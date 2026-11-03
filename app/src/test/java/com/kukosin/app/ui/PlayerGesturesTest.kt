package com.kukosin.app.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturesTest {
    @Test
    fun ignoresMovementBeforeTouchSlop() {
        assertNull(classifyCoverGesture(dx = 7f, dy = -8f, lyricsProgress = 0f, touchSlop = 10f))
    }

    @Test
    fun locksHorizontalAndVerticalGesturesToTheIntendedAction() {
        assertEquals(CoverGestureAxis.TRACK, classifyCoverGesture(60f, -20f, 0f, 10f))
        assertEquals(CoverGestureAxis.TRACK, classifyCoverGesture(-60f, 20f, 1f, 10f))
        assertEquals(CoverGestureAxis.LYRICS, classifyCoverGesture(10f, -60f, 0f, 10f))
        assertEquals(CoverGestureAxis.MINIMIZE, classifyCoverGesture(10f, 60f, 0f, 10f))
        assertEquals(CoverGestureAxis.LYRICS, classifyCoverGesture(10f, 60f, 1f, 10f))
    }

    @Test
    fun lyricsSettleAccordingToDistanceAndFlingDirection() {
        assertFalse(shouldOpenLyrics(0.2f, 0f))
        assertTrue(shouldOpenLyrics(0.34f, 0f))
        assertTrue(shouldOpenLyrics(0.6f, 0f))
        assertTrue(shouldOpenLyrics(0.1f, -700f))
        assertFalse(shouldOpenLyrics(0.9f, 700f))
    }

    @Test
    fun coverHitAreaHasMarginButDoesNotActivateBeforeMeasurement() {
        assertEquals(Rect.Zero, expandedCoverHitBounds(Rect.Zero, 12f))
        assertEquals(Rect(88f, 188f, 312f, 412f), expandedCoverHitBounds(Rect(100f, 200f, 300f, 400f), 12f))
        assertEquals(Rect(100f, 200f, 300f, 400f), expandedCoverHitBounds(Rect(100f, 200f, 300f, 400f), -5f))
    }
}
