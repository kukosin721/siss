package com.kukosin.app

import com.kukosin.app.eq.BiquadDesign
import com.kukosin.app.eq.DefaultEqBands
import com.kukosin.app.eq.EqBand
import com.kukosin.app.eq.EqBandType
import com.kukosin.app.eq.EqEngine
import com.kukosin.app.eq.EqPresets
import com.kukosin.app.eq.EqProfile
import com.kukosin.app.eq.EqSettings
import com.kukosin.app.eq.applyingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Verifica la matemática del ecualizador contra los valores del Audio EQ Cookbook.
 * Si estos tests pasan, la curva que dibuja la UI es la que realmente suena.
 */
class EqFilterTest {

    private val fs = 48_000.0

    @Test
    fun peakingAtCenterHitsExactlyTheRequestedGain() {
        val coeffs = BiquadDesign.peaking(fs, 1_000.0, 1.0, 6.0)
        val atCenter = BiquadDesign.magnitudeDb(coeffs, 1_000.0, fs)
        assertEquals(6.0, atCenter, 0.05)
    }

    @Test
    fun peakingIsSymmetricForCuts() {
        val coeffs = BiquadDesign.peaking(fs, 500.0, 1.0, -9.0)
        assertEquals(-9.0, BiquadDesign.magnitudeDb(coeffs, 500.0, fs), 0.05)
    }

