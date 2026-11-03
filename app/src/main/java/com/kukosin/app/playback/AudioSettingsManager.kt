package com.kukosin.app.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ajustes de audio: modo de salida, política del motor (estricto o con fallback),
 * buffer, fundidos y algunos extras de la cola.
 *
 * Oboe está **desactivado por defecto** por ahora (ver [AudioConfig.outputMode]): el
 * camino estable es el mezclador de Android. Los modos de Oboe siguen disponibles en
 * Ajustes para cuando se repare su problema de audio.
 */
data class AudioConfig(
    /**
     * Camino de salida. Por defecto [OutputMode.DISABLED]: AudioTrack / mezclador de
     * Android, **sin Oboe**. Oboe queda disponible en Ajustes para probarlo, pero no se
     * usa solo: en varios dispositivos está dando problemas de audio y se va a reparar
     * aparte. Cuando eso pase, el default vuelve a [OutputMode.AUTO].
     */
    val outputMode: OutputMode = OutputMode.DISABLED,
    /**
     * Si está activo, el motor NUNCA convierte: o abre exacto, o falla y avisa.
     * Apagado (recomendado), intenta bit-perfect primero y sólo si el DAC no lo
     * acepta pasa a conversión propia.
     */
    val strictBitPerfect: Boolean = false,
    /**
     * Buffer objetivo en milisegundos (anillo del motor): más grande = más estable,
     * más latencia al saltar. El default es el del preset [ContinuityPreset.DEFAULT].
     */
    val bufferMs: Int = ContinuityPreset.DEFAULT.bufferMs,
    /**
     * Fundidos de 25 ms al arrancar y 60 ms al pausar/detener, más un fundido corto
     * después de un salto. Evita los clics del motor. Apagado = señal intacta desde
     * el primer frame (purista).
     */
    val fades: Boolean = true,
    val autoAnalyze: Boolean = false,
    val decoder: String = "FFmpeg (Lossless)",
    val queueCompactMode: Boolean = false,
    val showQueueBadge: Boolean = true,
    val queueShowHistory: Boolean = true,
    /**
     * Preset de continuidad elegido. Lo que usa el motor sale de acá ([bufferMs],
     * [fades] y [prerollMs]); guardar el preset permite mostrar cuál está activo incluso
     * si después se ajustan los valores a mano.
     */
    val continuity: ContinuityPreset = ContinuityPreset.DEFAULT,
    /** Preroll del arranque en ms (-1 = automático). */
    val prerollMs: Int = ContinuityPreset.DEFAULT.prerollMs,
    /** 0 = volumen lineal, 1 = perceptual (el deslizador se siente parejo). */
    val volumeCurve: Int = 1,
    /** Vaciado del motor al cambiar de pista sin esperar las transiciones del HAL. */
    val fastTransitions: Boolean = true,
) {
    /**
     * Sólo lo que obliga a reconstruir el ExoPlayer. Cambiar el aspecto de la cola
     * no debe cortar la reproducción.
     */
    val audioSignature: String
        get() = "$outputMode|$strictBitPerfect|$bufferMs|$fades|$decoder|$prerollMs|$volumeCurve"

    companion object {
        val BUFFER_OPTIONS = listOf(100, 250, 500, 1000)
    }
}

object AudioSettingsManager {
    private const val PREFS_NAME = "kukosin_audio_settings_v2"

