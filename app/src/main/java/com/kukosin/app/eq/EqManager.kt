package com.kukosin.app.eq

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartido del ecualizador: UI ⇄ hilo de audio.
 *
 * - [snapshot] es lo que lee el procesador de audio (lectura volatile, sin locks ni
 *   asignaciones en el hilo de audio).
 * - [settings] es el StateFlow que observa Compose.
 * - Los cambios se persisten en SharedPreferences.
 * - [meter] / [clipping] alimentan el vúmetro de la pantalla del EQ.
 */
object EqManager {

    private const val PREFS = "kukosin_eq_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PREAMP = "preamp"
    private const val KEY_AUTO_HEADROOM = "auto_headroom"
    private const val KEY_PRESET = "preset"

    @Volatile
    var snapshot: EqSettings = EqSettings()
        private set

    private val _settings = MutableStateFlow(EqSettings())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    /** Pico actual (0..1) con caída suave; lo consume la UI. */
    private val _meter = MutableStateFlow(0f)
    val meter: StateFlow<Float> = _meter.asStateFlow()

    private val _clipping = MutableStateFlow(false)
    val clipping: StateFlow<Boolean> = _clipping.asStateFlow()

    /** Perfil activo (altavoz / auriculares / DAC USB). */
    private val _profile = MutableStateFlow(EqProfile.WIRED)
    val profile: StateFlow<EqProfile> = _profile.asStateFlow()

    /** Estado de cada perfil ya tocado en esta sesión (se persiste por separado). */
    private val profileCache = HashMap<EqProfile, EqSettings>()

    @Volatile
    private var peakAccum = 0f

    @Volatile
    private var clipAccum = false

    private var prefs: SharedPreferences? = null

    /** Idempotente. Llamar antes de construir el player y también desde la UI. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded()
        val loaded = load(_profile.value)
        profileCache[_profile.value] = loaded
        snapshot = loaded
        _settings.value = loaded
    }

    /** Publica un estado nuevo, lo persiste y lo deja disponible para el motor. */
    fun update(transform: (EqSettings) -> EqSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        // Fuerza el cálculo del pico de la curva (headroom) en el hilo de UI: el hilo de
        // audio nunca debe pagar ese costo.
        next.effectivePreampDb
        snapshot = next
        _settings.value = next
        profileCache[_profile.value] = next
        persist(_profile.value, next)
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun toggle() = update { it.copy(enabled = !it.enabled) }

    fun setPreamp(db: Float) =
        update { it.copy(preampDb = db.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)) }

    fun setAutoHeadroom(enabled: Boolean) = update { it.copy(autoHeadroom = enabled) }

    fun setBandGain(index: Int, db: Float) = update { it.withBandGain(index, db) }

    fun setBandFrequency(index: Int, hz: Float) = update { it.withBandFrequency(index, hz) }

    fun setBandQ(index: Int, q: Float) = update { it.withBandQ(index, q) }

    fun setBandType(index: Int, type: EqBandType) = update { it.withBandType(index, type) }

    /**
     * Cambia el perfil activo. Lo llama la app cuando cambia la salida (altavoz,
     * jack, DAC USB). No toca el hilo de audio más que la referencia [snapshot].
     */
    @Synchronized
    fun setProfile(profile: EqProfile) {
        if (profile == _profile.value) return
        profileCache[_profile.value] = _settings.value
        _profile.value = profile
        val next = profileCache[profile] ?: load(profile)
        profileCache[profile] = next
        snapshot = next
        _settings.value = next
        // El pico de la curva se calcula acá, en el hilo de UI.
        next.effectivePreampDb
    }

    fun applyPreset(name: String) {
        val preset = EqPresets.byName(name) ?: return
        update { it.applyingPreset(preset) }
    }

    fun resetFlat() = update {
        it.copy(
            bands = it.bands.map { band -> band.copy(gainDb = 0f) },
            preampDb = 0f,
            presetName = EqPresets.default.name,
        )
    }

    // ----------------------------------------------------------------- vúmetro

    /** Llamado desde el hilo de audio: solo escrituras volatile, sin asignaciones. */
    fun reportPeak(peak: Float, clipped: Boolean) {
        if (peak > peakAccum) peakAccum = peak
        if (clipped) clipAccum = true
    }

