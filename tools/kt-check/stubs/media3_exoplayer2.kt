@file:Suppress("unused", "UNUSED_PARAMETER")
/** Stub minimal de `androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration`. */
package androidx.media3.exoplayer

class ExoPlayer {
    class PreloadConfiguration(val targetPreloadDurationUs: Long) {
        companion object {
            val DEFAULT = PreloadConfiguration(Long.MIN_VALUE)
        }
    }

    fun setPreloadConfiguration(configuration: PreloadConfiguration) = Unit
}
