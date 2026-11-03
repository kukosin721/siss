package com.kukosin.app.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

internal enum class CoverGestureAxis { TRACK, LYRICS, MINIMIZE }

/**
 * Decide el eje una sola vez después de superar el slop del sistema.
 *
 * El eje vertical lleva un pequeño sesgo: en la portada el gesto natural para las
 * letras es un arrastre hacia arriba, y sin ese margen un swipe ligeramente
 * diagonal se interpretaba como cambio de pista y las letras no se abrían.
 */
internal fun classifyCoverGesture(dx: Float, dy: Float, lyricsProgress: Float, touchSlop: Float): CoverGestureAxis? {
    if (maxOf(abs(dx), abs(dy)) <= touchSlop) return null
    val vertical = abs(dy) * 1.15f
    return when {
        abs(dx) > vertical -> CoverGestureAxis.TRACK
        lyricsProgress > 0.01f || dy < 0f -> CoverGestureAxis.LYRICS
        else -> CoverGestureAxis.MINIMIZE
    }
}

/** La portada conserva un margen táctil incluso al estar reducida o en pausa. */
internal fun expandedCoverHitBounds(rect: Rect, paddingPx: Float): Rect {
    if (rect.width <= 0f || rect.height <= 0f) return Rect.Zero
    val padding = paddingPx.coerceAtLeast(0f)
    return Rect(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding)
}

/**
 * Un fling decidido gana; un arrastre moderado también abre las letras. El umbral
 * es bajo a propósito: con el rango de 200 dp, 28 % son unos 56 dp de recorrido.
 */
internal fun shouldOpenLyrics(progress: Float, velocityY: Float): Boolean =
    velocityY < -420f || (progress > 0.28f && velocityY < 520f)
