@file:OptIn(UnstableApi::class)

package com.kukosin.app.ui

import androidx.media3.common.util.UnstableApi
import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.kukosin.app.ui.component.KukosinWaveformSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.kukosin.app.data.artwork.EmbeddedArtwork
import com.kukosin.app.data.ytm.YtmService
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.kukosin.app.data.AlbumGroup
import com.kukosin.app.data.ArtistGroup
import com.kukosin.app.data.FolderNode
import com.kukosin.app.data.LibraryInsights
import com.kukosin.app.data.Track
import com.kukosin.app.data.formatTime
import com.kukosin.app.data.ytm.YtmTrack
import com.kukosin.app.audio.AudioEngineStatus
import com.kukosin.app.playback.AudioConfig
import com.kukosin.app.playback.AudioRoute
import com.kukosin.app.playback.OutputMode
import com.kukosin.app.playback.OutputDevice
import com.kukosin.app.playback.PlaybackService
import com.kukosin.app.playback.QueueManager
import com.kukosin.app.playback.UsbDacManager
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.AccentPreset
import com.kukosin.app.ui.theme.AccentTheme
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Line
import com.kukosin.app.ui.theme.accentColor
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
@Composable
internal fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    isDac: Boolean,
    sheetProgress: Float,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onArtMeasured: (LayoutCoordinates) -> Unit,
    onVerticalDragStart: () -> Unit,
    onVerticalDragDelta: (Float) -> Unit,
    onVerticalDragEnd: (Float) -> Unit,
    onVerticalDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragStart by rememberUpdatedState(onVerticalDragStart)
    val currentOnDragDelta by rememberUpdatedState(onVerticalDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onVerticalDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onVerticalDragCancel)

    // Se mide fuera de graphicsLayer: es la posición en reposo incluso en el primer vuelo.
    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = sheetProgress.coerceIn(0f, 1f)
                    alpha = (1f - p / 0.28f).coerceIn(0f, 1f)
                    translationY = -p * 42.dp.toPx()
                }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            currentOnDragStart()
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            currentOnDragDelta(amount)
                        },
                        onDragEnd = { currentOnDragEnd(tracker.calculateVelocity().y) },
                        onDragCancel = { currentOnDragCancel() },
                    )
                },
            shape = RoundedCornerShape(24.dp),
            color = InkSoft,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            Column {
                CompactSeekBar(
                    progress = progress,
                    durationMs = durationMs,
                    enabled = sheetProgress < 0.08f,
                    onSeek = onSeek,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
                Row(
                    modifier = Modifier
                        .clickable(enabled = sheetProgress < 0.08f, onClick = onOpen)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { alpha = if (sheetProgress > 0.001f) 0f else 1f },
                    ) {
                        Artwork(track, Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                track?.title ?: "No song selected",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Cream,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isDac) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = Gold,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable(onClick = onOpenSignalPath),
                                ) {
                                    Text("DAC", style = MaterialTheme.typography.labelSmall, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                }
                            }
                        }
                        Text(
                            track?.let { "${it.effectiveArtist} · ${it.codecLabel}" } ?: "Open the player",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = CreamMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = onToggle, enabled = sheetProgress < 0.1f && track != null) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Gold,
                        )
                    }
                    IconButton(onClick = onNext, enabled = sheetProgress < 0.1f && track != null) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Cream)
                    }
                }
            }
        }
        // 4 dp de la barra + 12 dp de padding de la fila.
        Box(
            Modifier
                .padding(start = 12.dp, top = 16.dp)
                .size(48.dp)
                .onGloballyPositioned(onArtMeasured),
        )
    }
}

