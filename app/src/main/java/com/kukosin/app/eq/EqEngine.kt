package com.kukosin.app.eq

import kotlin.math.abs
import kotlin.math.pow

/**
 * Motor de ecualización.
 *
 * Diseño pensado para audio en tiempo real:
 *
 * - **Cascada de biquads en forma directa II traspuesta** (TDF-II). Es la forma con
 *   mejor relación señal/ruido en coma flotante cuando los polos están cerca del
 *   círculo unitario (frecuencias bajas), que es justo el caso crítico de un EQ.
 * - **Cálculo en Double**, salida al formato del decoder al final. Sin cuantización
 *   intermedia entre bandas.
 * - **Suavizado de parámetros** por bloques de [SUB_BLOCK] frames con constante de
 *   tiempo ~15 ms: mover un slider no produce "zipper noise" ni chasquidos, porque
 *   los coeficientes se actualizan a ritmo de audio (750 veces por segundo a 48 kHz)
 *   y no de golpe.
 * - **Dry/Wet** para que encender/apagar el EQ no haga "pop": se cruza la señal seca
 *   con la procesada en ~15 ms.
 * - **Bypass real y bit-exacto**: mientras el cruce está en seco, [isBypass] es true y
 *   el procesador no toca la muestra (copia de bytes idéntica).
 * - **Protección de denormales** en los estados del filtro.
 *
 * No es thread-safe con respecto a [processInterleaved]: solo el hilo de audio lo
 * llama. [requestSettings] se puede llamar desde cualquier hilo (volatile).
 */
internal class EqEngine {

    /** Estado de una biquad: coeficientes + memoria por canal. */
    private class Stage(val channels: Int) {
        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a1 = 0.0
        var a2 = 0.0
        var active = false
        var activeTarget = false
        var moving = false
        val z1 = DoubleArray(channels)
        val z2 = DoubleArray(channels)
    }

    @Volatile
    private var requested: EqSettings = EqSettings()

    private var sampleRate = 48_000.0
    private var channels = 2
    private var stages: Array<Stage> = emptyArray()
    private var bandFreq = DoubleArray(0)
    private var bandQ = DoubleArray(0)
    private var bandGainTarget = DoubleArray(0)
    private var bandTypes: Array<EqBandType> = emptyArray()
    private var activeStages = IntArray(0)
    private var activeCount = 0

    private var preampTargetDb = 0.0
    private var preampCurrentDb = 0.0
    private var preampGain = 1.0

    private var wet = 0.0
    private var wetTarget = 0.0

    private var configured = false
    private val designScratch = DoubleArray(5)

    /**
     * Coeficientes objetivo y coeficientes suavizados.
     *
     * Suavizar la GANANCIA y rediseñar el biquad en cada sub-bloque hace que los polos
     * se muevan por el plano z mientras suena: eso se escucha como un "swish" al mover
     * un slider de Q o de frecuencia. Suavizar directamente los CINCO COEFICIENTES de
     * cada biquad es la técnica de los ecualizadores de estudio: el filtro se
     * interpola entre dos estados estables y nunca pierde estabilidad mientras la
     * ganancia va de -12 a +12 dB.
     */
    private var targetCoef = DoubleArray(0)
    private var smoothCoef = DoubleArray(0)

    /** ¿La cadena está en bypass total (seco puro, sin ningún proceso)? */
    val isBypass: Boolean
        get() = wet == 0.0 && wetTarget == 0.0

