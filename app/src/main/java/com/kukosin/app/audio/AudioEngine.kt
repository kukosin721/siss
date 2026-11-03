package com.kukosin.app.audio

import java.nio.ByteBuffer

/**
 * Protocolo entero compartido con `native-lib.cpp`. No reordenar los códigos.
 */
enum class PcmFormat(val code: Int, val bits: Int, val label: String) {
    INT16(0, 16, "16-bit"),
    INT24(1, 24, "24-bit"),
    FLOAT32(2, 32, "32-bit float"),
    INT32(3, 32, "32-bit int");

    companion object {
        fun fromCode(code: Int): PcmFormat = when (code) {
            0 -> INT16
            1 -> INT24
            2 -> FLOAT32
            3 -> INT32
            else -> INT16
        }

        fun fromMedia3Encoding(encoding: Int): PcmFormat = when (encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> INT16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> INT24
            androidx.media3.common.C.ENCODING_PCM_32BIT -> INT32
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> FLOAT32
            else -> INT16
        }
    }
}

/** Estado del stream de Oboe (espejo de oboe::StreamState). */
enum class EngineState(val code: Int) {
    UNINITIALIZED(0),
    UNKNOWN(1),
    OPEN(2),
    STARTING(3),
    STARTED(4),
    PAUSING(5),
    PAUSED(6),
    FLUSHING(7),
    FLUSHED(8),
    STOPPING(9),
    STOPPED(10),
    CLOSING(11),
    CLOSED(12),
    DISCONNECTED(13);

