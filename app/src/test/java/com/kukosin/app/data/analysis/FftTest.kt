package com.kukosin.app.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class FftTest {

    private fun magnitude(re: DoubleArray, im: DoubleArray, index: Int): Double =
        hypot(re[index], im[index])

    @Test
    fun pureTonePeaksAtItsBinAndMirrorsAtNMinusK() {
        val n = 1024
        val k = 64
        val re = DoubleArray(n) { sin(2.0 * PI * k * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)

        val peak = (0 until n / 2).maxByOrNull { magnitude(re, im, it) } ?: -1
        assertEquals(k, peak)
        // La FFT real es simétrica: el mismo pico aparece en N-k.
        assertTrue(abs(magnitude(re, im, k) - magnitude(re, im, n - k)) < 1e-6)
    }

    @Test
    fun aConstantSignalOnlyHasDc() {
        val n = 256
        val re = DoubleArray(n) { 1.0 }
        val im = DoubleArray(n)
        Fft.transform(re, im)

        assertEquals(n.toDouble(), magnitude(re, im, 0), 1e-9)
        for (i in 1 until n) {
            assertTrue("bin $i debería ser cero", magnitude(re, im, i) < 1e-9)
        }
    }

    @Test
    fun twoTonesKeepTheirRelativeHeights() {
        // Suma de dos tonos: cada uno tiene que seguir estando en su bin.
        val n = 512
        val k1 = 16
        val k2 = 100
        val re = DoubleArray(n) { sin(2.0 * PI * k1 * it / n) + 0.5 * sin(2.0 * PI * k2 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)

        assertTrue(magnitude(re, im, k1) > magnitude(re, im, k2))
        assertTrue(magnitude(re, im, k2) > magnitude(re, im, 40))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPowerOfTwoSizesAreRejected() {
        Fft.transform(DoubleArray(1000), DoubleArray(1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedSizesAreRejected() {
        Fft.transform(DoubleArray(64), DoubleArray(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSingleSampleIsRejected() {
        Fft.transform(DoubleArray(1), DoubleArray(1))
    }
}
