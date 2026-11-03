package com.kukosin.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regresión del bug que cortaba la reproducción: `PlaybackService` reconstruye el
 * ExoPlayer cuando cambia [AudioConfig.audioSignature]. Si la firma incluyera los
 * ajustes de la cola o del análisis, tocar cualquiera de esos switches cortaría el
 * audio sin motivo.
 */
class AudioConfigTest {

    @Test
    fun queueAndAnalysisSettingsDoNotChangeTheSignature() {
        val base = AudioConfig()
        val sameAudio = base.copy(
            autoAnalyze = !base.autoAnalyze,
            queueCompactMode = !base.queueCompactMode,
            showQueueBadge = !base.showQueueBadge,
            queueShowHistory = !base.queueShowHistory,
        )
        assertEquals(base.audioSignature, sameAudio.audioSignature)
    }

    @Test
    fun audioSettingsDoChangeTheSignature() {
        val base = AudioConfig()
        assertNotEquals(base.audioSignature, base.copy(outputMode = OutputMode.USB_DAC).audioSignature)
        assertNotEquals(base.audioSignature, base.copy(strictBitPerfect = true).audioSignature)
        assertNotEquals(base.audioSignature, base.copy(bufferMs = 500).audioSignature)
        assertNotEquals(base.audioSignature, base.copy(fades = false).audioSignature)
        assertNotEquals(base.audioSignature, base.copy(decoder = "MediaCodec").audioSignature)
    }

    @Test
    fun defaultsAreSane() {
        val config = AudioConfig()
        assertEquals(OutputMode.AUTO, config.outputMode)
        assertEquals(false, config.strictBitPerfect)
        assertEquals(250, config.bufferMs)
        assertEquals(true, config.fades)
        assertEquals(true, AudioConfig.BUFFER_OPTIONS.contains(config.bufferMs))
    }

    @Test
    fun outputModesDescribeWhatTheyDo() {
        assertEquals(true, OutputMode.AUTO.usesEngine)
        assertEquals(true, OutputMode.AAUDIO_EXCLUSIVE.usesEngine)
        assertEquals(true, OutputMode.DIRECT_DRIVER.usesEngine)
        assertEquals(true, OutputMode.USB_DAC.usesEngine)
        assertEquals(false, OutputMode.DISABLED.usesEngine)

        // El modo automático es el camino por defecto: usa Oboe pero no exige ni
        // exclusividad ni DAC, para que nunca falle.
        assertEquals(false, OutputMode.AUTO.requiresExclusive)
        assertEquals(false, OutputMode.AUTO.requiresDac)
        assertEquals(OutputMode.AUTO, OutputMode.entriesInOrder.first())
        assertEquals(OutputMode.AUTO, OutputMode.fromName(null))

        assertEquals(true, OutputMode.AAUDIO_EXCLUSIVE.requiresExclusive)
        assertEquals(true, OutputMode.USB_DAC.requiresExclusive)
        assertEquals(true, OutputMode.USB_DAC.requiresDac)

        assertEquals(true, OutputMode.USB_DAC.requiresUsb)
        assertEquals(true, OutputMode.USB_DAC.targetsUsb)
        assertEquals(false, OutputMode.AAUDIO_EXCLUSIVE.requiresUsb)
    }
}