    companion object {
        fun fromCode(code: Int): EngineState = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Espejo de oboe::Result. */
enum class NativeResult(val code: Int, val description: String) {
    OK(0, "OK"),
    ERROR_DISCONNECTED(-899, "Device disconnected"),
    ERROR_ILLEGAL_ARGUMENT(-898, "Invalid argument"),
    ERROR_INVALID_STATE(-895, "Invalid state"),
    ERROR_UNAVAILABLE(-892, "Format/rate not available on the device"),
    ERROR_NO_MEMORY(-890, "Out of memory"),
    ERROR_INVALID_FORMAT(-886, "Unsupported format"),
    ERROR_OUT_OF_RANGE(-885, "Out of range"),
    ERROR_INVALID_RATE(-883, "Unsupported sample rate"),
    ERROR_CLOSED(-882, "Stream closed"),
    ERROR_NULL(-889, "Engine not initialized"),
    UNKNOWN(Int.MIN_VALUE, "Unknown error");

    val isOk: Boolean get() = code == 0

    companion object {
        fun fromCode(code: Int): NativeResult =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Lo que pide la app: el formato real de la pista ya decodificada. */
data class AudioEngineConfig(
    val sourceFormat: PcmFormat = PcmFormat.INT16,
    val sourceSampleRate: Int = 48_000,
    val sourceChannels: Int = 2,
    /** Id del [android.media.AudioDeviceInfo] al que queremos salir (0 = predeterminado). */
    val deviceId: Int = 0,
    /** Si es true, el motor no acepta conversión: o es exacto, o falla y lo dice. */
    val strictBitPerfect: Boolean = false,
    /** Si es true, no se acepta un stream compartido (modos exclusivos). */
    val requireExclusive: Boolean = false,
    /**
     * Si es true y [deviceId] no se puede abrir, el motor falla en vez de caer al
     * dispositivo por defecto. Obligatorio en los modos que apuntan a un DAC USB.
     */
    val requireDevice: Boolean = false,
    /** Objetivo del anillo en milisegundos (lo que absorbe los tirones del decoder). */
    val bufferMs: Int = 250,
    /** Rampas de ~20 ms al arrancar/parar/pausar (evitan clics). */
    val fades: Boolean = true,
    /** Preroll del arranque en milisegundos (`-1` = automático: media ráfaga). */
    val prerollMs: Int = -1,
    /** 0 = volumen lineal, 1 = perceptual (v³). A 100 % ambas dan exactamente 1.0. */
    val volumeCurve: Int = 0,
)

/** Lo que devolvió el hardware, tal cual. */
data class EngineInfo(
    val opened: Boolean = false,
    val apiCode: Int = -1,
    val source: PcmFormat = PcmFormat.INT16,
    val sourceSampleRate: Int = 0,
    val sourceChannels: Int = 2,
    val stream: PcmFormat? = null,
    val streamSampleRate: Int = 0,
    val streamChannels: Int = 0,
    val framesPerBurst: Int = 0,
    /** Buffer del HAL: lo que se descarta al saltar (no es el colchón del motor). */
    val bufferFrames: Int = 0,
    /** Anillo del motor: el colchón real contra los cortes. */
    val ringFrames: Int = 0,
    val deviceId: Int = 0,
    val xRuns: Int = -1,
    val exclusive: Boolean = false,
    val passthrough: Boolean = false,
    val volumePercent: Int = 100,
    val errorCode: Int = 0,
    /** Latencia real medida con el timestamp de AAudio (-1 = desconocida). */
    val latencyMicros: Int = -1,
    /** El stream usa el camino MMAP: sin mezclador, latencia mínima. */
    val mmap: Boolean = false,
    /** Veces que el callback se quedó sin datos (diagnóstico de estabilidad). */
    val underruns: Int = 0,
    /** El HAL impuso otro rate o formato: no hay bit-perfect y se muestra tal cual. */
    val halConverted: Boolean = false,
) {
    val apiLabel: String
        get() = when (apiCode) {
            0 -> "AAudio"
            1 -> "OpenSL ES"
            else -> "—"
        }

    /**
     * Milisegundos de colchón del MOTOR (el anillo). Es lo que absorbe los huecos del
     * HAL; el buffer del hardware es aparte y mucho más chico.
     */
    val bufferMs: Double
        get() {
            val frames = if (ringFrames > 0) ringFrames else bufferFrames
            return if (streamSampleRate > 0) frames * 1000.0 / streamSampleRate else 0.0
        }

    /** Buffer del HAL en milisegundos (latencia que se descarta al saltar). */
    val halBufferMs: Double
        get() = if (streamSampleRate > 0) bufferFrames * 1000.0 / streamSampleRate else 0.0

    /** Bit-perfect real: exclusivo, sin conversión y sin atenuación digital. */
    val bitPerfect: Boolean
        get() = opened && exclusive && passthrough && volumePercent >= 100 && !halConverted

    /**
     * Hubo conversión en alguna parte: el motor resamplea/recodifica, o el HAL la hizo
     * por su cuenta. La UI lo muestra tal cual en vez de prometer bit-perfect.
     */
    val converted: Boolean
        get() = opened && (!passthrough || halConverted || streamSampleRate != sourceSampleRate ||
            stream?.bits != source.bits)

    /** Latencia de la cadena nativa en milisegundos (-1 = desconocida). */
    val latencyMs: Double get() = if (latencyMicros >= 0) latencyMicros / 1000.0 else -1.0

    /** Texto corto para la UI: "24-bit · 96 kHz · exclusive · MMAP". */
    val streamLabel: String
        get() {
            val fmt = stream ?: return "—"
            val sharing = if (exclusive) "exclusive" else "shared"
            val path = if (mmap) " · MMAP" else ""
            return "${fmt.bits}-bit · ${(streamSampleRate / 1000.0).format(1)} kHz · $sharing$path"
        }
}

private fun Double.format(decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f", this)

/**
 * Puente JNI al motor nativo ([audio::AudioEngine] en C++).
 *
 * Un [AudioEngine] = un stream de Oboe = un formato. Si la pista cambia de rate o de
 * profundidad, Media3 reconstruye el sink y se crea un motor nuevo: es el precio de no
 * resamplear.
 */
class AudioEngine : AutoCloseable {

    private val lock = Any()

    @Volatile
    private var handle: Long = nativeCreate()

    private val infoBuffer = IntArray(INFO_SIZE)

    init {
        require(handle != 0L) { "Could not create the native audio engine" }
    }

    /**
     * El motor lo cierra el hilo de audio de Media3 mientras la UI puede estar leyendo
     * el estado: sin este candado habría un use-after-free en JNI.
     */
    private inline fun <T> withHandle(default: T, block: (Long) -> T): T = synchronized(lock) {
        val current = handle
        if (current == 0L) default else block(current)
    }

    val state: EngineState
        get() = withHandle(EngineState.UNINITIALIZED) { EngineState.fromCode(nativeGetState(it)) }

    val isOpen: Boolean get() = info().opened

    /** Posición de playout en microsegundos (lo que ya sonó, no lo copiado). */
    fun positionMicros(): Long = withHandle(0L) { nativeGetPositionMicros(it) }

    /** Latencia real del camino de audio en microsegundos (-1 = desconocida). */
    fun latencyMicros(): Long = withHandle(-1L) { nativeGetLatencyMicros(it) }

    /** true si el motor ya vació todo lo encolado (fin de stream real). */
    fun isEndOfStream(): Boolean = withHandle(false) { nativeIsEndOfStream(it) }

    /** Frames de origen encolados desde el último flush (diagnóstico). */
    val sourceFramesQueued: Long get() = withHandle(0L) { nativeGetSourceFrames(it) }

    /**
     * Configura y lanza la apertura del stream en un hilo nativo propio.
     *
     * **No bloquea**: la negociación con el HAL (exclusividad, MMAP) ocurre fuera del
     * hilo que llama. El primer `write`/`start` espera a que termine y, si falló,
     * devuelve el error para que Media3 reconstruya el sink.
     */
    fun configure(config: AudioEngineConfig): NativeResult = withHandle(NativeResult.ERROR_NULL) { h ->
        NativeResult.fromCode(
            nativeConfigure(
                h,
                config.sourceFormat.code,
                config.sourceSampleRate,
                config.sourceChannels,
                config.deviceId,
                config.strictBitPerfect,
                config.requireExclusive,
                config.requireDevice,
                config.bufferMs,
                config.fades,
                config.prerollMs,
                config.volumeCurve,
            ),
        )
    }

    /** Último error nativo (0 = ninguno). */
    fun errorCode(): Int = withHandle(0) { h -> nativeErrorCode(h) }

    /** Espera (con timeout) el resultado de la apertura diferida. */
    fun awaitOpen(timeoutMs: Int): NativeResult = withHandle(NativeResult.ERROR_NULL) { h ->
        NativeResult.fromCode(nativeAwaitOpen(h, timeoutMs))
    }

    fun start(): NativeResult =
        withHandle(NativeResult.ERROR_NULL) { NativeResult.fromCode(nativeStart(it)) }

    /**
     * Fin de stream con drenado: espera (con timeout) a que el DAC consuma lo que queda
     * antes de devolver. Media3 llama a `stop()` una sola vez por stream.
     */
    fun stopAndWait(timeoutMs: Int = 750): NativeResult =
        withHandle(NativeResult.ERROR_NULL) { NativeResult.fromCode(nativeStopAndWait(it, timeoutMs)) }

    fun pause(): NativeResult =
        withHandle(NativeResult.ERROR_NULL) { NativeResult.fromCode(nativePause(it)) }

    /**
     * Descarta lo pendiente y deja el stream reutilizable.
     *
     * [waitForHardware] en `false` es el camino de las transiciones entre pistas: los
     * datos propios se descartan de inmediato y no se esperan las transiciones de
     * estado del HAL (que entre canción y canción se escuchaban como bache).
     */
    fun flush(waitForHardware: Boolean = true): NativeResult =
        withHandle(NativeResult.ERROR_NULL) {
            NativeResult.fromCode(nativeFlush(it, waitForHardware))
        }

    /** Curva de volumen: 0 = lineal, 1 = perceptual. */
    fun setVolumeCurve(curve: Int) = withHandle(Unit) { nativeSetVolumeCurve(it, curve) }

    /** Activa/desactiva las rampas en caliente (modo purista sin fundidos). */
    fun setFadesEnabled(enabled: Boolean) = withHandle(Unit) { nativeSetFades(it, enabled) }

    /**
     * Volumen 0..1 de la SEÑAL.
     *
     * Ojo: esto no es el volumen del teléfono. El motor sólo lo usa cuando el modo
     * decide que la atenuación va en la app (AAudio exclusivo o DAC sin control USB).
     * En los demás modos el volumen lo aplica el sistema o el atenuador del DAC y acá
     * se fija 1.0 para que la muestra no se toque.
     */
    fun setVolume(volume: Float) = withHandle(Unit) {
        nativeSetVolume(it, volume.coerceIn(0f, 1f))
    }

    /**
     * Escribe PCM ya decodificado. Devuelve los bytes de [buffer] aceptados
     * (0 = el ring está lleno, reintentar). Nunca bloquea.
     */
    fun write(buffer: ByteBuffer): Int {
        val remaining = buffer.remaining()
        if (remaining <= 0) return 0
        return withHandle(0) { h ->
            if (buffer.isDirect) {
                nativeWrite(h, buffer, buffer.position(), remaining).coerceIn(0, remaining)
            } else {
                val copy = ByteArray(remaining)
                val position = buffer.position()
                buffer.get(copy, 0, remaining)
                buffer.position(position)
                val direct = ByteBuffer.allocateDirect(remaining)
                direct.put(copy).flip()
                nativeWrite(h, direct, 0, remaining).coerceIn(0, remaining)
            }
        }
    }

    fun info(): EngineInfo = withHandle(EngineInfo()) { h ->
        nativeGetInfo(h, infoBuffer)
        val open = infoBuffer[0] >= 0
        return@withHandle EngineInfo(
            opened = open,
            apiCode = infoBuffer[0],
            stream = if (open && infoBuffer[1] >= 0) PcmFormat.fromCode(infoBuffer[1]) else null,
            streamSampleRate = infoBuffer[2],
            streamChannels = infoBuffer[3],
            framesPerBurst = infoBuffer[4],
            ringFrames = infoBuffer[5],
            deviceId = infoBuffer[6],
            xRuns = infoBuffer[7],
            exclusive = infoBuffer[8] == 1,
            passthrough = infoBuffer[10] == 1,
            errorCode = infoBuffer[11],
            volumePercent = infoBuffer[12],
            source = PcmFormat.fromCode(infoBuffer[13]),
            sourceSampleRate = infoBuffer[14],
            sourceChannels = infoBuffer[15],
            latencyMicros = infoBuffer[16],
            mmap = infoBuffer[17] == 1,
            underruns = infoBuffer[18],
            halConverted = infoBuffer[19] == 1,
        )
    }

    override fun close() {
        synchronized(lock) {
            val current = handle
            if (current != 0L) {
                handle = 0L
                nativeDelete(current)
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDelete(handle: Long)
    private external fun nativeConfigure(
        handle: Long,
        format: Int,
        sampleRate: Int,
        channelCount: Int,
        deviceId: Int,
        strictBitPerfect: Boolean,
        requireExclusive: Boolean,
        requireDevice: Boolean,
        bufferMs: Int,
        fades: Boolean,
        prerollMs: Int,
        volumeCurve: Int,
    ): Int
    private external fun nativeAwaitOpen(handle: Long, timeoutMs: Int): Int
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStopAndWait(handle: Long, timeoutMs: Int): Int
    private external fun nativePause(handle: Long): Int
    private external fun nativeFlush(handle: Long, waitForHardware: Boolean): Int
    private external fun nativeSetVolume(handle: Long, volume: Float)
    private external fun nativeSetVolumeCurve(handle: Long, curve: Int)
    private external fun nativeSetFades(handle: Long, enabled: Boolean)
    private external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    private external fun nativeErrorCode(handle: Long): Int
    private external fun nativeGetState(handle: Long): Int
    private external fun nativeGetSourceFrames(handle: Long): Long
    private external fun nativeGetPositionMicros(handle: Long): Long
    private external fun nativeGetLatencyMicros(handle: Long): Long
    private external fun nativeIsEndOfStream(handle: Long): Boolean
    private external fun nativeGetInfo(handle: Long, out: IntArray)

    private companion object {
        const val INFO_SIZE = 20

        init {
            // `runCatching`: en una JVM de escritorio (tests unitarios) la librería nativa
            // no existe y cargarla tiraba `UnsatisfiedLinkError` al tocar CUALQUIER
            // constante de esta clase. En el dispositivo carga y el fallo nativo se
            // informa por el código de error de cada llamada, no por una excepción.
            runCatching { System.loadLibrary("audioengine") }
        }
    }
}
