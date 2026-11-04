package com.kukosin.app.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.kukosin.app.audio.OboeAudioOutputProvider
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Cadena de audio: decoder → [EqAudioProcessor] (bypass si está apagado) →
 * [OboeAudioOutputProvider] → Oboe → DAC.
 *
 * No hay ramas por "driver": la única salida posible es Oboe (o AudioTrack puro en el
 * modo DISABLED), y el sink nunca convierte a float —eso requantizaría y sacaría la
 * señal del bit-perfect.
 *
 * Lo que se apaga acá y por qué:
 *  - **Float output**: `false`. Un float de por medio es una requantización más
 *    (24 bits → float32 → otra profundidad) que además hace clip al reconstruir.
 *  - **Playback params**: `false`. Cambiar la velocidad por el sink reinicia el stream
 *    y corta el audio; la app no ofrece cambios de velocidad, así que no se necesita.
 *  - **Fundidos y ganancia de Media3**: no se instalan. Los fundidos los hace el motor
 *    nativo, en 64 bits y con la curva de volumen del usuario (esto era, además, lo que
 *    hacía que el volumen "subiera solo" al empezar cada pista).
 */
@UnstableApi
class HiFiRenderersFactory(context: Context) : NextRenderersFactory(context) {

    init {
        val config = AudioSettingsManager.configState.value
        val extensionMode = when (config.decoder) {
            "MediaCodec" -> EXTENSION_RENDERER_MODE_OFF
            "Automatic" -> EXTENSION_RENDERER_MODE_ON
            else -> EXTENSION_RENDERER_MODE_PREFER
        }
        setExtensionRendererMode(extensionMode)
        // El PCM va al DAC con la profundidad con la que salió del decoder.
        setEnableAudioFloatOutput(false)
        // (No se llama a `setEnableAudioTrackPlaybackParams`: está deprecado en Media3
        // 1.11 y su valor por defecto ya es el que necesitamos —sin cambios de velocidad
        // que reinicien el stream.)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val mode = AudioSettingsManager.configState.value.outputMode
        // Dos etapas: primero la ganancia (fundidos entre pistas + ReplayGain, inerte si
        // ambos están apagados) y después el ecualizador. Las dos copian los bytes tal
        // cual cuando no hay nada que hacer, así que la ruta sigue siendo bit-perfect.
        val processors: Array<AudioProcessor> =
            arrayOf(GainAudioProcessor(), EqAudioProcessor(), SpectrumAudioProcessor())
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessors(processors)
        // Modo "Disabled": AudioTrack puro, el mezclador de Android decide.
        // Los demás modos abren el motor de Oboe con su propia política.
        if (mode.usesEngine) {
            builder.setAudioOutputProvider(OboeAudioOutputProvider(mode))
        }
        return builder.build()
    }
}
