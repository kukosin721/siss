package com.kukosin.app.audio

import com.kukosin.app.playback.OutputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado del motor para la UI: qué se abrió de verdad, no lo que pedimos. */
data class AudioStatus(
    val info: EngineInfo = EngineInfo(),
    val deviceName: String = "—",
    val mode: OutputMode = OutputMode.DISABLED,
    /** Un altavoz interno nunca puede ser bit-perfect: tiene su propio DSP y amplificador. */
    val builtinSpeaker: Boolean = false,
    val message: String? = null,
) {
    /**
     * El ecualizador está metiendo DSP ahora mismo. Apagado o con la curva plana el
     * procesador no toca ni un byte (bypass), así que no rompe el bit-perfect.
     */
    val eqActive: Boolean
        get() = try {
            com.kukosin.app.eq.EqManager.snapshot.isProcessing
        } catch (_: Throwable) {
            false
        }

    /**
     * Bit-perfect **verificado**: exclusivo, sin conversión, volumen al 100 %, sin EQ
     * encendido y sobre una salida que puede serlo (jack, línea o DAC USB; jamás el
     * altavoz interno).
     */
    val bitPerfectNow: Boolean
        get() = info.bitPerfect && mode.usesEngine && !builtinSpeaker && !eqActive
}

/**
 * Espejo observable de lo que está haciendo el motor nativo.
 *
 * El motor vive dentro del sink de Media3, así que no hay hilo propio acá: la UI
 * llama a [refresh] (barato, sólo lee enteros por JNI) mientras la pantalla está
 * abierta, y el provider publica al abrir/cerrar.
 */
object AudioEngineStatus {

    private val _status = MutableStateFlow(AudioStatus())
    val status: StateFlow<AudioStatus> = _status.asStateFlow()

    @Volatile
    private var engine: AudioEngine? = null

    @Volatile
    private var deviceName: String = "—"

    @Volatile
    private var mode: OutputMode = OutputMode.DISABLED

    @Volatile
    private var builtinSpeaker: Boolean = false

    @Volatile
    private var lastError: String? = null

    fun attach(engine: AudioEngine, deviceName: String, mode: OutputMode, builtinSpeaker: Boolean) {
        this.engine = engine
        this.deviceName = deviceName
        this.mode = mode
        this.builtinSpeaker = builtinSpeaker
        lastError = null
        refresh()
    }

    fun detach() {
        engine = null
        deviceName = "—"
        builtinSpeaker = false
        lastError = null
        // El modo elegido sobrevive al cierre del stream: sirve para explicar por qué
        // no hay bit-perfect cuando el modo es "Disabled" (mezclador).
        _status.value = AudioStatus(mode = mode)
    }

    /**
     * Publica el modo elegido aunque no haya motor abierto (caso "Disabled", donde
     * el sink nunca instala el provider de Oboe y por lo tanto nunca llama a [attach]).
     */
    fun setMode(newMode: OutputMode) {
        mode = newMode
        if (engine == null) {
            _status.value = AudioStatus(mode = newMode, builtinSpeaker = builtinSpeaker, message = lastError)
        } else {
            refresh()
        }
    }

    /** Vuelve a leer el estado nativo. No-op si no hay motor abierto. */
    fun refresh() {
        val current = engine ?: return
        val info = runCatching { current.info() }.getOrNull() ?: return
        _status.value = AudioStatus(
            info = info,
            deviceName = deviceName,
            mode = mode,
            builtinSpeaker = builtinSpeaker,
            message = lastError,
        )
    }

    /**
     * Volumen del motor (0..1) desde la UI. Sólo tiene efecto cuando la atenuación va en
     * la app: si el DAC tiene atenuador propio, [OboeAudioOutput] lo usa y el motor queda
     * a 1.0, de modo que llamar esto no rompe el bit-perfect.
     */
    fun setVolume(fraction: Float) {
        val current = engine ?: return
        runCatching { current.setVolume(fraction.coerceIn(0f, 1f)) }
        refresh()
    }

    fun error(message: String) {
        lastError = message
        _status.value = _status.value.copy(message = message)
    }
}
