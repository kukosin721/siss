package com.kukosin.app.playback

/**
 * Cuenta una vez por entrada realmente iniciada: pausar/reanudar no suma otra reproducción.
 * Se puede conservar la entrada al reconstruir el reproductor por un cambio de ajustes.
 */
internal class PlaybackStartTracker(private val onStart: (String) -> Unit) {
    private var currentKey: String? = null
    private var counted = false

    fun onTransition(key: String?, preserveIfSame: Boolean, isPlaying: Boolean) {
        if (!preserveIfSame || key != currentKey) {
            currentKey = key
            counted = false
        }
        if (isPlaying) countOnce()
    }

    fun onPlayingChanged(key: String?, isPlaying: Boolean) {
        if (key != currentKey) {
            currentKey = key
            counted = false
        }
        if (isPlaying) countOnce()
    }

    private fun countOnce() {
        val key = currentKey ?: return
        if (!counted) {
            counted = true
            onStart(key)
        }
    }
}
