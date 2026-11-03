package com.kukosin.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.data.analysis.EmbeddedLyrics
import com.kukosin.app.data.analysis.LyricLine
import com.kukosin.app.data.analysis.LyricWord
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkSoft
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Curva común para todo el panel de letras.
 *
 * Es un "ease-out" largo: arranca con decisión pero frena muy suave, así el
 * movimiento nunca se siente brusco ni termina de golpe.
 */
private val LyricsEasing = CubicBezierEasing(0.22f, 0.08f, 0.16f, 1f)

/** Duración base de una transición de verso. Lento a propósito (pedido: suave, no rápido). */
private const val VERSE_MS = 900

/** Duración del desplazamiento de la lista al cambiar de verso. */
private const val SCROLL_MS = 950

/** Fracción del alto visible donde se ancla el verso activo (0.5 = centro exacto). */
private const val ANCHOR_FRACTION = 0.42f

/**
 * Reloj suave para las letras.
 *
 * El reproductor entrega la posición cada 100–250 ms, insuficiente para que el
 * relleno palabra por palabra se vea continuo. Acá se extrapola con
 * [withFrameNanos] a partir del último dato real y se re-ancla en cada muestra.
 *
 * Importante: el valor se lee dentro de lambdas de dibujo (`drawWithContent`) y
 * de [derivedStateOf], nunca directamente en la composición, para que el reloj
 * a 60 fps provoque solo re-dibujo y no re-composición.
 */
@Composable
internal fun rememberLyricsClock(positionMs: Long, isPlaying: Boolean): State<Long> {
    val clock = remember { mutableLongStateOf(positionMs) }
    var anchorMs by remember { mutableLongStateOf(positionMs) }
    var anchorAt by remember { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(positionMs) {
        val jump = positionMs - anchorMs
        // Salto grande = seek: se corrige de inmediato en vez de "arrastrarse".
        if (abs(jump) > 400L) clock.longValue = positionMs
        anchorMs = positionMs
        anchorAt = System.nanoTime()
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            clock.longValue = anchorMs
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos { frameNanos ->
                val elapsed = ((frameNanos - anchorAt) / 1_000_000f).coerceIn(0f, 320f)
                clock.longValue = (anchorMs + elapsed).toLong()
            }
        }
    }

    return clock
}

/**
 * Panel de letras.
 *
 * El motor es el de la ronda 1: reloj a 60 fps extrapolado del último dato real,
 * versos que se acomodan con un ease-out largo, la lista sigue al verso activo
 * centrado en el 46 % del alto visible (y se frena si el usuario está leyendo) y
 * relleno palabra por palabra continuo cuando el archivo trae tiempos por palabra.
 */
