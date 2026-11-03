package com.kukosin.app.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kukosin.app.eq.EqEngine
import com.kukosin.app.eq.EqManager
import java.nio.ByteBuffer

/**
 * Procesador de audio del ecualizador para la cadena de ExoPlayer / Media3.
 *
 * Se inserta en [HiFiRenderersFactory] antes del motor Oboe, en la única ruta de
 * salida que tiene la app.
 *
 * Decisiones de calidad:
 * - No cambia el formato de audio (entrada == salida): el decoder sigue entregando su
 *   profundidad real y el sink la recibe intacta.
 * - Convierte a Double sólo para el cálculo, sin cuantización intermedia entre bandas.
 * - Si el EQ está apagado o plano, la ruta es un **memcpy** de los bytes: bit-perfect,
 *   cero aritmética sobre la señal.
 * - Soporta PCM 16/24/32 bits y float, que es todo lo que puede entregar MediaCodec/FFmpeg.
 */
@UnstableApi
class EqAudioProcessor : BaseAudioProcessor() {

    private val engine = EqEngine()

    private var bytes = ByteArray(0)
    private var samples = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!PcmCodec.isSupported(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = inputAudioFormat.bytesPerFrame
        val remaining = inputBuffer.remaining()
        if (frameBytes <= 0 || remaining < frameBytes) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val frames = remaining / frameBytes
        val usable = frames * frameBytes
        val bytesNeeded = usable
        if (bytes.size < bytesNeeded) bytes = ByteArray(bytesNeeded)
        val data = bytes
        inputBuffer.get(data, 0, usable)

        engine.requestSettings(EqManager.snapshot)

        if (!engine.isBypass) {
            val channelCount = inputAudioFormat.channelCount
            val sampleCount = frames * channelCount
            if (samples.size < sampleCount) samples = DoubleArray(sampleCount)
            val buf = samples
            decode(data, buf, sampleCount, inputAudioFormat.encoding)
            val peak = engine.processInterleaved(buf, frames, channelCount)
            encode(buf, data, sampleCount, inputAudioFormat.encoding)
            EqManager.reportPeak(peak.toFloat(), peak > 1.0)
        }

        val out = replaceOutputBuffer(usable)
        out.put(data, 0, usable)
        out.flip()
    }

    override fun onFlush() {
        engine.resetStates()
    }

    override fun onReset() {
        engine.resetStates()
    }

    // -------------------------------------------------------- conversión PCM

    private fun decode(src: ByteArray, dst: DoubleArray, count: Int, encoding: Int) =
        PcmCodec.decode(src, 0, dst, 0, count, encoding)

    private fun encode(src: DoubleArray, dst: ByteArray, count: Int, encoding: Int) =
        PcmCodec.encode(src, 0, dst, 0, count, encoding)
}
