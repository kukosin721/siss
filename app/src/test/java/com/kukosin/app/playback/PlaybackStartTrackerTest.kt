package com.kukosin.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStartTrackerTest {
    @Test
    fun preparingAndPausingDoNotAddPlays() {
        val starts = mutableListOf<String>()
        val tracker = PlaybackStartTracker { starts.add(it) }
        tracker.onTransition("content://music/1", preserveIfSame = false, isPlaying = false)
        assertEquals(emptyList<String>(), starts)

        tracker.onPlayingChanged("content://music/1", isPlaying = true)
        tracker.onPlayingChanged("content://music/1", isPlaying = false)
        tracker.onPlayingChanged("content://music/1", isPlaying = true)
        assertEquals(listOf("content://music/1"), starts)
    }

    @Test
    fun nextAndRepeatCountEachNewEntry() {
        val starts = mutableListOf<String>()
        val tracker = PlaybackStartTracker { starts.add(it) }
        tracker.onTransition("one", preserveIfSame = false, isPlaying = true)
        tracker.onTransition("two", preserveIfSame = false, isPlaying = true)
        tracker.onTransition("two", preserveIfSame = false, isPlaying = true)
        assertEquals(listOf("one", "two", "two"), starts)
    }

    @Test
    fun restoringTheSameItemDoesNotDoubleCount() {
        val starts = mutableListOf<String>()
        val tracker = PlaybackStartTracker { starts.add(it) }
        tracker.onTransition("one", preserveIfSame = false, isPlaying = true)
        tracker.onTransition("one", preserveIfSame = true, isPlaying = false)
        tracker.onPlayingChanged("one", isPlaying = true)
        assertEquals(listOf("one"), starts)

        tracker.onTransition("two", preserveIfSame = true, isPlaying = false)
        tracker.onPlayingChanged("two", isPlaying = true)
        assertEquals(listOf("one", "two"), starts)
    }

    @Test
    fun clearedQueueCanStartTheSameSongAgain() {
        val starts = mutableListOf<String>()
        val tracker = PlaybackStartTracker { starts.add(it) }
        tracker.onTransition("one", preserveIfSame = false, isPlaying = true)
        tracker.onTransition(null, preserveIfSame = false, isPlaying = false)
        tracker.onTransition("one", preserveIfSame = false, isPlaying = false)
        tracker.onPlayingChanged("one", isPlaying = true)
        assertEquals(listOf("one", "one"), starts)
    }
}
