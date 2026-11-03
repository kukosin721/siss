// KukosinWaveformSlider.kt — réplica GIF + paleta Kukosin
// Ubicación: app/src/main/java/com/kukosin/app/ui/component/KukosinWaveformSlider.kt

package com.kukosin.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.ui.theme.*
import kotlin.math.pow
import kotlin.random.Random

/**
 * Waveform SeekBar con controles DENTRO de la onda (como tu GIF).
 *
 * - La onda se DESPLAZA bajo aguja Gold fija: progress 0..1 -> contenido -progress*contentW
 * - Drag always starts at the current position (never jumps to 0:00). Slow, precise 0.16x.
 * - Cada canción tiene su onda única (seed = hash de trackKey)
 * - Onda amplia y adaptable + botones centrados en la onda (no en el Box)
 */
@Composable
fun KukosinWaveformSlider(
    progress: Float,
    onSeek: (Float) -> Unit,
    /** Se llama mientras el dedo se mueve (antes de soltar). Opcional. */
    onScrub: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    waveHeight: Dp = 128.dp,
    waveform: List<Float>? = null,
    trackKey: String? = null, // pass track.uri.toString() or "title|artist" -> unique wave per song
    /** La onda todavía se está extrayendo: barras planas y controles abajo. */
    isLoading: Boolean = false,
    isPlaying: Boolean = true,
    enabled: Boolean = true,
    onPlayPause: () -> Unit = {},
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    playedColor: Color = Cream,
    remainingColor: Color = Cream.copy(alpha = 0.22f),
    centerLineColor: Color = Gold,
    durationLabel: String = "",
    positionLabel: String = "",
) {
    val clamped = progress.coerceIn(0f, 1f)
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val isDragging = dragProgress != null
    // displayProgress es lo que se ve mientras arrastras; fuera de drag es clamped
    val displayProgress = dragProgress ?: clamped

    // ── Suavidad: animación fluida de la onda (no stutter cada 250ms) ─────
    // Cuando no arrastras, el progreso se anima con tween 600ms para movimiento sedoso.
    // Al arrastrar, snap instantáneo para seguir el dedo sin lag.
    val animatedProgress by animateFloatAsState(
        targetValue = displayProgress,
        // Mientras se reproduce, la posición se muestrea ~10 veces por segundo: un
        // tween corto y lineal rellena el hueco sin retargetear una animación larga en
        // cada tick (eso era lo que hacía que la onda se sintiera "pesada").
        animationSpec = if (isDragging) snap() else tween(durationMillis = 220, easing = LinearEasing),
        label = "waveProgressSmooth"
    )
    // Para el gesto, usamos el valor objetivo (no el animado) para cálculo preciso
    val progressForGestures = displayProgress
    val animatedProgressForDraw = animatedProgress

    // Fade out/in al pausar/reproducir
    val contentAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.58f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "waveFade"
    )
    val waveScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.96f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "waveScale"
    )
    // Mientras se arrastra la onda los controles se vuelven translúcidos para no
    // tapar la parte de la canción que se está eligiendo.
    val controlsAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.26f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "waveControlsAlpha"
    )

    val bars = remember(waveform, trackKey, isLoading) {
        when {
            // Cargando: todas las barras abajo (planas). Al llegar los datos reales
            // suben con un escalonado suave (ver `waveReveal`).
            isLoading -> List(180) { 0f }
            waveform != null -> when {
                waveform.isEmpty() -> List(180) { 0f }
                waveform.size >= 160 -> waveform
                else -> expandWaveform(waveform, 180)
            }
            else -> {
                val seed = trackKey?.hashCode() ?: 42
                generateKukosinWaveform(180, seed = seed)
            }
        }
    }

    // 0 = plano, 1 = onda completa. Se lee desde el `Canvas`, así que anima sin
    // recomponer: sólo invalida el dibujo.
    val waveReveal = remember { Animatable(if (isLoading) 0f else 1f) }
    LaunchedEffect(isLoading, bars) {
        if (isLoading) {
            waveReveal.snapTo(0f)
        } else {
            waveReveal.animateTo(1f, tween(durationMillis = 620, easing = FastOutSlowInEasing))
        }
    }

    // Los controles esperan abajo mientras la onda carga y suben a su lugar cuando
    // está lista. Es una sola propiedad de capa, no un relayout.
    val controlsShift by animateFloatAsState(
        targetValue = if (isLoading) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
        label = "waveControlsShift"
    )

    val currentDisplayProgress by rememberUpdatedState(progressForGestures)
    val currentClamped by rememberUpdatedState(clamped)

    // Más altura y barras más anchas, con una variante compacta para teléfonos pequeños.
    val waveTopDp = 18.dp
    val waveHDp = waveHeight
    val boxHeight = waveHDp + 74.dp
    val barWDp = 4.4.dp
    val gapDp = 3.2.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        InkElevated.copy(alpha = 0.95f),
                        Ink.copy(alpha = 1f),
                    )
                )
            )
            .border(1.dp, Line.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .graphicsLayer {
                alpha = contentAlpha
                scaleX = waveScale
                scaleY = waveScale
            }
    ) {
        // ── 1. ONDA ────────────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bars, enabled) {
                    if (!enabled) return@pointerInput
                    val sensitivity = 0.16f // slower and more precise (was 0.32)
                    var startProgress = 0f

                    detectHorizontalDragGestures(
                        onDragStart = {
                            startProgress = currentDisplayProgress
                            dragProgress = startProgress
                        },
                        onDragEnd = {
                            // INSTANTÁNEO: sin animación, seek directo
                            val final = dragProgress
                            dragProgress = null
                            if (final != null) onSeek(final.coerceIn(0f, 1f))
                        },
                        onDragCancel = { dragProgress = null }
                    ) { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount * sensitivity / size.width.toFloat()
                        val next = (dragProgress!! + delta).coerceIn(0f, 1f)
                        dragProgress = next
                        // Aviso en vivo: el reloj que dibuja el reproductor sigue al dedo
                        // sin mandar seeks a ExoPlayer (un seekTo por movimiento es lo
                        // que hacía que adelantar una canción se sintiera trabado).
                        onScrub(next)
                    }
                }
                .pointerInput(bars, trackKey, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val centerX = size.width / 2f
                        val delta = (offset.x - centerX) * 0.16f / size.width.toFloat()
                        val base = currentClamped
                        onSeek((base + delta).coerceIn(0f, 1f))
                    }
                }
        ) {
            val barW = barWDp.toPx()
            val gap = gapDp.toPx()
            val slot = barW + gap
            val contentW = bars.size * slot - gap
            val w = size.width
            val waveTop = waveTopDp.toPx()
            val waveH = waveHDp.toPx()
            val waveCenterY = waveTop + waveH / 2f
            val centerX = w / 2f
            val startX = centerX - animatedProgressForDraw * contentW

            // glow detrás de aguja
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, centerLineColor.copy(alpha = 0.09f), Color.Transparent),
                    startX = centerX - 32.dp.toPx(),
                    endX = centerX + 32.dp.toPx(),
                ),
                topLeft = Offset(centerX - 32.dp.toPx(), waveTop),
                size = Size(64.dp.toPx(), waveH),
            )

            val reveal = waveReveal.value
            val waveBottom = waveTop + waveH
            val minBarH = 3.dp.toPx()
            bars.forEachIndexed { i, amp ->
                val normalized = amp.coerceIn(0f, 1f)
                // El silencio real sigue siendo una línea baja, incluso con la onda más alta.
                val baseFrac = if (normalized <= 0.0001f) 0.035f else 0.16f + normalized * 0.84f
                // Escalonado de izquierda a derecha: cada barra arranca un poco más
                // tarde que la anterior. Con la onda ya revelada el factor es 1 y el
                // cálculo no cuesta nada.
                val local = if (reveal >= 0.999f) {
                    1f
                } else {
                    val start = (i.toFloat() / bars.size.toFloat()) * 0.55f
                    ((reveal - start) / 0.45f).coerceIn(0f, 1f)
                }
                val eased = 1f - (1f - local) * (1f - local)
                // Cargando: todas las barras son una línea fina apoyada en la base de la
                // onda. Al revelarse crecen y suben hasta quedar centradas en su altura
                // final. Son dos interpolaciones por barra, no un relayout.
                val finalH = waveH * baseFrac
                val bh = minBarH + (finalH - minBarH) * eased
                val bottomY = waveBottom + ((waveCenterY + finalH / 2f) - waveBottom) * eased
                val x = startX + i * slot
                if (x + barW < -12.dp.toPx() || x > w + 12.dp.toPx()) return@forEachIndexed
                val played = (x + barW / 2f) <= centerX
                drawRoundRect(
                    color = if (played) playedColor else remainingColor,
                    topLeft = Offset(x, bottomY - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                )
            }

            // aguja central Gold con glow
            drawRoundRect(
                color = centerLineColor.copy(alpha = 0.24f),
                topLeft = Offset(centerX - 1.6.dp.toPx(), waveTop + 2.dp.toPx()),
                size = Size(3.2.dp.toPx(), waveH - 4.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            drawRoundRect(
                color = centerLineColor,
                topLeft = Offset(centerX - 1.dp.toPx(), waveTop + 4.dp.toPx()),
                size = Size(2.dp.toPx(), waveH - 8.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }

        // ── 2. BOTONES CENTRADOS EN LA ONDA (no en el Box) ────────────────
        // Los controles permanecen centrados en la zona de la onda, también en compacto.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(waveHDp + waveTopDp * 2) // exact wave area
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.graphicsLayer {
                    alpha = controlsAlpha
                    translationY = controlsShift * 26.dp.toPx()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = onShuffle,
                    enabled = enabled,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream.copy(alpha = 0.46f)),
                ) { Icon(Icons.Rounded.FastRewind, null, Modifier.size(20.dp)) }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onPrev,
                    enabled = enabled,
                    modifier = Modifier.size(52.dp).background(InkElevated, CircleShape).border(1.dp, Gold.copy(alpha = 0.32f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream),
                ) { Icon(Icons.Rounded.SkipPrevious, "Prev", Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    modifier = Modifier.size(68.dp).background(Gold, CircleShape).border(1.2.dp, GoldDim, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Ink),
                ) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(36.dp)) }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = onNext,
                    enabled = enabled,
                    modifier = Modifier.size(52.dp).background(InkElevated, CircleShape).border(1.dp, Gold.copy(alpha = 0.32f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream),
                ) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp)) }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onRepeat,
                    enabled = enabled,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream.copy(alpha = 0.46f)),
                ) { Icon(Icons.Rounded.FastForward, null, Modifier.size(20.dp)) }
            }
        }

        // ── 3. TIEMPOS ABAJO ───────────────────────────────────────────────
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(positionLabel.ifEmpty { "0:00" }, color = Cream, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(durationLabel.ifEmpty { "3:53" }, color = Cream.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

// ────────────────────────── Helpers ─────────────────────────────────────

private fun generateKukosinWaveform(count: Int, seed: Int = 42): List<Float> {
    val rnd = Random(seed)
    return List(count) { i ->
        val t = i / count.toFloat()
        val env = kotlin.math.sin(t * Math.PI).toFloat() * 0.28f + 0.72f
        val low = (kotlin.math.sin(t * Math.PI * 6) * 0.22 + kotlin.math.sin(t * Math.PI * 13) * 0.14 + kotlin.math.sin(t * Math.PI * 22) * 0.06).toFloat()
        val noise = rnd.nextFloat() * 0.38f
        ((0.48f + low + noise) * env).coerceIn(0.10f, 1f)
    }
}

private fun expandWaveform(src: List<Float>, target: Int): List<Float> {
    if (src.size >= target) return src
    return List(target) { i ->
        val f = i * src.size.toFloat() / target
        val lo = f.toInt().coerceIn(src.indices)
        val hi = (lo + 1).coerceIn(src.indices)
        val frac = f - lo
        src[lo] * (1 - frac) + src[hi] * frac
    }
}

fun pcmToKukosinWaveform(pcm: ShortArray, bars: Int = 180): List<Float> {
    if (pcm.isEmpty()) return generateKukosinWaveform(bars, seed = pcm.size)
    val perBar = (pcm.size.toFloat() / bars).toInt().coerceAtLeast(1)
    var globalMax = 1f
    val rms = FloatArray(bars) { b ->
        val s = b * perBar; val e = minOf(s + perBar, pcm.size)
        if (s >= e) return@FloatArray 0f
        var sum = 0.0; for (k in s until e) { val v = pcm[k] / 32768f; sum += v * v }
        kotlin.math.sqrt(sum / (e - s)).toFloat().also { if (it > globalMax) globalMax = it }
    }
    return rms.map { ((it / globalMax).coerceIn(0f, 1f).toDouble().pow(0.60)).toFloat() }
}