    /**
     * Marca de la migración que apaga Oboe una sola vez.
     *
     * No alcanza con cambiar el valor por defecto de la clave: una instalación que ya
     * tenía guardado `AUTO` (u otro modo de Oboe) lo seguiría usando. Al primer arranque
     * con esta versión se fuerza el mezclador de Android; si después el usuario elige
     * Oboe a mano en Ajustes, esa elección se respeta en los arranques siguientes.
     */
    private const val KEY_OBOE_OFF_MIGRATION = "oboe_off_by_default_v1"

    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(AudioConfig())
    val configState: StateFlow<AudioConfig> = state.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            load()
        }
    }

    private fun load() {
        val p = prefs ?: return
        if (!p.getBoolean(KEY_OBOE_OFF_MIGRATION, false)) {
            // Oboe apagado de entrada (temporalmente): el audio sale por el mezclador
            // de Android, que es el camino estable. Se hace una sola vez para no pisar
            // al usuario que después lo vuelva a activar desde Ajustes.
            p.edit()
                .putString("output_mode_v4", OutputMode.DISABLED.name)
                .putBoolean(KEY_OBOE_OFF_MIGRATION, true)
                .apply()
        }
        state.value = AudioConfig(
            outputMode = OutputMode.fromName(p.getString("output_mode_v4", OutputMode.DISABLED.name)),
            strictBitPerfect = p.getBoolean("strict_bit_perfect", false),
            bufferMs = p.getInt(
                "buffer_ms",
                // Ojo: si en el teléfono quedó guardado un `buffer_ms` chico de una versión
                // anterior (250 o menos), se respeta —es una elección explícita—, pero
                // una instalación limpia arranca con el colchón del preset por defecto.
                ContinuityPreset.fromName(p.getString("continuity_preset", null)).bufferMs,
            ).coerceIn(40, 2000),
            fades = p.getBoolean("fades", true),
            autoAnalyze = p.getBoolean("auto_analyze", false),
            decoder = p.getString("decoder", "FFmpeg (Lossless)") ?: "FFmpeg (Lossless)",
            queueCompactMode = p.getBoolean("queue_compact_mode", false),
            showQueueBadge = p.getBoolean("show_queue_badge", true),
            queueShowHistory = p.getBoolean("queue_show_history", true),
            continuity = ContinuityPreset.fromName(p.getString("continuity_preset", null)),
            prerollMs = p.getInt("preroll_ms", -1),
            volumeCurve = p.getInt("volume_curve", 1),
            fastTransitions = p.getBoolean("fast_transitions", true),
        )
    }

    fun setOutputMode(mode: OutputMode) {
        prefs?.edit()?.putString("output_mode_v4", mode.name)?.apply()
        state.value = state.value.copy(outputMode = mode)
    }

    fun setStrictBitPerfect(enabled: Boolean) {
        prefs?.edit()?.putBoolean("strict_bit_perfect", enabled)?.apply()
        state.value = state.value.copy(strictBitPerfect = enabled)
    }

    /**
     * Aplica un preset completo (buffer + preroll + fundidos). Es una sola escritura y una
     * sola reconstrucción del reproductor, en vez de tres cambios encadenados.
     */
    fun setContinuity(preset: ContinuityPreset) {
        prefs?.edit()
            ?.putString("continuity_preset", preset.name)
            ?.putInt("buffer_ms", preset.bufferMs)
            ?.putInt("preroll_ms", preset.prerollMs)
            ?.putBoolean("fades", preset.fades)
            ?.apply()
        state.value = state.value.copy(
            continuity = preset,
            bufferMs = preset.bufferMs,
            prerollMs = preset.prerollMs,
            fades = preset.fades,
        )
    }

    fun setVolumeCurve(curve: Int) {
        val value = if (curve == 1) 1 else 0
        prefs?.edit()?.putInt("volume_curve", value)?.apply()
        state.value = state.value.copy(volumeCurve = value)
    }

    fun setFastTransitions(enabled: Boolean) {
        prefs?.edit()?.putBoolean("fast_transitions", enabled)?.apply()
        state.value = state.value.copy(fastTransitions = enabled)
    }

    fun setBufferMs(ms: Int) {
        val value = ms.coerceIn(40, 2000)
        prefs?.edit()?.putInt("buffer_ms", value)?.apply()
        state.value = state.value.copy(
            bufferMs = value,
            continuity = state.value.continuity,
        )
    }

    fun setFades(enabled: Boolean) {
        prefs?.edit()?.putBoolean("fades", enabled)?.apply()
        state.value = state.value.copy(fades = enabled)
    }

    fun setAutoAnalyze(enabled: Boolean) {
        prefs?.edit()?.putBoolean("auto_analyze", enabled)?.apply()
        state.value = state.value.copy(autoAnalyze = enabled)
    }

    fun setDecoder(decoder: String) {
        prefs?.edit()?.putString("decoder", decoder)?.apply()
        state.value = state.value.copy(decoder = decoder)
    }

    fun setQueueCompactMode(compact: Boolean) {
        prefs?.edit()?.putBoolean("queue_compact_mode", compact)?.apply()
        state.value = state.value.copy(queueCompactMode = compact)
    }

    fun setShowQueueBadge(show: Boolean) {
        prefs?.edit()?.putBoolean("show_queue_badge", show)?.apply()
        state.value = state.value.copy(showQueueBadge = show)
    }

    fun setQueueShowHistory(show: Boolean) {
        prefs?.edit()?.putBoolean("queue_show_history", show)?.apply()
        state.value = state.value.copy(queueShowHistory = show)
    }
}
