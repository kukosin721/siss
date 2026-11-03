package com.kukosin.app.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del índice de letras: la normalización es lo que hace que "Corazón" se
 * encuentre escribiendo "corazon" y que buscar dentro de la letra cueste lo mismo que
 * buscar en el título.
 *
 * Kotlin puro: no toca Android (el escaneo de archivos sí, pero la lógica de texto no).
 */
class LyricsIndexTest {

    @Test
    fun `quita acentos y baja a minusculas`() {
        assertEquals("corazon", LyricsIndex.normalize("Corazón"))
        assertEquals("maria jose", LyricsIndex.normalize("María José"))
        assertEquals("nino", LyricsIndex.normalize("niño"))
        assertEquals("uber", LyricsIndex.normalize("über"))
    }

    @Test
    fun `colapsa espacios y saltos de linea`() {
        assertEquals("te quiero", LyricsIndex.normalize("te \n\n   quiero   "))
        assertEquals("", LyricsIndex.normalize("   "))
    }

    @Test
    fun `descarta apostrofes y signos`() {
        assertEquals("dont stop me now", LyricsIndex.normalize("Don't stop me now!!"))
        assertEquals("que hora es", LyricsIndex.normalize("¿Qué hora es?"))
    }

    @Test
    fun `la consulta normalizada coincide con el texto normalizado`() {
        val lyric = LyricsIndex.normalize("Y yo te dije: corazón, no llores más")
        assertTrue(lyric.contains(LyricsIndex.normalize("CORAZÓN")))
        assertTrue(lyric.contains(LyricsIndex.normalize("no llores")))
        assertFalse(lyric.contains(LyricsIndex.normalize("llover")))
    }

    @Test
    fun `normalizar dos veces no cambia el resultado`() {
        val once = LyricsIndex.normalize("Camión Azul — 2ª parte")
        assertEquals(once, LyricsIndex.normalize(once))
    }
}
