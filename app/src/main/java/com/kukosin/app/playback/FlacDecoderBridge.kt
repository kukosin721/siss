package com.kukosin.app.playback

import androidx.media3.common.util.UnstableApi
import java.io.File

/**
 * Envoltorio del decodificador FLAC propio (`app/src/main/cpp/audio/FlacDecoder.cpp`).
 *
 * Existe por una razón concreta: MediaCodec no entrega 24 bits. `c2.android.flac.decoder`
 * emite 16 bits (descartando los otros 8) o float, y el camino float de Media3 multiplica
 * por 1/0x7FFFFFFF, así que un FLAC de 24 bits deja de ser bit-perfect **antes** de llegar
 * al motor de audio. Acá el decodificador entrega las muestras enteras en su ancho nativo
 * (16 → int16, 24 → 3 bytes LE, 32 → int32), que es exactamente lo que [com.kukosin.app.audio.AudioEngine]
 * escribe al DAC sin conversión.
 *
 * Instancias no re-entrantes: una por hilo de carga (el extractor de Media3 las crea por
 * media period).
 */
@UnstableApi
class FlacDecoderBridge private constructor(private var handle: Long) : AutoCloseable {

    /** Formato del archivo, tal como lo declara STREAMINFO. */
    data class StreamFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val audioOffset: Long,
        val bytesPerSample: Int,
        val seekPoints: Int,
        val crcErrors: Int,
    ) {
        /** Byte de PCM por frame intercalado (todos los canales). */
        val frameBytes: Int get() = bytesPerSample * channels

        /** Duración en microsegundos (0 si STREAMINFO no la declara). */
        val durationUs: Long
            get() = if (sampleRate > 0 && totalSamples > 0) {
                totalSamples * 1_000_000L / sampleRate
            } else {
                0L
            }
    }

    private val infoBuffer = IntArray(INFO_SIZE)

    /** Bytes que ocupó en el archivo el último bloque entregado por [read]. */
    var lastBlockBytes: Long = 0L
        private set
    private val streamInfoBuffer = IntArray(STREAMINFO_SIZE)

    val isOpen: Boolean get() = handle != 0L

    fun format(): StreamFormat? {
        if (handle == 0L) return null
        if (!nativeGetInfo(handle, infoBuffer)) return null
        val totalSamples = ((infoBuffer[3].toLong() and 0xFFFFFFFFL) shl 32) or
            (infoBuffer[4].toLong() and 0xFFFFFFFFL)
        val audioOffset = ((infoBuffer[5].toLong() and 0xFFFFFFFFL) shl 32) or
            (infoBuffer[6].toLong() and 0xFFFFFFFFL)
        return StreamFormat(
            sampleRate = infoBuffer[0],
            channels = infoBuffer[1],
            bitsPerSample = infoBuffer[2],
            totalSamples = totalSamples,
            audioOffset = audioOffset,
            bytesPerSample = infoBuffer[7],
            seekPoints = infoBuffer[8],
            crcErrors = infoBuffer[9],
        )
    }

    /** Los 34 bytes de STREAMINFO, para reabrir el stream en otro punto sin releer metadatos. */
    fun streamInfo(): IntArray? {
        if (handle == 0L) return null
        if (!nativeGetStreamInfo(handle, streamInfoBuffer)) return null
        return streamInfoBuffer.copyOf()
    }

    /**
     * Puntos de la SEEKTABLE como pares (sample, offset de byte, frames del frame). Si el
     * archivo no trae tabla se devuelve una lista vacía y el extractor arma la suya con los
     * frames que va viendo mientras decodifica.
     */
    fun seekTable(): List<LongArray> {
        if (handle == 0L) return emptyList()
        val samples = LongArray(MAX_SEEK_POINTS)
        val offsets = LongArray(MAX_SEEK_POINTS)
        val frames = IntArray(MAX_SEEK_POINTS)
        val count = nativeGetSeekTable(handle, samples, offsets, frames)
        if (count <= 0) return emptyList()
        return List(count) { index ->
            longArrayOf(samples[index], offsets[index], frames[index].toLong())
        }
    }

    /**
     * Decodifica hasta [maxFrames] frames en [destination] (array de bytes, que es lo que
     * Media3 consume a través de `ParsableByteArray`).
     *
     * Devuelve [ReadResult.frames] `> 0` con los bytes válidos (`frames * frameBytes`), o `0`
     * al final del stream (también ante un error de decodificación: es preferible cortar la
     * pista que mandar basura al DAC). [ReadResult.blockBytes] es lo que ocupa ese bloque en
     * el archivo y sirve para mantener sincronizada la posición del `DataSource`.
     */
    fun read(destination: ByteArray, capacityBytes: Int, maxFrames: Int): ReadResult {
        if (handle == 0L) return ReadResult(0, 0)
        val packed = nativeRead(handle, destination, capacityBytes, maxFrames)
        val frames = (packed ushr 32).toInt()
        val blockBytes = packed and 0xFFFFFFFFL
        lastBlockBytes = blockBytes
        return ReadResult(frames, blockBytes)
    }

    /** Errores de CRC vistos hasta ahora (0 en un archivo sano). */
    fun crcErrors(): Int = if (handle == 0L) 0 else {
        nativeGetInfo(handle, infoBuffer)
        infoBuffer[9]
    }

    fun seekToSample(sampleNumber: Long): Boolean {
        if (handle == 0L) return false
        return nativeSeek(handle, sampleNumber)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeClose(current)
    }

    data class ReadResult(val frames: Int, val blockBytes: Long)

    companion object {
        private const val INFO_SIZE = 10
        private const val STREAMINFO_SIZE = 34
        private const val MAX_SEEK_POINTS = 4096

        init {
            System.loadLibrary("audioengine")
        }

        fun open(file: File): FlacDecoderBridge? {
            val handle = nativeOpenFile(file.absolutePath)
            return if (handle == 0L) null else FlacDecoderBridge(handle)
        }

        fun open(fileDescriptor: Int, length: Long): FlacDecoderBridge? {
            val handle = nativeOpenFd(fileDescriptor, length)
            return if (handle == 0L) null else FlacDecoderBridge(handle)
        }

        /** Reabre el stream en [frameOffset] reutilizando los 34 bytes de STREAMINFO. */
        fun openAtFrame(
            fileDescriptor: Int,
            length: Long,
            streamInfo: IntArray,
            frameOffset: Long,
            firstSampleNumber: Long,
        ): FlacDecoderBridge? {
            val handle = nativeOpenAtFrame(
                fileDescriptor,
                length,
                streamInfo,
                streamInfo.size,
                frameOffset,
                firstSampleNumber,
            )
            return if (handle == 0L) null else FlacDecoderBridge(handle)
        }

        /**
         * Arma el índice de frames recorriendo sólo las cabeceras (sin decodificar). Se usa
         * cuando el archivo no trae SEEKTABLE. Devuelve la cantidad de frames indexados.
         */
        fun buildFrameIndex(
            fileDescriptor: Int,
            length: Long,
            streamInfo: IntArray,
            offsets: LongArray,
            samples: LongArray,
            frameSamples: IntArray,
        ): Int = nativeBuildFrameIndex(
            fileDescriptor,
            length,
            streamInfo,
            streamInfo.size,
            offsets,
            samples,
            frameSamples,
        )

        private external fun nativeOpenFile(path: String): Long
        private external fun nativeOpenFd(fd: Int, length: Long): Long
        private external fun nativeOpenAtFrame(
            fd: Int,
            length: Long,
            streamInfo: IntArray,
            count: Int,
            frameOffset: Long,
            firstSampleNumber: Long,
        ): Long
        private external fun nativeBuildFrameIndex(
            fd: Int,
            length: Long,
            streamInfo: IntArray,
            count: Int,
            offsets: LongArray,
            samples: LongArray,
            frameSamples: IntArray,
        ): Int
        private external fun nativeClose(handle: Long)
        private external fun nativeGetInfo(handle: Long, out: IntArray): Boolean
        private external fun nativeGetStreamInfo(handle: Long, out: IntArray): Boolean
        private external fun nativeGetSeekTable(
            handle: Long,
            samples: LongArray,
            offsets: LongArray,
            frameSamples: IntArray,
        ): Int
        private external fun nativeRead(
            handle: Long,
            out: ByteArray,
            capacity: Int,
            maxFrames: Int,
        ): Long
        private external fun nativeSeek(handle: Long, sample: Long): Boolean
    }
}