    @Test
    fun peakingFarFromCenterIsTransparent() {
        val coeffs = BiquadDesign.peaking(fs, 1_000.0, 1.4, 12.0)
        // Cuatro octavas abajo y arriba de una campana Q=1.4: menos de 0.5 dB.
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 62.5, fs)) < 0.5)
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 16_000.0, fs)) < 0.5)
    }

    @Test
    fun zeroGainIsAnExactBypass() {
        val coeffs = BiquadDesign.peaking(fs, 1_000.0, 1.0, 0.0)
        assertTrue(coeffs.isBypass)
        assertEquals(0.0, BiquadDesign.magnitudeDb(coeffs, 1_000.0, fs), 1e-9)
    }

    @Test
    fun lowShelfBoostsBelowItsCornerOnly() {
        val coeffs = BiquadDesign.lowShelf(fs, 100.0, 6.0)
        // Bien por debajo de la esquina: la ganancia tiende a +6 dB.
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 20.0, fs) - 6.0) < 2.0)
        // Bien por encima: 0 dB.
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 5_000.0, fs)) < 0.5)
    }

    @Test
    fun highShelfCutsAboveItsCornerOnly() {
        val coeffs = BiquadDesign.highShelf(fs, 8_000.0, -6.0)
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 18_000.0, fs) + 6.0) < 2.0)
        assertTrue(abs(BiquadDesign.magnitudeDb(coeffs, 200.0, fs)) < 0.5)
    }

    @Test
    fun flatPresetHasNoEffectOnTheCurve() {
        val settings = EqSettings(bands = DefaultEqBands)
        assertFalse(settings.isProcessing)
        assertEquals(0.0, BiquadDesign.responseDb(settings, 60.0, fs), 1e-9)
        assertEquals(0.0, BiquadDesign.responseDb(settings, 1_000.0, fs), 1e-9)
    }

    @Test
    fun autoHeadroomCompensatesTheRealCurvePeak() {
        val preset = EqPresets.byName("Bass")!!
        val settings = EqSettings(enabled = true, bands = DefaultEqBands).applyingPreset(preset)
        assertEquals(6f, settings.maxBoostDb, 0.001f)
        // El pico de la curva es mayor que el refuerzo de una sola banda: las campanas
        // vecinas se suman. El headroom sigue al pico real, no al slider.
        assertTrue(settings.curvePeakDb > settings.maxBoostDb)
        assertEquals(-settings.curvePeakDb, settings.effectivePreampDb, 0.001f)
        // Sin headroom automático el preamp queda en cero.
        assertEquals(0f, settings.copy(autoHeadroom = false).effectivePreampDb, 0.001f)
    }

    @Test
    fun equalizerNeverExceedsZeroDbWithAutoHeadroom() {
        val settings = EqSettings(
            enabled = true,
            bands = DefaultEqBands.map { it.copy(gainDb = 6f) },
        )
        val peak = BiquadDesign.peakResponseDb(settings, fs)
        assertTrue("pico de la curva = $peak dB", peak <= 0.6)
    }

    @Test
    fun engineIsBitPerfectBypassWhenDisabledOrFlat() {
        val engine = EqEngine()
        engine.configure(48_000, 2)
        engine.requestSettings(EqSettings(enabled = false, bands = DefaultEqBands))
        engine.processInterleaved(DoubleArray(128) , 64, 2)
        assertTrue(engine.isBypass)

        engine.requestSettings(
            EqSettings(enabled = true, bands = DefaultEqBands.map { it.copy(gainDb = 0f) }),
        )
        engine.processInterleaved(DoubleArray(128), 64, 2)
        assertTrue(engine.isBypass)
    }

    @Test
    fun engineAppliesTheRequestedBoostAndStaysFinite() {
        val engine = EqEngine()
        engine.configure(48_000, 2)
        val bands = DefaultEqBands.mapIndexed { i, band ->
            if (i == 5) band.copy(gainDb = 12f) else band.copy(gainDb = 0f)
        }
        engine.requestSettings(
            EqSettings(enabled = true, autoHeadroom = false, bands = bands),
        )

        val frames = 512
        val buf = DoubleArray(frames * 2)
        var peak = 0.0
        val step = 2.0 * PI * 1_000.0 / 48_000.0
        var phase = 0.0
        // 60 bloques ≈ 0.6 s: el suavizado ya se estabilizó.
        repeat(60) {
            for (i in 0 until frames) {
                val v = 0.1 * sin(phase)
                phase += step
                buf[i * 2] = v
                buf[i * 2 + 1] = v
            }
            peak = engine.processInterleaved(buf, frames, 2)
            for (v in buf) assertTrue("muestra no finita", v.isFinite())
        }
        // +12 dB sobre 0.1 = 0.4 (con ±2 % por el redondeo de la banda).
        assertEquals(0.4, peak, 0.01)
    }

    @Test
    fun engineTamesClippingWhenHeadroomIsAutomatic() {
        val engine = EqEngine()
        engine.configure(48_000, 2)
        val loud = EqSettings(
            enabled = true,
            autoHeadroom = true,
            bands = DefaultEqBands.map { it.copy(gainDb = 9f) },
        )
        engine.requestSettings(loud)
        val frames = 256
        val buf = DoubleArray(frames * 2)
        var peak = 0.0
        repeat(80) {
            for (i in 0 until frames) {
                buf[i * 2] = 0.9
                buf[i * 2 + 1] = 0.9
            }
            peak = engine.processInterleaved(buf, frames, 2)
        }
        assertTrue("pico $peak > 1.0 con headroom automático", peak <= 1.0)
    }

    @Test
    fun defaultBandsAreIsoOctavesWithShelvesAtTheEnds() {
        assertEquals(10, DefaultEqBands.size)
        assertEquals(EqBandType.LOW_SHELF, DefaultEqBands.first().type)
        assertEquals(EqBandType.HIGH_SHELF, DefaultEqBands.last().type)
        assertEquals(31f, DefaultEqBands.first().freqHz, 0.01f)
        assertEquals(16_000f, DefaultEqBands.last().freqHz, 0.01f)
        assertTrue(DefaultEqBands.all { it.gainDb == 0f })
    }

    @Test
    fun changingAGainMarksThePresetAsCustom() {
        val settings = EqSettings(bands = DefaultEqBands).withBandGain(3, 4f)
        assertEquals(EqSettings.CUSTOM_PRESET, settings.presetName)
        assertEquals(4f, settings.bands[3].gainDb, 0.001f)
        // Las demás bandas no se tocan.
        assertEquals(0f, settings.bands[2].gainDb, 0.001f)
    }

    @Test
    fun bandGainIsClampedToTheAllowedRange() {
        val settings = EqSettings(bands = DefaultEqBands).withBandGain(0, 40f)
        assertEquals(EqSettings.MAX_GAIN_DB, settings.bands[0].gainDb, 0.001f)
        val low = EqSettings(bands = listOf(EqBand(100f, 0f))).withBandGain(0, -40f)
        assertEquals(-EqSettings.MAX_GAIN_DB, low.bands[0].gainDb, 0.001f)
    }
    @Test
    fun changingTheBandTypeAlsoFixesItsQ() {
        val settings = EqSettings(enabled = true, bands = DefaultEqBands)
        val shelf = settings.withBandType(4, EqBandType.HIGH_SHELF)
        assertEquals(EqBandType.HIGH_SHELF, shelf.bands[4].type)
        assertEquals(0.71f, shelf.bands[4].q, 0.001f)
        assertEquals(EqSettings.CUSTOM_PRESET, shelf.presetName)

        val peak = shelf.withBandType(4, EqBandType.PEAK)
        assertEquals(EqBandType.PEAK, peak.bands[4].type)
        assertEquals(1.41f, peak.bands[4].q, 0.001f)
        // Cambiar el tipo no toca la ganancia de la banda.
        assertEquals(0f, peak.bands[4].gainDb, 0.001f)
    }

    @Test
    fun profileDependsOnTheOutputType() {
        assertEquals(EqProfile.BUILTIN, EqProfile.forDevice(isBuiltin = true, isUsbOrDac = false))
        assertEquals(EqProfile.USB, EqProfile.forDevice(isBuiltin = false, isUsbOrDac = true))
        // Un altavoz conectado por USB sigue siendo USB: manda el camino, no el parlante.
        assertEquals(EqProfile.USB, EqProfile.forDevice(isBuiltin = true, isUsbOrDac = true))
        assertEquals(EqProfile.WIRED, EqProfile.forDevice(isBuiltin = false, isUsbOrDac = false))
    }

    @Test
    fun aShelfBandStillCompensatesInTheCurve() {
        val settings = EqSettings(
            enabled = true,
            bands = DefaultEqBands.mapIndexed { i, band ->
                if (i == 0) band.copy(type = EqBandType.LOW_SHELF, q = 0.71f, gainDb = 5f) else band
            },
        )
        // El pico real de la curva tiene que reflejar el shelf.
        assertTrue(settings.curvePeakDb >= 4.0f)
        assertEquals(-settings.curvePeakDb, settings.effectivePreampDb, 0.001f)
    }
}
