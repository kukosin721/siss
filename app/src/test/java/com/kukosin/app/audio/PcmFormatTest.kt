package com.kukosin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica el protocolo entero compartido con `native-lib.cpp` y las etiquetas que
 * muestra la UI. Si alguien reordena [PcmFormat] o cambia un código, el motor nativo
 * recibe otro formato: estos tests lo detectan sin necesidad de un dispositivo.
 */
class PcmFormatTest {

    @Test
    fun `los codigos de formato coinciden con el protocolo JNI`() {
        assertEquals(0, PcmFormat.INT16.code)
        assertEquals(1, PcmFormat.INT24.code)
        assertEquals(2, PcmFormat.FLOAT32.code)
        assertEquals(3, PcmFormat.INT32.code)
        // Round-trip de todos los códigos: es lo único que cruza la frontera JNI.
        for (format in PcmFormat.entries) {
            assertEquals(format, PcmFormat.fromCode(format.code))
        }
    }

    @Test
    fun `los codigos de estado coinciden con oboe StreamState`() {
        assertEquals(0, EngineState.UNINITIALIZED.code)
        assertEquals(2, EngineState.OPEN.code)
        assertEquals(4, EngineState.STARTED.code)
        assertEquals(6, EngineState.PAUSED.code)
        assertEquals(8, EngineState.FLUSHED.code)
        assertEquals(13, EngineState.DISCONNECTED.code)
        assertEquals(13, EngineState.fromCode(13).code)
        assertEquals(EngineState.UNKNOWN, EngineState.fromCode(999))
    }

    @Test
    fun `los codigos de error coinciden con oboe Result`() {
        assertEquals(0, NativeResult.OK.code)
        assertEquals(-899, NativeResult.ERROR_DISCONNECTED.code)
        assertEquals(-895, NativeResult.ERROR_INVALID_STATE.code)
        assertEquals(-892, NativeResult.ERROR_UNAVAILABLE.code)
        assertEquals(-883, NativeResult.ERROR_INVALID_RATE.code)
        assertTrue(NativeResult.OK.isOk)
        assertFalse(NativeResult.ERROR_UNAVAILABLE.isOk)
        assertEquals(NativeResult.ERROR_DISCONNECTED, NativeResult.fromCode(-899))
    }

    @Test
    fun `el mapeo desde Media3 cubre los cuatro formatos PCM`() {
        assertEquals(PcmFormat.INT16, PcmFormat.fromMedia3Encoding(androidx.media3.common.C.ENCODING_PCM_16BIT))
        assertEquals(PcmFormat.INT24, PcmFormat.fromMedia3Encoding(androidx.media3.common.C.ENCODING_PCM_24BIT))
        assertEquals(PcmFormat.INT32, PcmFormat.fromMedia3Encoding(androidx.media3.common.C.ENCODING_PCM_32BIT))
        assertEquals(PcmFormat.FLOAT32, PcmFormat.fromMedia3Encoding(androidx.media3.common.C.ENCODING_PCM_FLOAT))
        // Un encoding desconocido nunca debe romper: cae a 16 bits.
        assertEquals(PcmFormat.INT16, PcmFormat.fromMedia3Encoding(-1))
    }

    @Test
    fun `bitPerfect exige exclusivo sin conversion y volumen al cien`() {
        val base = EngineInfo(
            opened = true,
            exclusive = true,
            passthrough = true,
            volumePercent = 100,
        )
        assertTrue(base.bitPerfect)
        assertFalse(base.copy(exclusive = false).bitPerfect)
        assertFalse(base.copy(passthrough = false).bitPerfect)
        assertFalse(base.copy(volumePercent = 99).bitPerfect)
        assertFalse(base.copy(opened = false).bitPerfect)
    }

    @Test
    fun `la etiqueta del stream informa MMAP cuando el camino es sin mezclador`() {
        val shared = EngineInfo(
            opened = true,
            stream = PcmFormat.INT24,
            streamSampleRate = 96_000,
            exclusive = false,
        )
        assertEquals("24-bit · 96.0 kHz · shared", shared.streamLabel)

        val mmap = shared.copy(exclusive = true, mmap = true)
        assertEquals("24-bit · 96.0 kHz · exclusive · MMAP", mmap.streamLabel)
    }

    @Test
    fun `la latencia se informa en milisegundos y menos uno significa desconocida`() {
        assertEquals(-1.0, EngineInfo().latencyMs, 0.0001)
        assertEquals(10.5, EngineInfo(latencyMicros = 10_500).latencyMs, 0.0001)
    }
}
