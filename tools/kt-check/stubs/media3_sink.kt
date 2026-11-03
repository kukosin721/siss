@file:Suppress("unused", "UNUSED_PARAMETER")
/** Stubs de tipado del sink de Media3 (no van al APK). Mismo paquete que en Java. */
package androidx.media3.exoplayer.audio

import androidx.media3.common.audio.AudioProcessor

interface AudioSink

open class DefaultAudioSink(context: android.content.Context) : AudioSink {
    open class Builder(private val context: android.content.Context) {
        fun setAudioProcessors(processors: Array<AudioProcessor>) = this
        fun setAudioProcessorChain(chain: DefaultAudioProcessorChain) = this
        fun setEnableFloatOutput(enabled: Boolean) = this
        fun setEnableAudioOutputPlaybackParameters(enabled: Boolean) = this
        fun setAudioOutputProvider(provider: AudioOutputProvider?) = this
        fun build(): AudioSink = DefaultAudioSink(context)
    }

    class DefaultAudioProcessorChain(vararg processors: AudioProcessor) {
        @get:JvmName("processors")
        val audioProcessors: Array<out AudioProcessor> = processors
        fun applyPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) =
            playbackParameters
        fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = skipSilenceEnabled
        fun getMediaDuration(playoutDurationUs: Long) = playoutDurationUs
        fun getSkippedOutputFrameCount() = 0L
    }
}
