package com.kukosin.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Convierte el sample rate al valor fijado en los ajustes (solo cuando NO se usa la ruta
 * bit-perfect, que debe entregar el rate original).
 *
 * El remuestreo lo hace [SincResampler]: filtro sinc con ventana de Blackman y tabla de fases.
 * Antes esto tomaba la muestra más cercana (nearest-neighbour), que al bajar el sample rate
 * **aliasa** todo lo que está por encima de Nyquist. Medido con un tono de 30 kHz bajando de
 * 96 kHz a 44.1 kHz: el nearest lo dejaba en −13.5 dBFS (un tono espurio de 14.1 kHz audible);
 * el sinc lo deja en −92 dBFS.
 */
@UnstableApi
class SampleRateAudioProcessor(
    private var targetSampleRateHz: Int = 0,
) : BaseAudioProcessor() {

    private val resampler = SincResampler()
    private var floatIn = FloatArray(0)
    private var floatOut = FloatArray(0)

    fun setTargetSampleRate(sampleRateHz: Int) {
        if (targetSampleRateHz != sampleRateHz) {
            targetSampleRateHz = sampleRateHz
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (targetSampleRateHz <= 0 || inputAudioFormat.sampleRate == targetSampleRateHz) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (!isSupportedEncoding(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        resampler.configure(
            inputAudioFormat.sampleRate,
            targetSampleRateHz,
            inputAudioFormat.channelCount,
        )
        return AudioProcessor.AudioFormat(
            targetSampleRateHz,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val frames = if (bytesPerFrame > 0) (limit - position) / bytesPerFrame else 0
        if (frames <= 0) {
            inputBuffer.position(limit)
            return
        }

        val sampleCount = frames * inputAudioFormat.channelCount
        if (floatIn.size < sampleCount) floatIn = FloatArray(sampleCount)
        readSamples(inputBuffer, position, sampleCount, inputAudioFormat.encoding, floatIn)

        val capacity = resampler.maxOutputFrames(frames) * inputAudioFormat.channelCount
        if (floatOut.size < capacity) floatOut = FloatArray(capacity)
        val outFrames = resampler.process(floatIn, frames, floatOut)

        if (outFrames > 0) {
            val outSamples = outFrames * outputAudioFormat.channelCount
            val out = replaceOutputBuffer(outSamples * outputAudioFormat.bytesPerFrame)
            writeSamples(floatOut, outSamples, outputAudioFormat.encoding, out)
        }
        inputBuffer.position(limit)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onFlush() {
        resampler.reset()
    }

    //region Conversión PCM ↔ float

    private fun isSupportedEncoding(encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    private fun readSamples(
        buffer: ByteBuffer,
        start: Int,
        count: Int,
        encoding: Int,
        out: FloatArray,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var p = start
                for (i in 0 until count) {
                    val lo = buffer.get(p).toInt() and 0xFF
                    val hi = buffer.get(p + 1).toInt()
                    out[i] = (((hi shl 8) or lo).toShort()).toFloat() / 32768f
                    p += 2
                }
            }

            C.ENCODING_PCM_24BIT -> {
                var p = start
                for (i in 0 until count) {
                    val b0 = buffer.get(p).toInt() and 0xFF
                    val b1 = buffer.get(p + 1).toInt() and 0xFF
                    val b2 = buffer.get(p + 2).toInt()
                    out[i] = ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608f
                    p += 3
                }
            }

            C.ENCODING_PCM_32BIT -> {
                var p = start
                for (i in 0 until count) {
                    var v = 0
                    for (b in 3 downTo 0) v = (v shl 8) or (buffer.get(p + b).toInt() and 0xFF)
                    out[i] = v.toFloat() / 2147483648f
                    p += 4
                }
            }

            else -> { // C.ENCODING_PCM_FLOAT
                var p = start
                for (i in 0 until count) {
                    var v = 0
                    for (b in 3 downTo 0) v = (v shl 8) or (buffer.get(p + b).toInt() and 0xFF)
                    out[i] = Float.fromBits(v)
                    p += 4
                }
            }
        }
    }

    private fun writeSamples(
        src: FloatArray,
        count: Int,
        encoding: Int,
        out: ByteBuffer,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) {
                val v = quantize16(src[i])
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
            }

            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val v = quantize24(src[i])
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
                out.put(((v shr 16) and 0xFF).toByte())
            }

            C.ENCODING_PCM_32BIT -> for (i in 0 until count) {
                val v = quantize32(src[i])
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
                out.put(((v shr 16) and 0xFF).toByte())
                out.put(((v shr 24) and 0xFF).toByte())
            }

            else -> for (i in 0 until count) { // C.ENCODING_PCM_FLOAT
                val v = src[i].coerceIn(-1f, 1f).toRawBits()
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
                out.put(((v shr 16) and 0xFF).toByte())
                out.put(((v shr 24) and 0xFF).toByte())
            }
        }
        out.flip()
    }

    private fun quantize16(x: Float): Int {
        if (abs(x) >= 1f) return if (x > 0f) 32767 else -32768
        return (x * 32768f).roundToInt().coerceIn(-32768, 32767)
    }

    private fun quantize24(x: Float): Int {
        if (abs(x) >= 1f) return if (x > 0f) 8388607 else -8388608
        return (x * 8388608f).roundToInt().coerceIn(-8388608, 8388607)
    }

    private fun quantize32(x: Float): Int {
        if (abs(x) >= 1f) return if (x > 0f) Int.MAX_VALUE else Int.MIN_VALUE
        return (x.toDouble() * 2147483648.0).roundToLong()
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    //endregion
}