    /** Prepara el motor para un formato de audio nuevo. Llamar desde el hilo de audio. */
    fun configure(sampleRateHz: Int, channelCount: Int) {
        val fs = if (sampleRateHz > 8_000) sampleRateHz.toDouble() else 48_000.0
        val ch = channelCount.coerceIn(1, 8)
        if (configured && fs == sampleRate && ch == channels) return
        sampleRate = fs
        channels = ch
        val s = requested
        val count = s.bands.size.coerceAtMost(MAX_BANDS)
        stages = Array(count) { Stage(ch) }
        bandFreq = DoubleArray(count)
        bandQ = DoubleArray(count)
        bandGainTarget = DoubleArray(count)
        bandTypes = Array(count) { i -> s.bands[i].type }
        activeStages = IntArray(count)
        activeCount = 0
        targetCoef = DoubleArray(count * 5)
        smoothCoef = DoubleArray(count * 5)
        for (i in 0 until count) {
            bandFreq[i] = s.bands[i].freqHz.toDouble()
            bandQ[i] = s.bands[i].q.toDouble()
            bandGainTarget[i] = 0.0
        }
        preampCurrentDb = if (s.isProcessing) s.effectivePreampDb.toDouble() else 0.0
        preampTargetDb = preampCurrentDb
        preampGain = dbToLinear(preampCurrentDb)
        wet = if (s.isProcessing) 1.0 else 0.0
        wetTarget = wet
        resetStates()
        configured = true
        for (i in stages.indices) {
            bandGainTarget[i] = if (s.isProcessing && s.bands[i].isGainBand) {
                s.bands[i].gainDb.toDouble().coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
            } else {
                0.0
            }
        }
        // Primer suavizado: deja el objetivo calculado y copia los coeficientes sin
        // rampa, así la primera configuración ya suena con la curva exacta.
        smoothAndUpdate()
        smoothCoef = targetCoef.copyOf()
        smoothAndUpdate()
    }

    /** El estado del EQ cambió (cualquier hilo). Se aplica suavemente en el próximo bloque. */
    fun requestSettings(settings: EqSettings) {
        requested = settings
    }

    /** Limpia la memoria de los filtros (seek, cambio de pista, flush). Sin clicks. */
    fun resetStates() {
        for (stage in stages) {
            stage.z1.fill(0.0)
            stage.z2.fill(0.0)
        }
    }

    /**
     * Procesa [buf] (entrelazado, `frames * channels` muestras en el rango -1..1) in place.
     * Devuelve el pico absoluto de la salida (para el vúmetro y la detección de clip).
     */
    fun processInterleaved(buf: DoubleArray, frames: Int, channelCount: Int): Double {
        if (!configured || frames <= 0) return 0.0
        val ch = channelCount.coerceAtMost(channels).coerceAtLeast(1)
        var peak = 0.0
        var frame = 0
        while (frame < frames) {
            val n = (frames - frame).coerceAtMost(SUB_BLOCK)
            smoothAndUpdate()
            val w = wet
            val dry = 1.0 - w
            val g = preampGain
            val running = w > 0.0 && activeCount > 0
            val preampOnly = w > 0.0 && activeCount == 0
            if (!running && !preampOnly) {
                // Seco total: medimos sin tocar nada.
                for (i in 0 until n) {
                    val base = (frame + i) * channelCount
                    for (c in 0 until ch) {
                        val v = buf[base + c]
                        val a = abs(v)
                        if (a > peak) peak = a
                    }
                }
            } else {
                for (i in 0 until n) {
                    val base = (frame + i) * channelCount
                    for (c in 0 until ch) {
                        val x = buf[base + c]
                        var y = x
                        if (running) {
                            for (k in 0 until activeCount) {
                                y = runStage(activeStages[k], y, c)
                            }
                        }
                        y *= g
                        val out = if (w >= 1.0) y else dry * x + w * y
                        buf[base + c] = out
                        val a = abs(out)
                        if (a > peak) peak = a
                    }
                }
            }
            frame += n
        }
        flushDenormals()
        return if (peak.isFinite()) peak else 0.0
    }

    // ------------------------------------------------------------------ interno

    private fun runStage(index: Int, input: Double, channel: Int): Double {
        val s = stages[index]
        // Direct Form II transpuesta.
        val y = s.b0 * input + s.z1[channel]
        s.z1[channel] = s.b1 * input - s.a1 * y + s.z2[channel]
        s.z2[channel] = s.b2 * input - s.a2 * y
        return y
    }

