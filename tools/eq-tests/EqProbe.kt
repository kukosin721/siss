package com.kukosin.app.eq

// Banco de pruebas del ecualizador: compila los archivos REALES de la app
// (Biquad.kt, EqModel.kt, EqEngine.kt) sin Android y mide la respuesta en frecuencia
// excitando el motor con senoidales. Verifica diseño == sonido, headroom automático,
// filtros de corte, bypass bit-exacto y que el suavizado no produzca transitorios.
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (!cond) {
        failures++
        println("    FALLA  $name  $detail")
    }
}

private fun checkClose(name: String, actual: Double, expected: Double, tol: Double) {
    check(
        name,
        abs(actual - expected) <= tol,
        "(obtenido %.3f, esperado %.3f +-%.3f)".format(actual, expected, tol),
    )
}

/** Ganancia (dB) medida: RMS de la salida estable contra un seno de amplitud 1. */
private fun measuredGain(freq: Double, sampleRate: Int, settings: EqSettings): Double {
    val engine = EqEngine()
    engine.requestSettings(settings)
    engine.configure(sampleRate, 1)
    val frames = 8192
    val buf = DoubleArray(frames)
    val w = 2.0 * PI * freq / sampleRate
    for (i in 0 until frames) buf[i] = sin(w * i)
    engine.processInterleaved(buf, frames, 1)
    // Segunda pasada: se mide la parte estable (sin el transitorio inicial).
    for (i in 0 until frames) buf[i] = sin(w * (i + frames))
    engine.processInterleaved(buf, frames, 1)
    val start = frames / 2
    var sum = 0.0
    for (i in start until frames) sum += buf[i] * buf[i]
    val rms = sqrt(sum / (frames - start))
    val peak = rms * sqrt(2.0)
    return 20.0 * log10(peak.coerceAtLeast(1e-12))
}

private fun band(
    freq: Float,
    gain: Float,
    q: Float = 1.41f,
    type: EqBandType = EqBandType.PEAK,
) = EqBand(freqHz = freq, gainDb = gain, q = q, type = type)

fun main() {
    println("Pruebas del ecualizador (codigo real de la app)")
    println("================================================")

    println("\n1. Respuesta de una campana (diseno == sonido)")
    val boost = EqSettings(enabled = true, autoHeadroom = false, bands = listOf(band(1000f, 6f)))
    checkClose("1 kHz con +6 dB", measuredGain(1000.0, 48_000, boost), 6.0, 0.25)
    checkClose("100 Hz sin efecto", measuredGain(100.0, 48_000, boost), 0.0, 0.3)
    checkClose("10 kHz sin efecto", measuredGain(10_000.0, 48_000, boost), 0.0, 0.3)

    println("\n2. Corte de -12 dB")
    val cut = EqSettings(enabled = true, autoHeadroom = false, bands = listOf(band(1000f, -12f)))
    checkClose("1 kHz con -12 dB", measuredGain(1000.0, 48_000, cut), -12.0, 0.3)

    println("\n3. Preamp")
    val pre = EqSettings(
        enabled = true,
        autoHeadroom = false,
        preampDb = -6f,
        bands = listOf(band(1000f, 0f)),
    )
    checkClose("-6 dB de preamp en 500 Hz", measuredGain(500.0, 48_000, pre), -6.0, 0.2)

    println("\n4. Headroom automatico")
    val loud = EqSettings(
        enabled = true,
        autoHeadroom = true,
        bands = listOf(band(60f, 12f), band(1000f, 9f), band(8000f, 6f)),
    )
    val peakDb = BiquadDesign.bandsPeakDb(loud.bands, 48_000.0)
    check("la curva tiene pico real", peakDb > 11.0, "(%.2f dB)".format(peakDb))
    checkClose("effectivePreamp cancela el pico", loud.effectivePreampDb + peakDb, 0.0, 0.35)
    val maxGain = listOf(60.0, 120.0, 1000.0, 4000.0, 8000.0)
        .maxOf { measuredGain(it, 48_000, loud) }
    check("con headroom el motor no supera 0 dB", maxGain <= 0.6, "(maximo %.2f dB)".format(maxGain))

    println("\n5. Notch y filtros de corte")
    val notch = EqSettings(
        enabled = true,
        autoHeadroom = false,
        bands = listOf(band(50f, 0f, q = 8f, type = EqBandType.NOTCH)),
    )
    val atNotch = measuredGain(50.0, 48_000, notch)
    val away = measuredGain(1000.0, 48_000, notch)
    check("el notch atenua 50 Hz por debajo de -20 dB", atNotch < -20.0, "(%.1f dB)".format(atNotch))
    checkClose("1 kHz pasa intacto", away, 0.0, 0.2)

    println("\n6. Estabilidad al mover un slider")
    val engine = EqEngine()
    engine.configure(48_000, 2)
    val frames = 256
    var maxSample = 0.0
    var nan = false
    for (step in 0..200) {
        val gain = -12f + 24f * step / 200f
        engine.requestSettings(
            EqSettings(enabled = true, autoHeadroom = false, bands = listOf(band(1000f, gain))),
        )
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = sin(2.0 * PI * 1000.0 * i / 48_000)
            buf[i * 2] = v
            buf[i * 2 + 1] = v
        }
        engine.processInterleaved(buf, frames, 2)
        for (v in buf) {
            if (v.isNaN() || v.isInfinite()) nan = true
            if (abs(v) > maxSample) maxSample = abs(v)
        }
    }
    check("sin NaN/Inf al mover la ganancia", !nan)
    check("sin desbordes (|x| <= 2)", maxSample < 2.0, "(pico %.2f)".format(maxSample))

    println("\n7. Bypass bit-exacto")
    val bypass = EqEngine()
    bypass.configure(44_100, 2)
    bypass.requestSettings(EqSettings(enabled = true))
    val flat = DoubleArray(64) { (it % 17) / 17.0 - 0.5 }
    val copy = flat.copyOf()
    bypass.processInterleaved(flat, 32, 2)
    var identical = true
    for (i in flat.indices) if (flat[i] != copy[i]) identical = false
    check("curva plana = sin cambios", identical)

    println("\n8. Curva dibujada == curva medida")
    val ui = EqSettings(enabled = true, autoHeadroom = false, bands = listOf(band(3000f, 8f)))
    for (f in listOf(500.0, 3000.0, 12_000.0)) {
        val designed = BiquadDesign.responseDb(ui, f, 48_000.0)
        val measured = measuredGain(f, 48_000, ui)
        checkClose("respuesta en %.0f Hz".format(f), measured, designed, 0.4)
    }

    println("\n================================================")
    if (failures == 0) {
        println("$checks comprobaciones, 0 fallas")
    } else {
        println("$checks comprobaciones, $failures FALLAS")
        kotlin.system.exitProcess(1)
    }
}
