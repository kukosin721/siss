package com.kukosin.app.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kukosin.app.data.analysis.Fft
import com.kukosin.app.ui.UiSettingsManager
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * Niveles del espectro para el visualizador del reproductor.
 *
 * Es un `object` y no parte del procesador porque lo lee la interfaz desde otro hilo: el
 * procesador escribe un array nuevo (nunca lo muta en el lugar) y la UI lo copia en el
 * `Canvas`. Así no hay locks ni tearing visible.
 */
object Spectrum {
    /** Cantidad de barras. 32 entra cómodo abajo de la carátula en cualquier pantalla. */
    const val BANDS = 32

    @Volatile
    private var current = FloatArray(BANDS)

    /** Sube en cada análisis: la UI lo usa para saber que hay datos nuevos. */
    @Volatile
    var version: Int = 0
        private set

    fun levels(): FloatArray = current

    fun publish(levels: FloatArray) {
        current = levels
        version++
    }

    fun clear() {
        current = FloatArray(BANDS)
        version++
    }
}

/**
 * Analiza el PCM que ya está pasando por la cadena y publica 32 bandas normalizadas.
 *
 * Decisiones:
 *  - Se analiza **un buffer de cada cuatro**: el espectro no necesita 43 actualizaciones por
 *    segundo para verse fluido, y el FFT de 512 puntos por buffer completo costaría batería
 *    sin que se note.
 *  - Ventana de Hann y escala en dB con caída suave: sin eso, el grave se come todo el
 *    gráfico y las barras tiemblan.
 *  - Con el visualizador apagado no decodifica nada: sólo copia bytes, igual que el resto de
 *    la cadena cuando no hay nada que hacer.
 */
@UnstableApi
class SpectrumAudioProcessor : BaseAudioProcessor() {

    private companion object {
        const val FFT_SIZE = 512
        const val ANALYSIS_EVERY = 4
        const val MIN_DB = -64.0
        const val MAX_DB = -6.0
    }

    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1)) }
    private val ring = DoubleArray(FFT_SIZE)
    private var ringPos = 0
    private var buffersSeen = 0

    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)

    private var bytes = ByteArray(0)
    private var samples = DoubleArray(0)
    private var levels = FloatArray(Spectrum.BANDS)
    private var analysing = false

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

        val enabled = runCatching { UiSettingsManager.settings.value.showSpectrum }.getOrDefault(false)
        if (enabled) {
            analysing = true
            buffersSeen++
            if (buffersSeen % ANALYSIS_EVERY == 0) {
                runCatching { analyze(data, frames) }
            }
        } else if (analysing) {
            // Recién apagado: se limpia una sola vez, no en cada buffer.
            analysing = false
            levels = FloatArray(Spectrum.BANDS)
            Spectrum.clear()
        }

        val out = replaceOutputBuffer(usable)
        out.put(data, 0, usable)
        out.flip()
    }

    private fun analyze(data: ByteArray, frames: Int) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleCount = frames * channels
        if (samples.size < sampleCount) samples = DoubleArray(sampleCount)
        val buf = samples
        PcmCodec.decode(data, 0, buf, 0, sampleCount, inputAudioFormat.encoding)

        // Mezcla a mono y alimenta el anillo (que se lee siempre en orden).
        var i = 0
        while (i < frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                sum += buf[i * channels + c]
            }
            ring[ringPos] = sum / channels
            ringPos = (ringPos + 1) % FFT_SIZE
            i++
        }

        // Copia rotada: el anillo empieza en la muestra más vieja.
        for (k in 0 until FFT_SIZE) {
            val index = (ringPos + k) % FFT_SIZE
            real[k] = ring[index] * window[k]
            imag[k] = 0.0
        }
        Fft.transform(real, imag)

        val next = FloatArray(Spectrum.BANDS)
        val bins = FFT_SIZE / 2
        // Reparto logarítmico: el oído no escucha en bins lineales y las primeras 20 barras
        // se comerían todo el rango si se repartiera parejo.
        val minBin = 1
        val maxBin = bins - 1
        for (band in 0 until Spectrum.BANDS) {
            val from = (minBin * (maxBin.toDouble() / minBin).pow(band.toDouble() / Spectrum.BANDS)).toInt()
                .coerceIn(minBin, maxBin)
            val to = (minBin * (maxBin.toDouble() / minBin).pow((band + 1).toDouble() / Spectrum.BANDS)).toInt()
            val last = to.coerceIn(from + 1, maxBin)
            var peak = 0.0
            for (bin in from until last) {
                val magnitude = real[bin] * real[bin] + imag[bin] * imag[bin]
                if (magnitude > peak) peak = magnitude
            }
            val amplitude = kotlin.math.sqrt(peak) / (FFT_SIZE / 2.0)
            val db = 20.0 * log10(amplitude.coerceAtLeast(1e-9))
            val normalized = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0.0, 1.0).toFloat()

            // Caída asimétrica: sube rápido, baja suave. Es lo que hace que las barras se
            // sientan como un vúmetro y no como un parpadeo.
            val previous = levels[band]
            next[band] = if (normalized > previous) {
                previous + (normalized - previous) * 0.55f
            } else {
                previous * 0.82f + normalized * 0.18f
            }
        }
        levels = next
        Spectrum.publish(next.copyOf())
    }

    override fun onFlush() {
        ring.fill(0.0)
        ringPos = 0
        levels = FloatArray(Spectrum.BANDS)
    }

    override fun onReset() {
        ring.fill(0.0)
        ringPos = 0
        levels = FloatArray(Spectrum.BANDS)
        Spectrum.clear()
    }
}