@Composable
fun LyricsPanel(
    lyrics: EmbeddedLyrics?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    onLineClick: ((Long) -> Unit)? = null,
    onScrollActivity: ((Boolean) -> Unit)? = null,
) {
    if (lyrics == null) {
        EmptyLyricsPlaceholder(modifier)
        return
    }

    val synced = lyrics.synced
    val fade = Brush.verticalGradient(
        0f to Ink,
        0.10f to Color.Transparent,
        0.86f to Color.Transparent,
        1f to Ink,
    )

    if (synced.isEmpty()) {
        val plainListState = rememberLazyListState()
        ObserveUserScroll(plainListState, onScrollActivity)
        LazyColumn(
            state = plainListState,
            modifier = modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(fade)
                },
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 28.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            item { LyricsSourceChip("${lyrics.source} · text") }
            item {
                Text(
                    text = lyrics.plainText,
                    color = Cream,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 38.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    SyncedLyrics(
        lyrics = lyrics,
        positionMs = positionMs,
        isPlaying = isPlaying,
        fade = fade,
        onLineClick = onLineClick,
        onScrollActivity = onScrollActivity,
        modifier = modifier,
    )
}

@Composable
private fun SyncedLyrics(
    lyrics: EmbeddedLyrics,
    positionMs: Long,
    isPlaying: Boolean,
    fade: Brush,
    onLineClick: ((Long) -> Unit)?,
    onScrollActivity: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    val synced = lyrics.synced
    val clock = rememberLyricsClock(positionMs, isPlaying)

    // Se recalcula en cada frame, pero al ser derivedStateOf solo invalida la
    // composición cuando el índice cambia de verdad.
    val active by remember(synced) {
        derivedStateOf {
            val now = clock.value
            var index = -1
            for (i in synced.indices) {
                if (synced[i].timeMs <= now) index = i else break
            }
            index.coerceAtLeast(0)
        }
    }

    val listState = rememberLazyListState()
    val userScrolling = rememberUserScrolling(listState)
    var followEnabled by remember { mutableStateOf(true) }
    // El primer salto no se anima: al abrir las letras el verso actual aparece ya
    // centrado, sin un barrido desde la primera línea.
    var initialAligned by remember(synced) { mutableStateOf(false) }

    ObserveUserScroll(listState, onScrollActivity)

    LaunchedEffect(userScrolling) {
        if (userScrolling) {
            followEnabled = false
        } else {
            // Deja respirar la lectura manual antes de volver a engancharse.
            delay(1800)
            followEnabled = true
        }
    }

    LaunchedEffect(active, followEnabled) {
        if (!followEnabled) return@LaunchedEffect
        val firstAlignment = !initialAligned
        // Un respiro mínimo evita que el scroll salga antes de que el verso
        // anterior termine de encogerse; el resultado se siente continuo.
        if (!firstAlignment) delay(60)
        listState.animateCenteredOn(
            index = active,
            anchorFraction = ANCHOR_FRACTION,
            spec = if (firstAlignment) {
                snap()
            } else {
                tween(durationMillis = SCROLL_MS, easing = LyricsEasing)
            },
        )
        initialAligned = true
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val halfViewport = maxHeight / 2
        val listModifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                drawRect(fade)
            }

        LazyColumn(
            state = listState,
            modifier = listModifier,
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 8.dp,
                top = (halfViewport - 28.dp).coerceAtLeast(0.dp),
                bottom = halfViewport,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            itemsIndexed(synced, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
                LyricVerse(
                    line = line,
                    nextTimeMs = synced.getOrNull(index + 1)?.timeMs,
                    clock = clock,
                    isActive = index == active,
                    isPast = index < active,
                    onSeek = onLineClick,
                )
            }
        }

        LyricsSourceChip(
            if (synced.any { it.isKaraoke }) "${lyrics.source} · karaoke" else "${lyrics.source} · synced",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 2.dp),
        )
    }
}

@Composable
private fun EmptyLyricsPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(InkSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Gold, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "No embedded lyrics",
                color = Cream,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This file has no USLT/SYLT, Vorbis LYRICS or ©lyr.",
                color = CreamMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberUserScrolling(listState: LazyListState): Boolean {
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> scrolling = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> scrolling = false
                else -> Unit
            }
        }
    }
    return scrolling
}

/**
 * Desplaza la lista hasta dejar [index] en la altura [anchorFraction] del alto
 * visible, con una animación propia (la nativa de Compose es fija y corta).
 *
 * Si el ítem todavía no está compuesto se estima el salto con el tamaño medio de
 * los ítems visibles y se corrige en la siguiente pasada.
 */
private suspend fun LazyListState.animateCenteredOn(
    index: Int,
    anchorFraction: Float,
    spec: AnimationSpec<Float>,
) {
    repeat(3) {
        val info = layoutInfo
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        if (viewport <= 0f) return
        val anchor = info.viewportStartOffset + viewport * anchorFraction

        val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible != null) {
            val delta = (visible.offset + visible.size / 2f) - anchor
            if (abs(delta) < 1.5f) return
            animateScrollBy(delta, spec)
            return
        }

        val first = info.visibleItemsInfo.firstOrNull() ?: return
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        val count = (last.index - first.index + 1).coerceAtLeast(1)
        val average = (last.offset + last.size - first.offset).toFloat() / count
        val delta = if (index > last.index) {
            (index - last.index) * average - (viewport * (1f - anchorFraction))
        } else {
            -((first.index - index) * average + viewport * anchorFraction)
        }
        if (abs(delta) < 1.5f) return
        animateScrollBy(delta, spec)
    }
}

@Composable
private fun ObserveUserScroll(
    listState: LazyListState,
    onScrollActivity: ((Boolean) -> Unit)?,
) {
    LaunchedEffect(listState, onScrollActivity) {
        if (onScrollActivity == null) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> onScrollActivity(true)
                is DragInteraction.Stop, is DragInteraction.Cancel -> onScrollActivity(false)
                else -> Unit
            }
        }
    }
}

