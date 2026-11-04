package com.kukosin.app.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Ganancia de la cadena: **fundidos entre pistas** y **ReplayGain**.
 *
 * Va antes del ecualizador, igual que en una consola: primero se nivela la pista y después
 * se corrige la curva. Es un procesador aparte de [EqAudioProcessor] a propósito — si el
 * usuario no usa ninguno de los dos, la ruta sigue siendo un **memcpy** de bytes, bit a
 * bit, sin una sola operación sobre la señal.
 *
 * No cambia el formato: lo que entra (16/24/32 bits o float) es lo que sale.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    private var bytes = ByteArray(0)
    private var samples = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!PcmCodec.isSupported(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
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
        if (bytes.size < usable) bytes = ByteArray(usable)
        val data = bytes
        inputBuffer.get(data, 0, usable)

        // Una sola lectura de ganancia por buffer: el valor ya viene interpolado por reloj,
        // y recalcularlo por muestra costaría 44.100 divisiones por segundo sin sonar
        // distinto.
        val gain = PlaybackGain.current()

        if (gain > 0.0001f && (gain < 0.9999f || gain > 1.0001f)) {
            val channelCount = inputAudioFormat.channelCount
            val sampleCount = frames * channelCount
            if (samples.size < sampleCount) samples = DoubleArray(sampleCount)
            val buf = samples
            PcmCodec.decode(data, 0, buf, 0, sampleCount, inputAudioFormat.encoding)
            for (i in 0 until sampleCount) {
                buf[i] *= gain
            }
            PcmCodec.encode(buf, 0, data, 0, sampleCount, inputAudioFormat.encoding)
        } else if (gain <= 0.0001f) {
            // Silencio exacto: no hace falta decodificar para llenar de ceros.
            java.util.Arrays.fill(data, 0, usable, 0.toByte())
        }

        val out = replaceOutputBuffer(usable)
        out.put(data, 0, usable)
        out.flip()
    }

    override fun onFlush() = Unit

    override fun onReset() = Unit
}