    private fun smoothAndUpdate() {
        val target = requested
        val active = target.isProcessing
        wetTarget = if (active) 1.0 else 0.0
        wet += (wetTarget - wet) * SMOOTH

        val count = bandGainTarget.size
        if (target.bands.size >= count) {
            for (i in 0 until count) {
                val band = target.bands[i]
                bandFreq[i] = band.freqHz.toDouble()
                bandQ[i] = band.q.toDouble()
                bandTypes[i] = band.type
                val gTarget = if (active && band.isGainBand) {
                    band.gainDb.toDouble().coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
                } else {
                    0.0
                }
                bandGainTarget[i] = gTarget
            }
        }

        // Preamp: se suaviza en dB (es un escalar, no hay polos que mover).
        val preampT = if (active) target.effectivePreampDb.toDouble() else 0.0
        preampTargetDb = preampT
        val pDelta = preampTargetDb - preampCurrentDb
        if (abs(pDelta) > 1e-4) {
            preampCurrentDb += pDelta * SMOOTH
        } else {
            preampCurrentDb = preampTargetDb
        }
        preampGain = dbToLinear(preampCurrentDb)

        // 1) Rediseño de los coeficientes OBJETIVO (fuera del lazo de muestras).
        for (i in stages.indices) {
            val activeStage = BiquadDesign.designInto(
                type = bandTypes[i],
                sampleRate = sampleRate,
                freqHz = bandFreq[i],
                q = bandQ[i],
                gainDb = bandGainTarget[i],
                out = designScratch,
            )
            val base = i * 5
            targetCoef[base] = if (activeStage) designScratch[0] else 1.0
            targetCoef[base + 1] = if (activeStage) designScratch[1] else 0.0
            targetCoef[base + 2] = if (activeStage) designScratch[2] else 0.0
            targetCoef[base + 3] = if (activeStage) designScratch[3] else 0.0
            targetCoef[base + 4] = if (activeStage) designScratch[4] else 0.0
            stages[i].activeTarget = activeStage
        }

        // 2) Los coeficientes que suenan se mueven hacia el objetivo.
        for (i in stages.indices) {
            val base = i * 5
            var stageMoving = false
            for (k in 0 until 5) {
                val delta = targetCoef[base + k] - smoothCoef[base + k]
                if (abs(delta) > COEF_EPS) {
                    smoothCoef[base + k] += delta * SMOOTH
                    stageMoving = true
                } else {
                    smoothCoef[base + k] = targetCoef[base + k]
                }
            }
            stages[i].moving = stageMoving
        }
        if (abs(wet - wetTarget) < 1e-3) wet = wetTarget

        // 3) Se publican a las etapas y se recalcula qué etapas están activas.
        //    Una etapa cuenta como activa si su objetivo lo es o si todavía está
        //    viajando hacia/desde el bypass (si no, se cortaría el fade del filtro).
        var n = 0
        for (i in stages.indices) {
            val s = stages[i]
            val base = i * 5
            s.b0 = smoothCoef[base]
            s.b1 = smoothCoef[base + 1]
            s.b2 = smoothCoef[base + 2]
            s.a1 = smoothCoef[base + 3]
            s.a2 = smoothCoef[base + 4]
            val midpoint = abs(s.b0 - 1.0) > COEF_EPS || abs(s.b1) > COEF_EPS ||
                abs(s.b2) > COEF_EPS || abs(s.a1) > COEF_EPS || abs(s.a2) > COEF_EPS
            s.active = s.activeTarget || midpoint || s.moving
            if (s.active) {
                if (n < activeStages.size) activeStages[n] = i
                n++
            }
        }
        activeCount = n
    }

    private fun flushDenormals() {
        for (s in stages) {
            for (c in 0 until channels) {
                if (abs(s.z1[c]) < DENORMAL) s.z1[c] = 0.0
                if (abs(s.z2[c]) < DENORMAL) s.z2[c] = 0.0
            }
        }
    }

    private companion object {
        const val MAX_BANDS = 16
        /** 64 frames ≈ 1.33 ms a 48 kHz: los parámetros se mueven a ritmo de audio. */
        const val SUB_BLOCK = 64
        /** One-pole por sub-bloque → constante de tiempo ≈ 15 ms. */
        const val SMOOTH = 0.35
        const val DENORMAL = 1e-20
        /** Umbral para considerar que dos coeficientes son distintos (≈ -280 dB). */
        const val COEF_EPS = 1e-9
        const val MAX_GAIN_DB = 12.0

        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    }
}
