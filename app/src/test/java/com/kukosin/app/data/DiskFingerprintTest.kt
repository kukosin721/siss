package com.kukosin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La firma de disco es lo que evita mostrar metadatos de un archivo que ya no es el que
 * está en disco. Si esto se rompe, la caché "miente" con duración/bitrate/carátula.
 */
class DiskFingerprintTest {

    @Test
    fun `misma firma coincide`() {
        val a = DiskFingerprint(size = 12_345_678L, modifiedMs = 1_700_000_000_000L)
        val b = DiskFingerprint(size = 12_345_678L, modifiedMs = 1_700_000_000_000L)
        assertTrue(a.matches(b))
    }

    @Test
    fun `distinto tamano o fecha no coincide`() {
        val base = DiskFingerprint(1000L, 5000L)
        assertFalse(base.matches(DiskFingerprint(1001L, 5000L)))
        assertFalse(base.matches(DiskFingerprint(1000L, 5001L)))
        assertFalse(base.matches(null))
    }

    @Test
    fun `ida y vuelta por texto`() {
        val original = DiskFingerprint(9_876_543_210L, 1_690_000_000_123L)
        val parsed = DiskFingerprint.parse(original.encoded)
        assertTrue(original.matches(parsed))
    }

    @Test
    fun `texto invalido no rompe`() {
        assertNull(DiskFingerprint.parse(null))
        assertNull(DiskFingerprint.parse(""))
        assertNull(DiskFingerprint.parse("abc"))
        assertNull(DiskFingerprint.parse("12"))
        assertNull(DiskFingerprint.parse("12:"))
        assertNull(DiskFingerprint.parse("x:y"))
        assertNull(DiskFingerprint.parse("-1:5"))
        assertNull(DiskFingerprint.parse("1:2:3"))
    }
}
