// KukosinWaveformSlider.kt — onda desplazable con controles integrados.
// Ubicación: app/src/main/java/com/kukosin/app/ui/component/KukosinWaveformSlider.kt

package com.kukosin.app.ui.component

import androidx.compose.animation.core.AnimationSpec
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.ui.Motion
import com.kukosin.app.ui.theme.*
import kotlin.math.pow
import kotlin.random.Random

/**
 * Onda desplazable con los controles de transporte dentro de la onda.
 *
 * - La onda es una cinta MÁS ANCHA que la tarjeta y se desplaza mientras avanza la
 *   canción; la aguja (la línea que separa lo escuchado de lo que falta) avanza de
 *   izquierda a derecha. Así la tarjeta siempre queda llena de onda: nunca se ve
 *   media onda y medio vacío al empezar un tema. No es una imagen estática.
 * - Arrastrar no salta al punto tocado: parte de la posición actual y avanza con
 *   sensibilidad reducida, así un gesto corto no te manda al principio del tema.
 * - Cada canción tiene su onda real (la extrae [com.kukosin.app.data.analysis.WaveformExtractor]).
 * - Las barras son más gruesas y están centradas verticalmente, y los botones son más
 *   grandes: es el lenguaje visual del reproductor de referencia sin perder la paleta
 *   Ink/Cream/dorada de Kukosin.
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
    /** Retroceso rápido (10 s). Reemplaza al antiguo botón de shuffle dentro de la onda. */
    onRewind: () -> Unit = {},
    /** Avance rápido (10 s). Reemplaza al antiguo botón de repeat dentro de la onda. */
    onForward: () -> Unit = {},
    /**
     * Abre la cola. Si no es null, se dibuja un tirador "QUEUE" en el borde inferior de
     * la tarjeta: se puede tocar o arrastrar hacia arriba. Es la zona de gesto que
     * queda justo debajo de las barras, así no compite con el seek horizontal.
     */
    onOpenQueue: (() -> Unit)? = null,
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
    // Cuando no arrastras, el progreso se anima con tween 220ms para movimiento sedoso.
    // Al arrastrar, snap instantáneo para seguir el dedo sin lag.
    val animatedProgress by animateFloatAsState(
        targetValue = displayProgress,
        animationSpec = if (isDragging) snap() else tween(durationMillis = 220, easing = LinearEasing),
        label = "waveProgressSmooth",
    )
    // Para el gesto, usamos el valor objetivo (no el animado) para cálculo preciso
    val progressForGestures = displayProgress
    val animatedProgressForDraw = animatedProgress

    // Fade out/in al pausar/reproducir
    val contentAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.58f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "waveFade",
    )
    val waveScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.97f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "waveScale",
    )
    // Mientras se arrastra la onda los controles se vuelven translúcidos para no
    // tapar la parte de la canción que se está eligiendo.
    val controlsAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.26f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "waveControlsAlpha",
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
        label = "waveControlsShift",
    )

    val currentDisplayProgress by rememberUpdatedState(progressForGestures)
    val currentClamped by rememberUpdatedState(clamped)

    // En pantallas angostas (≤ 340 dp) los controles se achican un escalón para que
    // sigan entrando los cinco botones con aire entre ellos.
    val compact = LocalConfiguration.current.screenWidthDp <= 340
    val sideButton = if (compact) 38.dp else 44.dp
    val sideIcon = if (compact) 20.dp else 23.dp
    val skipButton = if (compact) 52.dp else 60.dp
    val skipIcon = if (compact) 26.dp else 30.dp
    val playButton = if (compact) 68.dp else 78.dp
    val playIcon = if (compact) 36.dp else 42.dp

    // Barras más gruesas y con menos separación: la onda se lee como una textura
    // continua, no como un peine. La altura total no cambia.
    val waveTopDp = 8.dp
    val boxHeight = waveHeight + 52.dp
    val barWDp = 6.2.dp
    val gapDp = 3.4.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        InkElevated.copy(alpha = 0.92f),
                        Ink.copy(alpha = 1f),
                    )
                )
            )
            .border(1.dp, Line.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
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
                    val sensitivity = 0.16f // slower and more precise

                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragProgress = currentDisplayProgress
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
            val waveH = waveHeight.toPx()
            val waveCenterY = waveTop + waveH / 2f
            // La cinta se corre lo que sobra de ancho (contentW - w) repartido en toda
            // la canción, y la aguja cruza la tarjeta de borde a borde. Si por algún
            // motivo la onda entrara completa (contentW <= w), se centra y la aguja
            // recorre sólo el ancho de la onda.
            val tapeOverflow = (contentW - w).coerceAtLeast(0f)
            val startX = -animatedProgressForDraw * tapeOverflow
            val needleX = if (tapeOverflow > 0f) {
                animatedProgressForDraw * w
            } else {
                (w - contentW) / 2f + animatedProgressForDraw * contentW
            }

            // Halo suave detrás de la aguja: se lee como "ahora" sin dibujar una línea
            // dura sobre las barras.
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, centerLineColor.copy(alpha = 0.10f), Color.Transparent),
                    startX = needleX - 44.dp.toPx(),
                    endX = needleX + 44.dp.toPx(),
                ),
                topLeft = Offset(needleX - 44.dp.toPx(), waveTop),
                size = Size(88.dp.toPx(), waveH),
            )

            val reveal = waveReveal.value
            val minBarH = 4.dp.toPx()
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
                val finalH = waveH * baseFrac
                val bh = minBarH + (finalH - minBarH) * eased
                val x = startX + i * slot
                if (x + barW < -12.dp.toPx() || x > w + 12.dp.toPx()) return@forEachIndexed
                val played = (x + barW / 2f) <= needleX
                // Centradas verticalmente: las barras crecen hacia arriba y hacia abajo
                // desde la línea media, igual que en el reproductor de referencia.
                val top = waveCenterY - bh / 2f
                drawRoundRect(
                    color = if (played) playedColor else remainingColor,
                    topLeft = Offset(x, top),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }

        // ── 2. BOTONES CENTRADOS EN LA ONDA ────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(waveHeight + waveTopDp * 2) // exact wave area
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
                    onClick = onRewind,
                    enabled = enabled,
                    modifier = Modifier
                        .size(sideButton)
                        .background(InkSoft.copy(alpha = 0.55f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream.copy(alpha = 0.62f)),
                ) { Icon(Icons.Rounded.FastRewind, "Back 10 seconds", Modifier.size(sideIcon)) }
                Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
                IconButton(
                    onClick = onPrev,
                    enabled = enabled,
                    modifier = Modifier
                        .size(skipButton)
                        .background(InkElevated, CircleShape)
                        .border(1.dp, Gold.copy(alpha = 0.34f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream),
                ) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(skipIcon)) }
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                IconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    modifier = Modifier
                        .size(playButton)
                        .background(Gold, CircleShape)
                        .border(1.2.dp, GoldDim, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Ink),
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        Modifier.size(playIcon),
                    )
                }
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                IconButton(
                    onClick = onNext,
                    enabled = enabled,
                    modifier = Modifier
                        .size(skipButton)
                        .background(InkElevated, CircleShape)
                        .border(1.dp, Gold.copy(alpha = 0.34f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream),
                ) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(skipIcon)) }
                Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
                IconButton(
                    onClick = onForward,
                    enabled = enabled,
                    modifier = Modifier
                        .size(sideButton)
                        .background(InkSoft.copy(alpha = 0.55f), CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Cream.copy(alpha = 0.62f)),
                ) { Icon(Icons.Rounded.FastForward, "Forward 10 seconds", Modifier.size(sideIcon)) }
            }
        }

        // ── 3. TIEMPOS ABAJO ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                positionLabel.ifEmpty { "0:00" },
                color = Cream,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                durationLabel.ifEmpty { "3:53" },
                color = Cream.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // ── 4. TIRADOR DE LA COLA ──────────────────────────────────────────
        // Va en el borde inferior de la tarjeta (debajo de las barras) para que el
        // gesto de abrir la cola no se mezcle con el arrastre horizontal del seek.
        if (onOpenQueue != null) {
            QueuePullHandle(
                onOpen = onOpenQueue,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
            )
        }
    }
}

