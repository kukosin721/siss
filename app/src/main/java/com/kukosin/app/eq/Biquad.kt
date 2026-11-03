package com.kukosin.app.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coeficientes de un biquad directo forma I/II normalizados por a0.
 *
 * y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]
 *
 * Se trabaja siempre en Double: con biquads en cascada el error de redondeo de Float
 * se acumula audiblemente en las frecuencias bajas (donde |a1| ronda 2).
 */
data class BiquadCoeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    val isBypass: Boolean
        get() = abs(b0 - 1.0) < 1e-9 && abs(b1) < 1e-9 && abs(b2) < 1e-9 &&
            abs(a1) < 1e-9 && abs(a2) < 1e-9

    companion object {
        val BYPASS = BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Diseño de filtros según el "Audio EQ Cookbook" de Robert Bristow-Johnson.
 *
 * Se usan formas de banda constante (constant-Q) para las campanas: el ancho de banda
 * no cambia al subir o bajar la ganancia, que es lo que hace una mesa de mezclas real.
 */
object BiquadDesign {

    private const val MIN_FREQ = 10.0
    private const val MAX_GAIN = 24.0
    /** Pendiente de los shelves (1.0 = transición suave, tipo consola). */
    private const val SHELF_SLOPE = 1.0

    /**
     * Calcula los coeficientes de un biquad y los escribe en [out] como
     * `[b0, b1, b2, a1, a2]`. Devuelve false si el filtro es unitario (bypass).
     *
     * No asigna memoria: es la ruta que usa el motor de audio en tiempo real.
     */
    fun designInto(
        type: EqBandType,
        sampleRate: Double,
        freqHz: Double,
        q: Double,
        gainDb: Double,
        out: DoubleArray,
    ): Boolean {
        // Sólo las bandas de ganancia pueden quedar inactivas por ganancia nula: un
        // notch o un pasa-altos actúan aunque su gainDb sea 0.
        if (type.usesGain && abs(gainDb) < 1e-3) {
            out[0] = 1.0
            out[1] = 0.0
            out[2] = 0.0
            out[3] = 0.0
            out[4] = 0.0
            return false
        }
        val fs = safeFs(sampleRate)
        val f0 = clampFreq(freqHz, fs)
        val safeQ = if (q.isFinite()) q.coerceIn(0.05, 40.0) else 1.41
        val a = 10.0.pow(gainDb.coerceIn(-MAX_GAIN, MAX_GAIN) / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        // Notch, pasa-bajos y pasa-altos no dependen de la ganancia: son filtros de
        // corte (útiles para eliminar un zumbido de 50/60 Hz o un siseo).
        when (type) {
            EqBandType.NOTCH -> {
                val alpha = sinW / (2.0 * safeQ)
                val a0 = 1.0 + alpha
                out[0] = 1.0 / a0
                out[1] = (-2.0 * cosW) / a0
                out[2] = 1.0 / a0
                out[3] = (-2.0 * cosW) / a0
                out[4] = (1.0 - alpha) / a0
                return true
            }
            EqBandType.LOW_PASS -> {
                val alpha = sinW / (2.0 * safeQ)
                val a0 = 1.0 + alpha
                out[0] = ((1.0 - cosW) / 2.0) / a0
                out[1] = (1.0 - cosW) / a0
                out[2] = ((1.0 - cosW) / 2.0) / a0
                out[3] = (-2.0 * cosW) / a0
                out[4] = (1.0 - alpha) / a0
                return true
            }
            EqBandType.HIGH_PASS -> {
                val alpha = sinW / (2.0 * safeQ)
                val a0 = 1.0 + alpha
                out[0] = ((1.0 + cosW) / 2.0) / a0
                out[1] = -(1.0 + cosW) / a0
                out[2] = ((1.0 + cosW) / 2.0) / a0
                out[3] = (-2.0 * cosW) / a0
                out[4] = (1.0 - alpha) / a0
                return true
            }
            EqBandType.PEAK -> {
                val alpha = sinW / (2.0 * safeQ)
                val a0 = 1.0 + alpha / a
                out[0] = (1.0 + alpha * a) / a0
                out[1] = (-2.0 * cosW) / a0
                out[2] = (1.0 - alpha * a) / a0
                out[3] = (-2.0 * cosW) / a0
                out[4] = (1.0 - alpha / a) / a0
            }
            EqBandType.LOW_SHELF -> {
                val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / SHELF_SLOPE - 1.0) + 2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
                out[0] = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha) / a0
                out[1] = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0
                out[2] = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0
                out[3] = -2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0
                out[4] = ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0
            }
            EqBandType.HIGH_SHELF -> {
                val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / SHELF_SLOPE - 1.0) + 2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                val a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
                out[0] = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha) / a0
                out[1] = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW) / a0
                out[2] = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0
                out[3] = 2.0 * ((a - 1.0) - (a + 1.0) * cosW) / a0
                out[4] = ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0
            }
        }
        // Si algún coeficiente no es finito, el filtro se declara inactivo en vez de
        // propagar NaN a toda la cadena (un NaN en un biquad enmudece para siempre).
        if (!out[0].isFinite() || !out[1].isFinite() || !out[2].isFinite() ||
            !out[3].isFinite() || !out[4].isFinite()
        ) {
            out[0] = 1.0
            out[1] = 0.0
            out[2] = 0.0
            out[3] = 0.0
            out[4] = 0.0
            return false
        }
        return true
    }

    /** Campana (peaking EQ) de banda constante. */
    fun peaking(sampleRate: Double, freqHz: Double, q: Double, gainDb: Double): BiquadCoeffs =
        one(EqBandType.PEAK, sampleRate, freqHz, q, gainDb)

    /** Shelf de graves. */
    fun lowShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadCoeffs =
        one(EqBandType.LOW_SHELF, sampleRate, freqHz, 0.71, gainDb)

    /** Shelf de agudos. */
    fun highShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadCoeffs =
        one(EqBandType.HIGH_SHELF, sampleRate, freqHz, 0.71, gainDb)

    private fun one(
        type: EqBandType,
        sampleRate: Double,
        freqHz: Double,
        q: Double,
        gainDb: Double,
    ): BiquadCoeffs {
        val out = DoubleArray(5)
        return if (designInto(type, sampleRate, freqHz, q, gainDb, out)) {
            BiquadCoeffs(out[0], out[1], out[2], out[3], out[4])
        } else {
            BiquadCoeffs.BYPASS
        }
    }

    /** Coeficientes de la banda para un sample rate dado. */
    fun forBand(band: EqBand, sampleRate: Double): BiquadCoeffs =
        one(band.type, sampleRate, band.freqHz.toDouble(), band.q.toDouble(), band.gainDb.toDouble())

    /**
     * Respuesta en magnitud (dB) del biquad a [freqHz], evaluando H(e^{jw}) sobre el
     * círculo unitario. Analítica, sin FFT: sirve para dibujar la curva exacta que
     * está sonando, no una aproximación.
     */
    fun magnitudeDb(coeffs: BiquadCoeffs, freqHz: Double, sampleRate: Double): Double {
        if (coeffs.isBypass) return 0.0
        val fs = safeFs(sampleRate)
        val w = 2.0 * PI * freqHz.coerceIn(0.0, fs / 2.0) / fs
        val cos1 = cos(w)
        val cos2 = cos(2.0 * w)
        val sin1 = sin(w)
        val sin2 = sin(2.0 * w)
        val numRe = coeffs.b0 + coeffs.b1 * cos1 + coeffs.b2 * cos2
        val numIm = -(coeffs.b1 * sin1 + coeffs.b2 * sin2)
        val denRe = 1.0 + coeffs.a1 * cos1 + coeffs.a2 * cos2
        val denIm = -(coeffs.a1 * sin1 + coeffs.a2 * sin2)
        val num = hypot(numRe, numIm)
        val den = hypot(denRe, denIm)
        if (den < 1e-12) return 0.0
        return 20.0 * log10((num / den).coerceAtLeast(1e-9))
    }

    /** Respuesta del ecualizador completo (preamp incluido) en dB. */
    fun responseDb(settings: EqSettings, freqHz: Double, sampleRate: Double): Double {
        if (!settings.isProcessing) return 0.0
        var db = settings.effectivePreampDb.toDouble()
        for (band in settings.bands) {
            if (abs(band.gainDb) < 0.05f) continue
            db += magnitudeDb(forBand(band, sampleRate), freqHz, sampleRate)
        }
        return db
    }

    /**
     * Pico de la curva que forman las bandas (sin preamp). Es lo que usa el headroom
     * automático: mover un slider de +6 dB no sube el nivel global, lo baja el preamp.
     * Se barre el espectro en pasos del 2 % (~250 puntos), con los biquads reales.
     */
    fun bandsPeakDb(bands: List<EqBand>, sampleRate: Double): Double {
        val contributing = bands.filter {
            it.isGainBand && it.gainDb.isFinite() && abs(it.gainDb) >= 0.05f
        }
        if (contributing.isEmpty()) return 0.0
        var peak = -120.0
        var f = MIN_FREQ
        val top = minOf(20_000.0, safeFs(sampleRate) * 0.49)
        // Rejilla logarítmica fina (pasos del 1 %, ~700 puntos de 10 Hz a 20 kHz): el pico
        // de una campana estrecha no se puede pasar por alto entre dos muestras.
        while (f < top) {
            var db = 0.0
            for (band in contributing) {
                db += magnitudeDb(forBand(band, sampleRate), f, sampleRate)
            }
            if (db > peak) peak = db
            f *= 1.01
        }
        return peak
    }

    /** Nivel máximo (dB) de la curva completa (preamp incluido). */
    fun peakResponseDb(settings: EqSettings, sampleRate: Double): Double {
        if (!settings.isProcessing) return 0.0
        return settings.effectivePreampDb + bandsPeakDb(settings.bands, sampleRate)
    }

    private fun safeFs(sampleRate: Double): Double =
        if (sampleRate.isFinite() && sampleRate > 8_000.0) sampleRate else 48_000.0

    private fun clampFreq(freqHz: Double, sampleRate: Double): Double =
        freqHz.coerceIn(MIN_FREQ, sampleRate * 0.49)
}