    /** Llamado desde la UI (~20 Hz): devuelve el pico acumulado y lo reinicia. */
    fun consumePeak(decay: Float = 0.82f): Pair<Float, Boolean> {
        val p = peakAccum
        peakAccum = 0f
        val prev = _meter.value * decay
        val shown = if (p > prev) p else prev
        _meter.value = shown.coerceIn(0f, 1.2f)
        val clipped = clipAccum
        if (clipped) clipAccum = false
        _clipping.value = _clipping.value || clipped
        return shown to clipped
    }

    fun clearClipIndicator() {
        _clipping.value = false
    }

    // ------------------------------------------------------------ persistencia

    private fun prefix(profile: EqProfile) = "${profile.name.lowercase()}_"

    private fun load(profile: EqProfile): EqSettings {
        val p = prefs ?: return EqSettings()
        val pre = prefix(profile)
        val bands = DefaultEqBands.mapIndexed { i, band ->
            band.copy(
                freqHz = p.getFloat("${pre}freq_$i", band.freqHz),
                gainDb = p.getFloat("${pre}gain_$i", band.gainDb),
                q = p.getFloat("${pre}q_$i", band.q),
                type = runCatching {
                    EqBandType.valueOf(
                        p.getString("${pre}type_$i", band.type.name) ?: band.type.name,
                    )
                }.getOrDefault(band.type),
            )
        }
        return EqSettings(
            enabled = p.getBoolean("${pre}$KEY_ENABLED", false),
            preampDb = p.getFloat("${pre}$KEY_PREAMP", 0f),
            autoHeadroom = p.getBoolean("${pre}$KEY_AUTO_HEADROOM", true),
            presetName = p.getString("${pre}$KEY_PRESET", EqPresets.default.name) ?: EqPresets.default.name,
            bands = bands,
        )
    }

    private fun persist(profile: EqProfile, settings: EqSettings) {
        val p = prefs ?: return
        val pre = prefix(profile)
        p.edit().apply {
            putBoolean("${pre}$KEY_ENABLED", settings.enabled)
            putFloat("${pre}$KEY_PREAMP", settings.preampDb)
            putBoolean("${pre}$KEY_AUTO_HEADROOM", settings.autoHeadroom)
            putString("${pre}$KEY_PRESET", settings.presetName)
            settings.bands.forEachIndexed { i, band ->
                putFloat("${pre}freq_$i", band.freqHz)
                putFloat("${pre}gain_$i", band.gainDb)
                putFloat("${pre}q_$i", band.q)
                putString("${pre}type_$i", band.type.name)
            }
        }.apply()
    }

    /**
     * Copia los ajustes del formato viejo (una sola curva para todo) al perfil de
     * auriculares, una única vez. Así nadie pierde lo que ya había configurado.
     */
    private fun migrateLegacyIfNeeded() {
        val p = prefs ?: return
        if (!p.contains(KEY_ENABLED)) return
        if (p.contains("${prefix(EqProfile.WIRED)}$KEY_ENABLED")) return
        val pre = prefix(EqProfile.WIRED)
        p.edit().apply {
            putBoolean("${pre}$KEY_ENABLED", p.getBoolean(KEY_ENABLED, false))
            putFloat("${pre}$KEY_PREAMP", p.getFloat(KEY_PREAMP, 0f))
            putBoolean("${pre}$KEY_AUTO_HEADROOM", p.getBoolean(KEY_AUTO_HEADROOM, true))
            putString("${pre}$KEY_PRESET", p.getString(KEY_PRESET, EqPresets.default.name))
            for (i in DefaultEqBands.indices) {
                putFloat("${pre}freq_$i", p.getFloat("freq_$i", DefaultEqBands[i].freqHz))
                putFloat("${pre}gain_$i", p.getFloat("gain_$i", DefaultEqBands[i].gainDb))
                putFloat("${pre}q_$i", p.getFloat("q_$i", DefaultEqBands[i].q))
                putString("${pre}type_$i", DefaultEqBands[i].type.name)
            }
        }.apply()
    }
}