@Composable
private fun LyricsSourceChip(label: String, modifier: Modifier = Modifier) {
    Surface(color = InkSoft, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Gold.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Un verso.
 *
 * - La línea activa crece y toma el dorado; las demás se encogen y se apagan con
 *   una transición larga, así "se acomodan" en lugar de saltar.
 * - Una barra dorada acompaña al verso activo.
 * - Si el verso trae tiempos por palabra (LRC enriquecido, SYLT, etc.) se dibuja
 *   el relleno continuo de cada palabra leyendo el reloj en la fase de dibujo.
 */
@Composable
private fun LyricVerse(
    line: LyricLine,
    nextTimeMs: Long?,
    clock: State<Long>,
    isActive: Boolean,
    isPast: Boolean,
    onSeek: ((Long) -> Unit)?,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.975f,
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseScale",
    )
    val color by animateColorAsState(
        targetValue = when {
            isActive -> Gold
            isPast -> Cream.copy(alpha = 0.26f)
            else -> CreamMuted.copy(alpha = 0.34f)
        },
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseColor",
    )
    val fontSize by animateFloatAsState(
        targetValue = if (isActive) 27f else 22f,
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseFontSize",
    )
    val lineHeight by animateFloatAsState(
        targetValue = if (isActive) 36f else 30f,
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseLineHeight",
    )
    val drift by animateDpAsState(
        targetValue = if (isActive) 0.dp else 1.dp,
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseDrift",
    )
    val accent by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(durationMillis = VERSE_MS, easing = LyricsEasing),
        label = "verseAccent",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = drift.toPx()
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .drawBehind {
                if (accent > 0.01f) {
                    val barWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = Gold.copy(alpha = 0.75f * accent),
                        topLeft = Offset(-10.dp.toPx(), 2.dp.toPx()),
                        size = Size(barWidth, (size.height - 4.dp.toPx()).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                }
            }
            .clickable(enabled = onSeek != null) { onSeek?.invoke(line.timeMs) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (line.isKaraoke) {
            KaraokeVerse(line = line, nextTimeMs = nextTimeMs, clock = clock, isActive = isActive, isPast = isPast, onSeek = onSeek)
        } else {
            Text(
                text = line.text,
                color = color,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                ),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class WordState { SUNG, CURRENT, UPCOMING }

/**
 * Verso con tiempos por palabra.
 *
 * Todo el cálculo temporal vive en la fase de dibujo: el reloj puede avanzar a
 * 60 fps sin re-componer y el relleno se ve continuo en vez de a saltos.
 */
@Composable
private fun KaraokeVerse(
    line: LyricLine,
    nextTimeMs: Long?,
    clock: State<Long>,
    isActive: Boolean,
    isPast: Boolean,
    onSeek: ((Long) -> Unit)?,
) {
    val words = line.words

    val currentIndex by remember(words, isActive, isPast) {
        derivedStateOf {
            val now = clock.value
            if (isPast) words.size
            else if (!isActive) -1
            else {
                var index = -1
                for (i in words.indices) {
                    if (words[i].timeMs <= now) index = i else break
                }
                index
            }
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        words.forEachIndexed { i, word ->
            val state = when {
                i < currentIndex -> WordState.SUNG
                i == currentIndex -> WordState.CURRENT
                else -> WordState.UPCOMING
            }
            KaraokeWord(
                word = word,
                nextTimeMs = words.getOrNull(i + 1)?.timeMs ?: nextTimeMs,
                clock = clock,
                state = state,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun KaraokeWord(
    word: LyricWord,
    nextTimeMs: Long?,
    clock: State<Long>,
    state: WordState,
    onSeek: ((Long) -> Unit)?,
) {
    val duration = ((nextTimeMs ?: (word.timeMs + 900L)) - word.timeMs).coerceAtLeast(120L)

    val restColor by animateColorAsState(
        targetValue = when (state) {
            WordState.SUNG -> Cream.copy(alpha = 0.85f)
            WordState.CURRENT -> CreamMuted.copy(alpha = 0.40f)
            WordState.UPCOMING -> CreamMuted.copy(alpha = 0.32f)
        },
        animationSpec = tween(durationMillis = 700, easing = LyricsEasing),
        label = "wordRest",
    )
    val scale by animateFloatAsState(
        targetValue = if (state == WordState.CURRENT) 1.035f else 1f,
        animationSpec = tween(durationMillis = 700, easing = LyricsEasing),
        label = "wordScale",
    )

    val style = MaterialTheme.typography.headlineMedium.copy(fontSize = 25.sp, lineHeight = 34.sp)
    val sungStyle = style.copy(
        shadow = if (state == WordState.CURRENT) {
            Shadow(color = Gold.copy(alpha = 0.45f), offset = Offset.Zero, blurRadius = 14f)
        } else {
            null
        },
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.85f)
            }
            .clickable(enabled = onSeek != null) { onSeek?.invoke(word.timeMs) }
            .padding(vertical = 1.dp),
    ) {
        Text(
            text = word.text,
            color = restColor,
            fontWeight = FontWeight.Medium,
            style = style,
        )
        Text(
            text = word.text,
            color = Gold,
            fontWeight = FontWeight.Bold,
            style = sungStyle,
            modifier = Modifier.drawWithContent {
                val progress = ((clock.value - word.timeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                if (progress > 0f) {
                    clipRect(right = size.width * progress) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
        )
    }
}
