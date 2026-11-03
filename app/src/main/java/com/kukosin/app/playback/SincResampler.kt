package com.kukosin.app.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Remuestreador de ventana sinc, con estado, para convertir sample rate sin el aliasing que
 * mete el nearest-neighbour.
 *
 * - FIR sinc con ventana de Blackman: [TAPS] coeficientes por fase.
 * - Tabla de [PHASES] fases precalculada, así no se evalúan senos por muestra.
 * - Al bajar el sample rate el corte se pone en 0.92 × Nyquist de salida: se filtra **antes**
 *   de decimar, que es justo lo que faltaba antes.
 * - Guarda cola de entrada y posición fraccionaria entre llamadas, así no hay cortes entre
 *   buffers consecutivos.
 *
 * Sin dependencias de Android a propósito: se puede compilar y medir en un JVM normal.
 */
internal class SincResampler {

    private companion object {
        /** Coeficientes por fase. 32 es un buen equilibrio entre calidad y CPU en móvil. */
        const val TAPS = 32

        /** Resolución de la tabla de fases. 512 → error de fase < 0.1 %. */
        const val PHASES = 512

        /** Corte al 92 % del Nyquist de salida: deja margen para la transición del filtro. */
        const val ROLLOFF = 0.92
    }

    private var channels = 0
    private var inRate = 0
    private var outRate = 0

    /** Cuántas muestras de entrada avanza cada muestra de salida. */
    private var step = 1.0

    /** Tabla de coeficientes, [PHASES] × [TAPS]. */
    private var coeffs = FloatArray(0)

    /** Entrada pendiente, intercalada (frame a frame por canal). */
    private var buffer = FloatArray(0)
    private var frames = 0

    /** Frame entero de lectura dentro de [buffer]. */
    private var readIndex = 0

    /** Parte fraccionaria de la posición, siempre en [0, 1). */
    private var readFrac = 0.0

    val isConfigured: Boolean
        get() = channels > 0 && inRate > 0 && outRate > 0

    /** Retardo propio del filtro, en frames de entrada (medio lóbulo). */
    val latencyInFrames: Int
        get() = TAPS / 2

    fun configure(inRate: Int, outRate: Int, channels: Int) {
        if (this.inRate == inRate && this.outRate == outRate && this.channels == channels) return
        this.inRate = inRate
        this.outRate = outRate
        this.channels = channels
        step = inRate.toDouble() / outRate.toDouble()
        buildKernel()
        prime()
    }

    fun reset() {
        prime()
    }

    /** Tope de frames que puede devolver [process] para [inputFrames] de entrada. */
    fun maxOutputFrames(inputFrames: Int): Int =
        if (step <= 0.0) inputFrames else (inputFrames / step).toInt() + 4

    /**
     * Remuestrea [inputFrames] frames intercalados de [input] y escribe el resultado en
     * [output]. Devuelve cuántos frames de salida escribió.
     */
    fun process(input: FloatArray, inputFrames: Int, output: FloatArray): Int {
        if (!isConfigured || inputFrames <= 0) return 0
        append(input, inputFrames)

        val half = TAPS / 2
        val maxOut = output.size / channels
        var outFrames = 0

        while (outFrames < maxOut) {
            val base = readIndex
            // Hace falta historia a ambos lados del punto de interpolación.
            if (base - (half - 1) < 0) break
            if (base + half >= frames) break

            val phase = (readFrac * PHASES).toInt().coerceIn(0, PHASES - 1)
            val coeffBase = phase * TAPS

            for (ch in 0 until channels) {
                var acc = 0f
                var idx = (base - (half - 1)) * channels + ch
                for (k in 0 until TAPS) {
                    acc += buffer[idx] * coeffs[coeffBase + k]
                    idx += channels
                }
                output[outFrames * channels + ch] = acc
            }

            outFrames++
            // Se avanza en entero + fracción en vez de sumar doubles: así el resultado no
            // depende de cómo se hayan partido los buffers y no se acumula deriva.
            readFrac += step
            if (readFrac >= 1.0) {
                val whole = readFrac.toInt()
                readIndex += whole
                readFrac -= whole
            }
        }

        dropConsumed()
        return outFrames
    }

    //region Interno

    private fun buildKernel() {
        val cutoff = ROLLOFF * min(1.0, outRate.toDouble() / inRate.toDouble())
        val half = TAPS / 2
        coeffs = FloatArray(PHASES * TAPS)

        for (phase in 0 until PHASES) {
            val frac = phase.toDouble() / PHASES
            var sum = 0.0
            val base = phase * TAPS

            for (k in 0 until TAPS) {
                val offset = k - (half - 1)
                val d = offset - frac
                val sinc = if (d == 0.0) {
                    1.0
                } else {
                    val arg = PI * cutoff * d
                    sin(arg) / arg
                }
                val x = d / half
                val window = if (abs(x) >= 1.0) {
                    0.0
                } else {
                    0.42 + 0.5 * cos(PI * x) + 0.08 * cos(2.0 * PI * x)
                }
                val c = cutoff * sinc * window
                coeffs[base + k] = c.toFloat()
                sum += c
            }

            // Normaliza cada fase: ganancia unidad en DC, sin escalones entre fases.
            if (sum != 0.0) {
                val norm = (1.0 / sum).toFloat()
                for (k in 0 until TAPS) coeffs[base + k] *= norm
            }
        }
    }

    /** Arranca con medio filtro de silencio, como si la canción viniera precedida de nada. */
    private fun prime() {
        val half = TAPS / 2
        val needed = half * channels
        if (buffer.size < needed) buffer = FloatArray(needed)
        if (needed > 0) buffer.fill(0f, 0, needed)
        frames = half
        readIndex = half
        readFrac = 0.0
    }

    private fun append(input: FloatArray, inputFrames: Int) {
        val needed = (frames + inputFrames) * channels
        if (buffer.size < needed) {
            val grown = FloatArray(maxOf(needed * 2, 2048 * channels.coerceAtLeast(1)))
            System.arraycopy(buffer, 0, grown, 0, frames * channels)
            buffer = grown
        }
        System.arraycopy(input, 0, buffer, frames * channels, inputFrames * channels)
        frames += inputFrames
    }

    private fun dropConsumed() {
        val half = TAPS / 2
        // Se conserva la historia que el próximo buffer todavía necesita.
        val keepFrom = readIndex - (half - 1)
        if (keepFrom <= 0) return
        val keepFrames = frames - keepFrom
        if (keepFrames > 0) {
            System.arraycopy(buffer, keepFrom * channels, buffer, 0, keepFrames * channels)
        }
        frames = keepFrames.coerceAtLeast(0)
        readIndex -= keepFrom
    }

    //endregion
}