@Composable
internal fun NowPlayingSheet(
    track: Track?,
    controller: MediaController?,
    isPlaying: Boolean,
    hasPermission: Boolean,
    isScanning: Boolean,
    output: OutputDevice,
    analysis: com.kukosin.app.data.analysis.AudioAnalysis?,
    sheetProgress: Float,
    lyricsProgress: Float,
    allowLyricsBack: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    interactiveEnabled: Boolean,
    maxArtDp: Dp,
    waveformHeight: Dp,
    playPauseScale: Float,
    miniArtRectProvider: () -> Rect,
    fullArtSlotRectProvider: () -> Rect,
    onFullArtSlotMeasured: (LayoutCoordinates) -> Unit,
    onLyricsArtSlotMeasured: (LayoutCoordinates) -> Unit,
    onLyricsDragStart: () -> Unit,
    onLyricsDragDelta: (Float) -> Unit,
    onLyricsDragEnd: (Float) -> Unit,
    onLyricsDragCancel: () -> Unit,
    onCloseLyrics: () -> Unit,
    onRequestPermission: () -> Unit,
    onFallbackPlayFirst: () -> Unit,
    onVerticalDragStart: () -> Unit,
    onVerticalDragDelta: (Float) -> Unit,
    onVerticalDragEnd: (Float) -> Unit,
    onVerticalDragCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShowInLibrary: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onOpenLibraryShortcut: () -> Unit,
    onOpenEqualizerShortcut: () -> Unit,
    onOpenSearchShortcut: () -> Unit,
    onOpenSettingsShortcut: () -> Unit,
) {
    var position by remember(controller) { mutableLongStateOf(controller?.currentPosition ?: 0L) }
    // Durante el arrastre, el reloj que se dibuja es el del dedo (no el del player):
    // así la barra no "vuelve atrás" mientras ExoPlayer busca.
    var scrubbing by remember { mutableStateOf(false) }
    var duration by remember(controller) { mutableLongStateOf((controller?.duration ?: 0L).coerceAtLeast(0L)) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var horizontalJob by remember { mutableStateOf<Job?>(null) }
    var previousArt by remember { mutableStateOf<Uri?>(null) }
    var nextArt by remember { mutableStateOf<Uri?>(null) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val artworkWidthPx = with(density) { maxArtDp.toPx() }
    val cornerArtWidthPx = with(density) { 56.dp.toPx() }
    var sheetCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var centerArtRect by remember { mutableStateOf(Rect.Zero) }
    var cornerArtRect by remember { mutableStateOf(Rect.Zero) }

    // Los gestos largos leen siempre los callbacks y progresos más recientes, sin reiniciar
    // pointerInput a mitad del arrastre cuando cambia el progreso de letras o del reproductor.
    val currentLyricsProgress by rememberUpdatedState(lyricsProgress)
    val currentSheetProgress by rememberUpdatedState(sheetProgress)
    val currentInteractive by rememberUpdatedState(interactiveEnabled)
    val currentArtScale by rememberUpdatedState(playPauseScale)
    val currentOnLyricsStart by rememberUpdatedState(onLyricsDragStart)
    val currentOnLyricsDelta by rememberUpdatedState(onLyricsDragDelta)
    val currentOnLyricsEnd by rememberUpdatedState(onLyricsDragEnd)
    val currentOnLyricsCancel by rememberUpdatedState(onLyricsDragCancel)
    val currentOnSheetStart by rememberUpdatedState(onVerticalDragStart)
    val currentOnSheetDelta by rememberUpdatedState(onVerticalDragDelta)
    val currentOnSheetEnd by rememberUpdatedState(onVerticalDragEnd)
    val currentOnSheetCancel by rememberUpdatedState(onVerticalDragCancel)

    BackHandler(enabled = allowLyricsBack && lyricsProgress > 0.01f && sheetProgress > 0.9f) {
        onCloseLyrics()
    }

    val currentLyrics = analysis?.takeIf { track != null && it.uri == track.uri }?.lyrics
    val context = LocalContext.current
    var realWaveform by remember(track?.uri) { mutableStateOf<List<Float>?>(null) }
    // `true` desde el primer frame de una pista local: la onda arranca plana y sube
    // cuando termina la extracción, en vez de mostrar una onda inventada y cambiarla.
    var waveformLoading by remember(track?.uri) { mutableStateOf(track?.isLocal == true) }
    val flatWaveform = remember { List(180) { 0f } }
    LaunchedEffect(track?.uri) {
        realWaveform = null
        val selected = track
        if (selected != null && selected.isLocal) {
            waveformLoading = true
            try {
                realWaveform = com.kukosin.app.data.analysis.WaveformExtractor.getWaveform(context, selected, 180)
            } finally {
                waveformLoading = false
            }
        } else {
            waveformLoading = false
        }
    }
    // Muestreo del reloj: 200 ms ≈ 5 Hz. El slider rellena el hueco con un tween
    // lineal, así que la onda se sigue moviendo a 60 fps; muestrear 30 veces por
    // segundo sólo generaba recomposiciones de toda la hoja que competían con las
    // animaciones de apertura y de carátula.
    //
    // Mientras el dedo arrastra (`scrubbing`) NO se lee la posición del player: manda la
    // posición del dedo (`onScrub`). Así el reloj sigue al dedo al instante aunque el
    // seek real tarde, y no hay saltos cuando ExoPlayer confirma la nueva posición.
    LaunchedEffect(controller) {
        while (isActive) {
            duration = (controller?.duration ?: 0L).coerceAtLeast(0L)
            if (!scrubbing) position = controller?.currentPosition ?: 0L
            delay(200L)
        }
    }

    // El blur del arrastre respeta el ajuste de Ajustes → Apariencia → Blur: con "Off" no
    // se ejecuta ninguna pasada de RenderEffect y el gesto cuesta lo mismo que un scroll.
    val uiBlur = LocalUiSettings.current.blur
    val dragBlurMax = uiBlur.artworkDrag * 2f
    val artworkBlur by animateDpAsState(
        targetValue = if (dragX != 0f && dragBlurMax > 0.dp) {
            (abs(dragX).coerceAtMost(12f) * (dragBlurMax.value / 12f)).dp
        } else {
            0.dp
        },
        animationSpec = Motion.quick(),
        label = "artworkDragBlur",
    )
    val artworkAlpha by animateFloatAsState(
        targetValue = if (dragX != 0f) 1f - (abs(dragX) / 90f).coerceIn(0f, 1f) else 1f,
        animationSpec = Motion.quick(),
        label = "artworkDragAlpha",
    )

    fun artworkAt(offset: Int): Uri? {
        val player = controller ?: return null
        val index = player.currentMediaItemIndex + offset
        if (index !in 0 until player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri
        return if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            EmbeddedArtwork.uriFor(uri)
        } else {
            item.mediaMetadata.artworkUri
        }
    }

    // La animación se ejecuta fuera del AwaitPointerEventScope restringido; dragX es
    // la única fuente de verdad tanto al arrastrar como al completar/cancelar el gesto.
    fun settleHorizontal(step: Float, velocityX: Float, cancelled: Boolean) {
        val player = controller
        val threshold = (step * 0.35f).coerceAtLeast(30f)
        val flingThreshold = step * SWIPE_FLING_FACTOR
        val goNext = dragX < -threshold || velocityX < -flingThreshold
        val goPrevious = dragX > threshold || velocityX > flingThreshold
        horizontalJob?.cancel()
        horizontalJob = gestureScope.launch {
            suspend fun snapBack() {
                animate(dragX, 0f, animationSpec = Motion.snappy()) { value, _ ->
                    dragX = value
                }
            }
            val next = !cancelled && goNext && player?.hasNextMediaItem() == true
            val previous = !cancelled && goPrevious && player?.hasPreviousMediaItem() == true
            if (player != null && (next || previous)) {
                animate(
                    initialValue = dragX,
                    targetValue = if (next) -step else step,
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) { value, _ -> dragX = value }
                val oldIndex = player.currentMediaItemIndex
                val switched = try {
                    if (next) player.seekToNext() else player.seekToPrevious()
                    awaitIndexChange(player, oldIndex)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w("KukosinSwipe", "Could not change track", error)
                    false
                }
                if (switched) {
                    // La vecina permanece centrada hasta que el controlador publique la nueva pista.
                    withFrameNanos { }
                    dragX = 0f
                } else {
                    snapBack()
                }
            } else {
                snapBack()
            }
        }
    }

    // El hit-test vive en el contenedor estable, por encima de la carátula dibujada.
    // El rectángulo sigue la portada visual; una vez iniciado, el gesto usa coordenadas
    // del reproductor que no se mueven mientras aparecen las letras.
    val scaledCenterArt = if (centerArtRect == Rect.Zero) Rect.Zero
        else scaleRectAroundCenter(centerArtRect, playPauseScale)
    val visibleCoverRect = if (scaledCenterArt == Rect.Zero) Rect.Zero else {
        val destination = if (cornerArtRect != Rect.Zero) {
            lerpRect(scaledCenterArt, cornerArtRect, lyricsProgress.coerceIn(0f, 1f))
        } else scaledCenterArt
        Rect(destination.left + dragX, destination.top, destination.right + dragX, destination.bottom)
    }
    val hitBounds = expandedCoverHitBounds(visibleCoverRect, with(density) { 24.dp.toPx() })
    var swipeStep by remember { mutableFloatStateOf(1f) }
    val coverGesture = rememberCoverGestureModifier(
        enabled = interactiveEnabled && sheetProgress >= 0.98f,
        hitBounds = hitBounds,
        lyricsProgress = lyricsProgress,
        controllerKey = controller,
        trackKey = track?.uri,
        onStart = { axis ->
            when (axis) {
                CoverGestureAxis.TRACK -> {
                    horizontalJob?.cancel()
                    swipeStep = lerpFloat(
                        artworkWidthPx * currentArtScale,
                        cornerArtWidthPx,
                        currentLyricsProgress.coerceIn(0f, 1f),
                    ).coerceAtLeast(1f)
                    hasPrevious = controller?.hasPreviousMediaItem() == true
                    hasNext = controller?.hasNextMediaItem() == true
                    previousArt = artworkAt(-1)
                    nextArt = artworkAt(1)
                }
                CoverGestureAxis.LYRICS -> currentOnLyricsStart()
                CoverGestureAxis.MINIMIZE -> currentOnSheetStart()
            }
        },
        onDrag = { axis, dx, dy ->
            when (axis) {
                CoverGestureAxis.TRACK -> {
                    val raw = dragX + dx
                    val blocked = (raw > 0f && !hasPrevious) || (raw < 0f && !hasNext)
                    dragX = if (blocked) dragX + dx * SWIPE_EDGE_RESISTANCE
                        else raw.coerceIn(-swipeStep, swipeStep)
                }
                CoverGestureAxis.LYRICS -> currentOnLyricsDelta(dy)
                CoverGestureAxis.MINIMIZE -> currentOnSheetDelta(dy)
            }
        },
        onEnd = { axis, velocityX, velocityY ->
            when (axis) {
                CoverGestureAxis.TRACK -> settleHorizontal(swipeStep, velocityX, cancelled = false)
                CoverGestureAxis.LYRICS -> currentOnLyricsEnd(velocityY)
                CoverGestureAxis.MINIMIZE -> currentOnSheetEnd(velocityY)
            }
        },
        onCancel = { axis ->
            when (axis) {
                CoverGestureAxis.TRACK -> settleHorizontal(swipeStep, 0f, cancelled = true)
                CoverGestureAxis.LYRICS -> currentOnLyricsCancel()
                CoverGestureAxis.MINIMIZE -> currentOnSheetCancel()
            }
        },
    )

    // La cabecera permite cerrar letras con un arrastre, sin robar el scroll del panel.
    val headerDrag = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        var draggingLyrics = false
        detectVerticalDragGestures(
            onDragStart = {
                if (currentInteractive) {
                    tracker.resetTracking()
                    draggingLyrics = currentLyricsProgress > 0.01f
                    if (draggingLyrics) currentOnLyricsStart() else currentOnSheetStart()
                }
            },
            onVerticalDrag = { change, amount ->
                if (currentInteractive) {
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (draggingLyrics) currentOnLyricsDelta(amount) else currentOnSheetDelta(amount)
                }
            },
            onDragEnd = {
                if (currentInteractive) {
                    val velocity = tracker.calculateVelocity().y
                    if (draggingLyrics) currentOnLyricsEnd(velocity) else currentOnSheetEnd(velocity)
                }
            },
            onDragCancel = {
                if (currentInteractive) {
                    if (draggingLyrics) currentOnLyricsCancel() else currentOnSheetCancel()
                }
            },
        )
    }
    // Este nodo permanece montado durante el gesto. Solo inicia el minimizado si
    // el arrastre empezó fuera de letras y va hacia abajo; no roba su scroll.
    val bodyDismiss = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentInteractive || currentLyricsProgress > 0.01f || currentSheetProgress < 0.98f) {
                return@awaitEachGesture
            }
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var totalX = 0f
            var totalY = 0f
            var dragging = false
            var finished = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        if (dragging) {
                            currentOnSheetEnd(tracker.calculateVelocity().y)
                            change.consume()
                        }
                        finished = true
                        break
                    }
                    totalX += change.position.x - change.previousPosition.x
                    val deltaY = change.position.y - change.previousPosition.y
                    totalY += deltaY
                    val amount = if (!dragging) {
                        val slop = viewConfiguration.touchSlop
                        if (maxOf(abs(totalX), abs(totalY)) <= slop) continue
                        if (totalY <= 0f || abs(totalX) > abs(totalY)) break
                        dragging = true
                        currentOnSheetStart()
                        (totalY - slop).coerceAtLeast(0f)
                    } else deltaY
                    currentOnSheetDelta(amount)
                    change.consume()
                }
            } finally {
                if (!finished && dragging) currentOnSheetCancel()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { sheetCoords = it }
            .then(coverGesture)
            .then(bodyDismiss),
    ) {
        // El fondo sigue a la carátula durante el morph con el MiniPlayer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = sheetProgress.coerceIn(0f, 1f)
                    val full = fullArtSlotRectProvider()
                    val current = lerpRect(miniArtRectProvider(), full, p)
                    translationY = (current.top - full.top).coerceAtLeast(0f)
                    alpha = ((p - 0.04f) / 0.76f).coerceIn(0f, 1f)
                    val topCorner = 28.dp * (1f - p)
                    shape = RoundedCornerShape(topStart = topCorner, topEnd = topCorner)
                    clip = true
                }
                .background(Brush.verticalGradient(listOf(InkElevated, Ink, Ink)))
                .pointerInput(Unit) { detectTapGestures { } },
        )

        // Los dos slots se miden sin capas transformadas en sus ancestros. Así el arte
        // compartido y el arte de letras utilizan exactamente los mismos rectángulos.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerDrag)
                    .graphicsLayer {
                        val p = sheetProgress.coerceIn(0f, 1f)
                        val full = fullArtSlotRectProvider()
                        val current = lerpRect(miniArtRectProvider(), full, p)
                        translationY = (current.top - full.top).coerceAtLeast(0f)
                        alpha = ((p - 0.12f) / 0.72f).coerceIn(0f, 1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, bottom = 2.dp)
                        .width(38.dp)
                        .height(4.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(CreamMuted.copy(alpha = 0.45f)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = interactiveEnabled) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = CreamMuted, modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = Gold, fontWeight = FontWeight.Bold)
                        Text(
                            if (track == null) "Your music" else if (track.isLocal) "Local music" else "YouTube Music",
                            style = MaterialTheme.typography.labelSmall,
                            color = CreamMuted,
                        )
                    }
                    if (track?.isLocal == true) {
                        IconButton(onClick = onToggleFavorite, enabled = interactiveEnabled) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (isFavorite) Gold else CreamMuted,
                                modifier = Modifier.size(25.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(maxArtDp)
                            .onGloballyPositioned { coords ->
                                val root = sheetCoords
                                if (root != null && root.isAttached && coords.isAttached) {
                                    centerArtRect = root.localBoundingBoxOf(coords, clipBounds = false)
                                    onFullArtSlotMeasured(coords)
                                }
                            }
                            .then(
                                if (lyricsProgress < 0.5f) {
                                    Modifier.semantics { contentDescription = "Artwork. Tap to see the song in your library or swipe up for lyrics" }
                                } else {
                                    Modifier.clearAndSetSemantics { }
                                },
                            )
                            // Tapping the artwork takes you to the library with the
                            // song highlighted; lyrics open with an upward swipe.
                            .clickable(enabled = interactiveEnabled, onClick = onShowInLibrary),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val p = sheetProgress.coerceIn(0f, 1f)
                                val lp = lyricsProgress.coerceIn(0f, 1f)
                                val full = fullArtSlotRectProvider()
                                val current = lerpRect(miniArtRectProvider(), full, p)
                                translationY = current.bottom - full.bottom - 30.dp.toPx() * lp
                                translationX = -20.dp.toPx() * lp
                                alpha = ((p - 0.10f) / 0.72f).coerceIn(0f, 1f) * (1f - lp * 1.4f).coerceIn(0f, 1f)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = track?.title ?: "Your music starts here",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Cream,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = track?.let { "${it.effectiveArtist} — ${it.album}" }
                                ?: if (isScanning) "Scanning your library…" else "Pick a song from Library or Search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CreamMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(5.dp))
                        if (track != null) {
                            Surface(
                                color = InkSoft,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Gold.copy(alpha = 0.42f)),
                                modifier = Modifier.clickable(enabled = interactiveEnabled, onClick = onOpenSignalPath),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (output.isDac) Icons.Rounded.Usb else Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = Gold,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${track.qualityLabel} · ${output.name}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Gold,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else if (!hasPermission) {
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Allow audio access") }
                        }
                        Spacer(Modifier.height(10.dp))
                        KukosinWaveformSlider(
                            progress = if (duration > 0) position.toFloat() / duration else 0f,
                            onSeek = { fraction ->
                                if (interactiveEnabled) {
                                    val target = (fraction * duration).toLong()
                                    // Un solo seekTo por gesto (el slider ya avisa al soltar).
                                    scrubbing = false
                                    position = target
                                    controller?.seekTo(target)
                                }
                            },
                            onScrub = { fraction ->
                                scrubbing = true
                                position = (fraction * duration).toLong()
                            },
                            waveHeight = waveformHeight,
                            waveform = if (track == null) flatWaveform else realWaveform,
                            trackKey = track?.uri?.toString(),
                            isLoading = waveformLoading,
                            isPlaying = isPlaying,
                            enabled = controller != null && track != null && interactiveEnabled,
                            onPlayPause = {
                                if (interactiveEnabled && controller != null) {
                                    when {
                                        controller.mediaItemCount == 0 -> onFallbackPlayFirst()
                                        controller.isPlaying -> controller.pause()
                                        else -> controller.play()
                                    }
                                }
                            },
                            onPrev = { if (interactiveEnabled) controller?.seekToPrevious() },
                            onNext = { if (interactiveEnabled) controller?.seekToNext() },
                            onShuffle = { if (interactiveEnabled && controller != null) QueueManager.toggleShuffle(controller) },
                            onRepeat = {
                                if (interactiveEnabled && controller != null) {
                                    controller.repeatMode = when (controller.repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                }
                            },
                            positionLabel = formatTime(position),
                            durationLabel = formatTime(duration),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .then(if (lyricsProgress > 0.01f) headerDrag else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .onGloballyPositioned { coords ->
                                    val root = sheetCoords
                                    if (root != null && root.isAttached && coords.isAttached) {
                                        cornerArtRect = root.localBoundingBoxOf(coords, clipBounds = false)
                                        onLyricsArtSlotMeasured(coords)
                                    }
                                }
                                .then(
                                    if (lyricsProgress > 0.01f) {
                                        Modifier
                                            .semantics { contentDescription = "Artwork. Tap to close lyrics" }
                                            .clickable(onClick = onCloseLyrics)
                                    } else Modifier,
                                ),
                        )
                        if (lyricsProgress > 0.01f) {
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        alpha = ((lyricsProgress - 0.08f) / 0.62f).coerceIn(0f, 1f) *
                                            ((sheetProgress - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                        translationX = (1f - lyricsProgress) * 18.dp.toPx()
                                    },
                            ) {
                                Text(track?.title ?: "No song selected", style = MaterialTheme.typography.titleMedium, color = Cream, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track?.effectiveArtist ?: "Your music", style = MaterialTheme.typography.bodySmall, color = CreamMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track?.qualityLabel ?: "Lyrics", style = MaterialTheme.typography.labelSmall, color = Gold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = onCloseLyrics, enabled = interactiveEnabled) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Close lyrics", tint = Gold)
                            }
                        }
                    }
                    if (lyricsProgress > 0.01f) {
                        LyricsPanel(
                            lyrics = currentLyrics,
                            positionMs = position,
                            isPlaying = isPlaying,
                            onLineClick = if (controller != null) { timeMs -> controller.seekTo(timeMs) } else null,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val lp = lyricsProgress.coerceIn(0f, 1f)
                                    alpha = ((lp - 0.08f) / 0.72f).coerceIn(0f, 1f) *
                                        ((sheetProgress - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                    translationY = (1f - lp) * 72.dp.toPx()
                                },
                        )
                    }
                }
            }

            PlayerShortcutBar(
                enabled = interactiveEnabled,
                onOpenLibrary = onOpenLibraryShortcut,
                onOpenEqualizer = onOpenEqualizerShortcut,
                onOpenSearch = onOpenSearchShortcut,
                onOpenSettings = onOpenSettingsShortcut,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 8.dp)
                    .graphicsLayer {
                        val p = sheetProgress.coerceIn(0f, 1f)
                        val full = fullArtSlotRectProvider()
                        val current = lerpRect(miniArtRectProvider(), full, p)
                        translationY = current.bottom - full.bottom
                        alpha = ((p - 0.25f) / 0.65f).coerceIn(0f, 1f)
                    },
            )
        }

        if (sheetProgress >= 0.998f && centerArtRect != Rect.Zero) {
            val lp = lyricsProgress.coerceIn(0f, 1f)
            val step = lerpFloat(artworkWidthPx * playPauseScale, cornerArtWidthPx, lp).coerceAtLeast(1f)
            val previous = dragX > 0f
            val showNeighbor = dragX != 0f && if (previous) hasPrevious else hasNext
            if (showNeighbor) {
                PlayerMorphingArtwork(
                    track = null,
                    artworkOverride = if (previous) previousArt else nextArt,
                    centerRect = centerArtRect,
                    cornerRect = cornerArtRect,
                    lyricsProgress = lp,
                    baseArtDp = maxArtDp,
                    playPauseScale = playPauseScale,
                    offsetX = if (previous) dragX - step else dragX + step,
                    alpha = (abs(dragX) / step).coerceIn(0f, 1f),
                    blur = 0.dp,
                )
            }
            PlayerMorphingArtwork(
                track = track,
                centerRect = centerArtRect,
                cornerRect = cornerArtRect,
                lyricsProgress = lp,
                baseArtDp = maxArtDp,
                playPauseScale = playPauseScale,
                offsetX = dragX,
                alpha = artworkAlpha,
                blur = artworkBlur,
            )
        }
    }
}

