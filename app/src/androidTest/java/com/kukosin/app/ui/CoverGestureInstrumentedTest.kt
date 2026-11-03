package com.kukosin.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverGestureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun upwardDragContinuesWhenLyricsOverlayAppears() {
        var overlayVisible by mutableStateOf(false)
        var startedAxis: CoverGestureAxis? = null
        var finishedAxis: CoverGestureAxis? = null
        var verticalDistance = 0f
        var cancelled = false
        var minimizeStoleGesture = false

        compose.setContent {
            val gesture = rememberCoverGestureModifier(
                enabled = true,
                hitBounds = Rect(0f, 0f, 10000f, 10000f),
                lyricsProgress = if (overlayVisible) 0.15f else 0f,
                controllerKey = null,
                trackKey = "song",
                onStart = { startedAxis = it },
                onDrag = { axis, _, dy ->
                    if (axis == CoverGestureAxis.LYRICS) {
                        verticalDistance += dy
                        overlayVisible = true
                    }
                },
                onEnd = { axis, _, _ -> finishedAxis = axis },
                onCancel = { cancelled = true },
            )
            Box(
                Modifier.size(300.dp)
                    .then(gesture)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, _ ->
                            minimizeStoleGesture = true
                            change.consume()
                        }
                    }
                    .testTag("player"),
            ) {
                Box(Modifier.fillMaxSize().clickable { })
                if (overlayVisible) {
                    // Simula el scroll del panel que se monta a mitad del arrastre.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures { change, _ -> change.consume() }
                    })
                }
            }
        }

        compose.onNodeWithTag("player").performTouchInput {
            down(center)
            moveBy(Offset(0f, -50f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player").performTouchInput {
            moveBy(Offset(0f, -80f))
            up()
        }
        compose.runOnIdle {
            assertTrue(overlayVisible)
            assertEquals(CoverGestureAxis.LYRICS, startedAxis)
            assertEquals(CoverGestureAxis.LYRICS, finishedAxis)
            assertTrue(verticalDistance < -80f)
            assertFalse(cancelled)
            assertFalse(minimizeStoleGesture)
        }
    }

    @Test
    fun tapStillReachesCoverAndHorizontalSwipeRemainsTrackGesture() {
        var taps = 0
        var startedAxis: CoverGestureAxis? = null
        var finishedAxis: CoverGestureAxis? = null
        compose.setContent {
            val gesture = rememberCoverGestureModifier(
                enabled = true,
                hitBounds = Rect(0f, 0f, 10000f, 10000f),
                lyricsProgress = 0f,
                controllerKey = null,
                trackKey = "song",
                onStart = { startedAxis = it },
                onDrag = { _, _, _ -> },
                onEnd = { axis, _, _ -> finishedAxis = axis },
                onCancel = { },
            )
            Box(Modifier.size(300.dp).then(gesture).testTag("player")) {
                Box(Modifier.fillMaxSize().clickable { taps++ })
            }
        }
        compose.onNodeWithTag("player").performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithTag("player").performTouchInput {
            down(center)
            moveBy(Offset(-100f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(CoverGestureAxis.TRACK, startedAxis)
            assertEquals(CoverGestureAxis.TRACK, finishedAxis)
        }
    }
}
