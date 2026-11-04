package com.kukosin.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kukosin.app.playback.Spectrum
import com.kukosin.app.ui.theme.Gold

/**
 * Barras del espectro en vivo, dibujadas sobre la carátula.
 *
 * La animación se dispara por **versión**: [Spectrum] sube un contador cada vez que el
 * procesador de audio publica un análisis nuevo, y acá sólo se recompone cuando ese
 * contador cambia. Con la música pausada el composable queda quieto: no gasta un frame.
 */
@Composable
internal fun SpectrumBars(modifier: Modifier = Modifier) {
    var version by remember { mutableIntStateOf(Spectrum.version) }
    var levels by remember { mutableStateOf(Spectrum.levels()) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            val current = Spectrum.version
            if (current != version) {
                version = current
                levels = Spectrum.levels()
            }
        }
    }

    Canvas(modifier = modifier) {
        val bars = levels.size
        if (bars == 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val gap = size.width * 0.012f
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        // Degradado vertical: el acento arriba, un poco más apagado abajo. Da volumen sin
        // tapar la carátula.
        val brush = Brush.verticalGradient(
            colors = listOf(Gold.copy(alpha = 0.92f), Gold.copy(alpha = 0.35f)),
        )
        for (i in 0 until bars) {
            val level = levels[i].coerceIn(0f, 1f)
            val height = (size.height * level).coerceAtLeast(size.height * 0.05f)
            val left = i * (barWidth + gap)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
        // Reflejo tenue abajo para que las barras no "floten".
        drawRect(
            color = Color.Black.copy(alpha = 0.18f),
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1f),
        )
    }
}