/**
 * Carátula con **fundido cruzado** entre pistas.
 *
 * Antes, al cambiar de canción el `AsyncImage` cambiaba de modelo y la carátula se
 * reemplazaba de golpe (con un hueco en el medio mientras Coil resolvía la nueva). Ahora se
 * mantiene la imagen anterior visible hasta que la nueva está lista y se cruzan con un
 * `Crossfade` de [Motion.ARTWORK_MS]: el cambio de pista se siente continuo aunque la
 * imagen tarde en llegar.
 *
 * Detalles que sí se notan en una lista:
 *  - La imagen anterior se guarda en un `remember`, así no hace falta que Coil la vuelva a
 *    decodificar para el fundido.
 *  - `contentDescription` sigue al álbum actual (el viejo queda como decoración durante el
 *    cruce, no se anuncia).
 *  - Sin carátula (ni vieja ni nueva) queda el fondo con la nota musical, como antes.
 */
@Composable
internal fun Artwork(
    track: Track?,
    modifier: Modifier = Modifier,
    artworkUri: Uri? = null,
    cornerRadius: Dp = 12.dp,
) {
    // Se recuerda la última carátula válida: durante el cambio de pista se sigue viendo
    // ésta mientras Coil resuelve la próxima. La escritura va en un `SideEffect` y no en el
    // cuerpo de la composición: escribir estado mientras se compone obliga a Compose a
    // recomponer en el acto (y con la carátula cayendo en cada cambio de pista eso se nota).
    var lastImage by remember { mutableStateOf<Uri?>(null) }
    val image = artworkUri ?: track?.artworkUri
    SideEffect {
        if (image != null && image != lastImage) lastImage = image
    }
    val target = image ?: lastImage

    // Cuando una carátula reemplaza a otra (elegir una canción), no entra de golpe:
    // además del fundido cruzado se asienta desde 96 % hasta su tamaño final. Eso es
    // lo que hace que "se vaya posicionando" en su slot. Sólo se anima el reemplazo,
    // no la primera aparición, para no castigar el scroll de las listas.
    val settle = remember { Animatable(1f) }
    var lastSettled by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(target) {
        val previous = lastSettled
        lastSettled = target
        if (target != null && previous != null && previous != target) {
            settle.snapTo(0.96f)
            settle.animateTo(1f, Motion.enter(Motion.ARTWORK_MS))
        }
    }

    Box(
        modifier = modifier
            .then(if (cornerRadius > 0.dp) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier)
            .background(InkSoft)
            .graphicsLayer {
                scaleX = settle.value
                scaleY = settle.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = CreamMuted)
        if (target != null) {
            Crossfade(
                targetState = target,
                animationSpec = Motion.enter(Motion.ARTWORK_MS),
                label = "artworkCrossfade",
            ) { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = if (uri == image) track?.album else null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
internal fun SharedTransitionArtwork(
    track: Track?,
    sheetProgress: Float,
    lyricsProgress: Float,
    isSheetDragging: Boolean,
    wasSheetDragged: Boolean,
    baseArtDp: Dp,
    miniArtRectProvider: () -> Rect,
    fullArtSlotRectProvider: () -> Rect,
) {
    val p = sheetProgress.coerceIn(0f, 1f)
    // 1.0 = "Soft"; el ajuste escala este blur (0 = Off, 1.7 = Strong).
    val blurScale = LocalUiSettings.current.blur.artworkTransition.value / 18f
    // El radio NO se recalcula con el progreso (que cambia en cada frame): recrear un
    // RenderEffect distinto por frame es una de las operaciones más caras de esta
    // transición. Se elige un valor por estado y `animateDpAsState` hace el cruce una
    // sola vez, cuando cambia el estado.
    val targetBlurVal = when {
        p <= 0.002f || p >= 0.998f -> 0f
        isSheetDragging -> 14f * blurScale
        wasSheetDragged -> 9f * blurScale
        else -> 5f * blurScale
    }
    val transitBlur by animateDpAsState(
        targetValue = targetBlurVal.dp,
        animationSpec = Motion.quick(140),
        label = "sharedArtworkTransitBlur",
    )

    // El tamaño base coincide con el slot real: también cabe en pantallas estrechas,
    // y se escala/traslada en GPU sin re-layout durante cada frame.
    Box(
        modifier = Modifier
            .size(baseArtDp)
            .graphicsLayer {
                val prog = sheetProgress.coerceIn(0f, 1f)
                val miniR = miniArtRectProvider()
                val fullR = fullArtSlotRectProvider()
                val curR = lerpRect(miniR, fullR, prog)
                val basePx = baseArtDp.toPx().coerceAtLeast(1f)
                val sx = (curR.width / basePx).coerceAtLeast(0.05f)
                val sy = (curR.height / basePx).coerceAtLeast(0.05f)
                translationX = curR.left
                translationY = curR.top
                scaleX = sx
                scaleY = sy
                transformOrigin = TransformOrigin(0f, 0f)
                val fullCorner = 20.dp - 8.dp * lyricsProgress.coerceIn(0f, 1f)
                val desiredCornerDp = 12.dp + (fullCorner - 12.dp) * prog
                shape = RoundedCornerShape(desiredCornerDp / sx)
                clip = true
                shadowElevation = (4.dp + 20.dp * sin(prog * PI).toFloat()).toPx()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (transitBlur > 0.2.dp) {
                        Modifier.blur(transitBlur, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Artwork(
                track = track,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
            )
        }
    }
}

@Composable
internal fun PlayerMorphingArtwork(
    track: Track?,
    centerRect: Rect,
    cornerRect: Rect,
    lyricsProgress: Float,
    baseArtDp: Dp,
    playPauseScale: Float,
    offsetX: Float,
    alpha: Float,
    blur: Dp,
    artworkOverride: Uri? = null,
) {
    Box(
        modifier = Modifier
            .size(baseArtDp)
            .graphicsLayer {
                val lp = lyricsProgress.coerceIn(0f, 1f)
                val center = scaleRectAroundCenter(centerRect, playPauseScale)
                val corner = if (cornerRect == Rect.Zero) center else cornerRect
                val destination = lerpRect(center, corner, lp)
                val basePx = baseArtDp.toPx().coerceAtLeast(1f)
                val sx = (destination.width / basePx).coerceAtLeast(0.01f)
                val sy = (destination.height / basePx).coerceAtLeast(0.01f)
                translationX = destination.left + offsetX
                translationY = destination.top
                scaleX = sx
                scaleY = sy
                transformOrigin = TransformOrigin(0f, 0f)
                shape = RoundedCornerShape((20.dp - 8.dp * lp) / sx)
                clip = true
                this.alpha = alpha.coerceIn(0f, 1f)
            },
    ) {
        Artwork(
            track = track,
            artworkUri = artworkOverride,
            cornerRadius = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blur > 0.2.dp) Modifier.blur(blur, edgeTreatment = BlurredEdgeTreatment.Rectangle) else Modifier),
        )
    }
}

internal fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

internal fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect = Rect(
    left = lerpFloat(start.left, stop.left, fraction),
    top = lerpFloat(start.top, stop.top, fraction),
    right = lerpFloat(start.right, stop.right, fraction),
    bottom = lerpFloat(start.bottom, stop.bottom, fraction),
)

internal fun scaleRectAroundCenter(rect: Rect, scale: Float): Rect {
    val cx = rect.center.x
    val cy = rect.center.y
    val hw = rect.width * scale * 0.5f
    val hh = rect.height * scale * 0.5f
    return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
}
