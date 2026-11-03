package com.kukosin.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Reconoce los gestos en el contenedor estable del reproductor, no en el slot que se
 * oculta al entrar en letras. Initial permite reservar el arrastre antes de que un
 * clickable, el scroll de letras o el minimizador lo consuman. Un toque sin arrastre
 * no se consume y sigue llegando al clickable de la carátula.
 */
@Composable
internal fun rememberCoverGestureModifier(
    enabled: Boolean,
    hitBounds: Rect,
    lyricsProgress: Float,
    controllerKey: Any?,
    trackKey: Any?,
    onStart: (CoverGestureAxis) -> Unit,
    onDrag: (CoverGestureAxis, Float, Float) -> Unit,
    onEnd: (CoverGestureAxis, Float, Float) -> Unit,
    onCancel: (CoverGestureAxis) -> Unit,
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentBounds by rememberUpdatedState(hitBounds)
    val currentLyricsProgress by rememberUpdatedState(lyricsProgress)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnCancel by rememberUpdatedState(onCancel)

    return Modifier.pointerInput(controllerKey, trackKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val bounds = currentBounds
            if (!currentEnabled || bounds.width <= 0f || bounds.height <= 0f || !bounds.contains(down.position)) {
                return@awaitEachGesture
            }

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            var axis: CoverGestureAxis? = null
            var totalX = 0f
            var totalY = 0f
            var finished = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        val activeAxis = axis
                        if (activeAxis != null) {
                            val velocity = velocityTracker.calculateVelocity()
                            currentOnEnd(activeAxis, velocity.x, velocity.y)
                            change.consume()
                        }
                        finished = true
                        break
                    }

                    var dx = change.position.x - change.previousPosition.x
                    var dy = change.position.y - change.previousPosition.y
                    if (axis == null) {
                        totalX += dx
                        totalY += dy
                        val slop = viewConfiguration.touchSlop
                        val selected = classifyCoverGesture(totalX, totalY, currentLyricsProgress, slop) ?: continue
                        axis = selected
                        dx = if (totalX > 0f) (totalX - slop).coerceAtLeast(0f) else (totalX + slop).coerceAtMost(0f)
                        dy = if (totalY > 0f) (totalY - slop).coerceAtLeast(0f) else (totalY + slop).coerceAtMost(0f)
                        currentOnStart(selected)
                    }
                    val activeAxis = axis ?: continue
                    currentOnDrag(activeAxis, dx, dy)
                    change.consume()
                }
            } finally {
                if (!finished) axis?.let { currentOnCancel(it) }
            }
        }
    }
}
