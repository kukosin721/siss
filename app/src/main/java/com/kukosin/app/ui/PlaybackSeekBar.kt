package com.kukosin.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.data.formatTime
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink

/**
 * Barra de avance del mini reproductor.
 *
 * El mini reproductor mostraba una barra de progreso que no se podía tocar: para
 * moverte dentro de la canción había que abrir el reproductor grande. Ahora la
 * barra se toca (salto directo) y se arrastra (scrubbing), y mientras arrastrás
 * aparece una píldora dorada con el tiempo exacto al que vas.
 *
 * El progreso que entra se interpola con un `tween` lineal, así se mueve suave
 * entre las muestras del reproductor (que llegan cada ~400 ms) en vez de a saltos.
 */
@Composable
internal fun CompactSeekBar(
    progress: Float,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // La barra sigue al reloj con una interpolación corta: antes eran 420 ms lineales, que
    // con posiciones que llegan cada ~250–400 ms dejaban la barra siempre "persiguiendo" al
    // reloj. Con una curva suave y más corta el movimiento se lee como continuo y, cuando el
    // usuario cambia de pista, la barra llega antes.
    val smooth by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = Motion.quick(MOTION_MS),
        label = "miniProgress",
    )

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }

    val shown = if (dragging) dragFraction else smooth

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val fraction = dragFraction
                        dragging = false
                        onSeek(fraction)
                    },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val midY = size.height / 2f
            val trackHeight = 3.dp.toPx()
            val radius = trackHeight / 2f
            drawLine(color = Ink, start = Offset(0f, midY), end = Offset(size.width, midY), strokeWidth = trackHeight)
            if (shown > 0f) {
                drawLine(
                    color = Gold,
                    start = Offset(0f, midY),
                    end = Offset(size.width * shown, midY),
                    strokeWidth = trackHeight,
                )
            }
            val thumbX = (size.width * shown).coerceIn(radius, size.width - radius)
            val thumbRadius = if (dragging) 6.dp.toPx() else 4.5.dp.toPx()
            if (dragging) {
                drawCircle(color = Gold.copy(alpha = 0.25f), radius = thumbRadius * 2.4f, center = Offset(thumbX, midY))
            }
            drawCircle(color = Gold, radius = thumbRadius, center = Offset(thumbX, midY))
        }

        if (dragging && durationMs > 0L) {
            val density = LocalDensity.current
            val halfPill = with(density) { 22.dp.toPx() }
            val pillX = with(density) {
                (widthPx * shown - halfPill).coerceIn(0f, (widthPx - halfPill * 2f).coerceAtLeast(0f)).toDp()
            }
            Surface(
                color = Gold,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = pillX),
            ) {
                Text(
                    text = formatTime((durationMs * shown).toLong()),
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Duración del seguimiento de la barra. Corta a propósito: la posición del reproductor se
 * refresca varias veces por segundo, así que una animación más larga nunca alcanza el valor
 * real y la barra se ve arrastrada.
 */
private const val MOTION_MS = 180