/**
 * Tirador para abrir la cola con un gesto hacia arriba.
 *
 * La zona táctil es más grande que la píldora visible (176 × 44 dp) para que el gesto
 * no exija precisión. Mientras se arrastra, la píldora sube un poco como confirmación;
 * al soltar por encima del umbral se abre la cola (el blur de fondo lo maneja
 * `PlayerApp`). Un toque también la abre, para que se descubra sin adivinar el gesto.
 */
@Composable
private fun QueuePullHandle(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val maxPullPx = with(density) { 22.dp.toPx() }
    val thresholdPx = with(density) { 14.dp.toPx() }
    var pullPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val pullSpec: AnimationSpec<Float> = if (dragging) {
        snap()
    } else {
        Motion.snappy(stiffness = 520f, dampingRatio = 0.92f)
    }
    val animatedPull by animateFloatAsState(
        targetValue = if (dragging) pullPx.coerceIn(-maxPullPx, 0f) else 0f,
        animationSpec = pullSpec,
        label = "queuePullNudge",
    )

    Box(
        modifier = modifier
            // Zona táctil a todo lo ancho de la tarjeta: el gesto funciona en cualquier
            // punto de la franja bajo las barras, no solo sobre la píldora.
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = "Open queue"
                role = Role.Button
                onClick(label = "Open queue") {
                    onOpen()
                    true
                }
            }
            .pointerInput(onOpen) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        pullPx = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        pullPx = (pullPx + dragAmount).coerceIn(-maxPullPx, 0f)
                    },
                    onDragEnd = {
                        val trigger = pullPx <= -thresholdPx
                        dragging = false
                        pullPx = 0f
                        if (trigger) onOpen()
                    },
                    onDragCancel = {
                        dragging = false
                        pullPx = 0f
                    },
                )
            }
            .pointerInput(onOpen) {
                detectTapGestures { onOpen() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 176.dp)
                .graphicsLayer {
                    translationY = animatedPull * 0.55f
                    alpha = (0.72f + 0.28f * (animatedPull / -maxPullPx).coerceIn(0f, 1f))
                        .coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(50))
                .background(Ink.copy(alpha = 0.62f))
                .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = Gold.copy(alpha = 0.82f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "QUEUE",
                color = Gold.copy(alpha = 0.82f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
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
