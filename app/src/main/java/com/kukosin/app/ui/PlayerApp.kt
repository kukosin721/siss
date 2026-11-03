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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun PlayerApp(viewModel: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val library by viewModel.uiState.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    // Estilo Poweramp: el reproductor siempre es la pantalla principal al iniciar.
    var showNowPlaying by remember { mutableStateOf(true) }
    var activeSection by remember { mutableStateOf(AppSection.LIBRARY) }
    var showSignalPath by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var userMessage by remember { mutableStateOf<String?>(null) }
    // Pista a la que hay que llevar la biblioteca después de tocar la carátula.
    var libraryFocusUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // Dock (mini reproductor) retráctil: se esconde al bajar en la lista y vuelve al subir.
    var dockHidden by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        if (userMessage != null) {
            delay(3000)
            userMessage = null
        }
    }

    // Acumula el scroll del usuario para decidir si el dock se esconde. Umbral alto
    // para abajo (no querés que se vaya por un toquecito) y bajo para arriba (volver
    // tiene que ser inmediato).
    val dockScroll = remember {
        var acc = 0f
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                acc = (acc + available.y).coerceIn(-400f, 400f)
                if (acc > 140f) {
                    dockHidden = true
                    acc = 0f
                } else if (acc < -50f) {
                    dockHidden = false
                    acc = 0f
                }
                return Offset.Zero
            }
        }
    }

    // Al cambiar de sección el dock siempre vuelve.
    LaunchedEffect(activeSection) { dockHidden = false }

    // Indexado de letras en segundo plano: se dispara una sola vez por sesión y sólo
    // toca los archivos que todavía no están indexados. Es lo que permite que la
    // búsqueda por letra responda al instante en vez de abrir 10.000 archivos.
    LaunchedEffect(library.hasScanned, library.tracks.size) {
        if (library.tracks.isNotEmpty()) viewModel.indexLyrics()
    }

    val dockOffset by animateDpAsState(
        targetValue = if (dockHidden) 132.dp else 0.dp,
        animationSpec = Motion.enter(Motion.EXIT_MS + 20),
        label = "dockOffset",
    )
    val dockAlpha by animateFloatAsState(
        targetValue = if (dockHidden) 0f else 1f,
        animationSpec = Motion.leave(),
        label = "dockAlpha",
    )

    // El resaltado de la fila dura un par de segundos y se apaga solo.
    LaunchedEffect(libraryFocusUri) {
        if (libraryFocusUri != null) {
            delay(1900)
            libraryFocusUri = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermission = grants.entries.any { it.key != Manifest.permission.POST_NOTIFICATIONS && it.value }
        if (hasPermission) viewModel.refresh()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && !library.hasScanned && !library.isScanning) {
            viewModel.refresh()
        }
    }

    // El servicio reconstruye la MediaSession cuando cambia un ajuste de audio (el
    // reproductor nuevo necesita una sesión nueva). Al liberar la sesión vieja, el
    // MediaController de la UI recibe `onDisconnected`; antes nadie lo escuchaba, así
    // que el control quedaba muerto hasta reiniciar la app: la pantalla seguía
    // mostrando el reproductor pero ningún botón respondía. Reconectar acá arregla
    // ese bloqueo sin tocar la reproducción.
    var controllerGeneration by remember { mutableIntStateOf(0) }

    DisposableEffect(controllerGeneration) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        var disposed = false
        val listener = object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (disposed) return
                controllerGeneration++
            }
        }
        val future = MediaController.Builder(context, token).setListener(listener).buildAsync()
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    runCatching { controller = future.get() }
                }
            },
            executor,
        )
        onDispose {
            disposed = true
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    DisposableEffect(controller) {
        val player = controller
        if (player != null) QueueManager.attach(player)
        onDispose { if (player != null) QueueManager.detach(player) }
    }

    val playback = rememberPlaybackState(controller, library.tracks)

    /** videoId de YouTube Music que está sonando, para pintar la fila activa en la pestaña YTM. */
    val playingYtmId = playback.current?.uri
        ?.takeIf { it.scheme == YtmService.STREAM_SCHEME }
        ?.lastPathSegment
    val output by AudioRoute.current.collectAsStateWithLifecycle()

    // El ecualizador tiene una curva por tipo de salida: se cambia solo cuando cambia
    // la salida (altavoz / jack / DAC USB).
    LaunchedEffect(output.isBuiltin, output.isUsb, output.isDac) {
        com.kukosin.app.eq.EqManager.setProfile(
            com.kukosin.app.eq.EqProfile.forDevice(
                isBuiltin = output.isBuiltin,
                isUsbOrDac = output.isUsb || output.isDac,
            ),
        )
    }
    val analysis by viewModel.analysisState.collectAsStateWithLifecycle()
    val audioConfig by viewModel.audioConfig.collectAsStateWithLifecycle()
    val uiSettings by UiSettingsManager.settings.collectAsStateWithLifecycle()
    val routeReady by AudioRoute.isReady.collectAsStateWithLifecycle()
    // Sin DAC conectado los modos exclusivos quedan bloqueados; si había uno guardado
    // se vuelve al modo automático de Oboe (no al mezclador): la reproducción sigue
    // pasando por el motor, sólo pierde la exclusividad.
    // Se espera a que AudioRoute haya leído los dispositivos reales; si no, el
    // valor inicial (sin DAC) borraría el modo de un DAC recién conectado.
    LaunchedEffect(output.isDac, routeReady) {
        if (routeReady && !output.isDac && audioConfig.outputMode.requiresDac) {
            viewModel.setOutputMode(OutputMode.AUTO)
        }
    }
    // El estado del motor refleja el modo elegido incluso cuando no hay stream
    // abierto (modo Disabled), para que los paneles de estado no mientan.
    LaunchedEffect(audioConfig.outputMode) {
        AudioEngineStatus.setMode(audioConfig.outputMode)
    }
    var showSettings by remember { mutableStateOf(false) }
    val settingsVisibility = remember { MutableTransitionState(false) }
    settingsVisibility.targetState = showSettings
    val settingsDialogVisible = settingsVisibility.currentState || settingsVisibility.targetState || !settingsVisibility.isIdle

    val density = LocalDensity.current
    val playerSheetScope = rememberCoroutineScope()
    // Estilo Poweramp: el reproductor siempre es la pantalla principal (1f = reproductor, 0f = sección activa).
    var sheetProgress by remember { mutableFloatStateOf(1f) }
    var playerLyricsProgress by remember { mutableFloatStateOf(0f) }
    var isSheetDragging by remember { mutableStateOf(false) }
    var wasSheetDragged by remember { mutableStateOf(false) }
    var sheetAnimJob by remember { mutableStateOf<Job?>(null) }
    var lyricsAnimJob by remember { mutableStateOf<Job?>(null) }
    val favoriteUris by LibraryInsights.favorites.collectAsStateWithLifecycle()

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var miniArtRestingRect by remember { mutableStateOf(Rect.Zero) }
    var fullArtSlotRect by remember { mutableStateOf(Rect.Zero) }
    var lyricsArtSlotRect by remember { mutableStateOf(Rect.Zero) }

    val playPauseScale by animateFloatAsState(
        targetValue = if (playback.isPlaying) 1f else 0.92f,
        animationSpec = Motion.gentle(),
        label = "artworkPlayPauseScale",
    )

    fun animatePlayerSheet(target: Float, initialVelocity: Float = 0f, fromDrag: Boolean = false) {
        sheetAnimJob?.cancel()
        isSheetDragging = false
        wasSheetDragged = fromDrag
        showNowPlaying = target > 0.5f
        val from = sheetProgress
        if (abs(from - target) < 0.0005f) {
            sheetProgress = target
            wasSheetDragged = false
            return
        }
        sheetAnimJob = playerSheetScope.launch {
            animate(
                initialValue = from,
                targetValue = target,
                initialVelocity = initialVelocity.coerceIn(-8f, 8f),
                animationSpec = Motion.snappy(stiffness = 380f, dampingRatio = 0.90f),
            ) { value, _ ->
                sheetProgress = value.coerceIn(0f, 1f)
            }
            sheetProgress = target
            wasSheetDragged = false
        }
    }

    fun animateLyrics(target: Float, initialVelocity: Float = 0f) {
        lyricsAnimJob?.cancel()
        lyricsAnimJob = playerSheetScope.launch {
            animate(
                initialValue = playerLyricsProgress,
                targetValue = target,
                initialVelocity = initialVelocity.coerceIn(-8f, 8f),
                animationSpec = Motion.snappy(stiffness = 320f, dampingRatio = 0.88f),
            ) { value, _ -> playerLyricsProgress = value.coerceIn(0f, 1f) }
            playerLyricsProgress = target
        }
    }

    // Al escanear la biblioteca, prepara el primer tema en pausa si la cola estaba vacía,
    // de modo que el reproductor principal tenga carátula y onda listas al instante.
    LaunchedEffect(controller, library.tracks.size) {
        val player = controller
        if (player != null && library.tracks.isNotEmpty() && player.mediaItemCount == 0) {
            QueueManager.cueTracksIfEmpty(player, library.tracks)
        }
    }

    val dragRangePx = (rootSizePx.height * 0.68f).takeIf { it > 240f }
        ?: with(density) { 520.dp.toPx() }
    val currentDragRangePx by rememberUpdatedState(dragRangePx)
    val lyricsDragRangePx = with(density) { 200.dp.toPx() }

    // Reservar espacio a la onda ampliada sin sacar los controles de la pantalla.
    val waveformHeight = if (rootSizePx.height > 0 && with(density) { rootSizePx.height.toDp() } < 720.dp) {
        116.dp
    } else {
        136.dp
    }
    val maxArtDp = if (rootSizePx.height > 0 && rootSizePx.width > 0) {
        with(density) {
            minOf(
                320.dp.toPx(),
                (rootSizePx.width - 48.dp.toPx()).coerceAtLeast(1.dp.toPx()),
                (rootSizePx.height - (waveformHeight + 405.dp).toPx()).coerceAtLeast(120.dp.toPx()),
            ).toDp()
        }
    } else {
        304.dp
    }

    val resolveMiniArtRect: () -> Rect = {
        if (miniArtRestingRect != Rect.Zero) {
            miniArtRestingRect
        } else {
            val sizePx = with(density) { 48.dp.toPx() }
            val left = with(density) { 28.dp.toPx() }
            val bottom = (rootSizePx.height.takeIf { it > 0 } ?: 1800) - with(density) { 36.dp.toPx() }
            Rect(left, bottom - sizePx, left + sizePx, bottom)
        }
    }

    val resolveFullArtSlotRect: () -> Rect = {
        val baseFull = if (fullArtSlotRect != Rect.Zero) {
            fullArtSlotRect
        } else {
            val artPx = with(density) { maxArtDp.toPx() }
            val w = (rootSizePx.width.takeIf { it > 0 } ?: 1080).toFloat()
            val h = (rootSizePx.height.takeIf { it > 0 } ?: 2200).toFloat()
            val left = ((w - artPx) * 0.5f).coerceAtLeast(0f)
            val top = h * 0.12f
            Rect(left, top, left + artPx, top + artPx)
        }
        val scaledFull = scaleRectAroundCenter(baseFull, playPauseScale)
        val lp = playerLyricsProgress.coerceIn(0f, 1f)
        if (lp > 0.001f && lyricsArtSlotRect != Rect.Zero) {
            lerpRect(scaledFull, lyricsArtSlotRect, lp)
        } else {
            scaledFull
        }
    }

    // Como el reproductor es la pantalla principal, Atrás en Biblioteca/Ecualizador/Búsqueda
    // retrocede en subcategorías o vuelve con animación al reproductor principal.
    val modalOpen = showQueueSheet || showSignalPath || settingsDialogVisible ||
        library.selectedAlbum != null || library.selectedArtist != null ||
        library.selectedTagGroup != null || library.inspectTrack != null
    BackHandler(enabled = !modalOpen && (!showNowPlaying || sheetProgress < 0.999f)) {
        if (sheetProgress > 0.01f) {
            animatePlayerSheet(target = 1f, fromDrag = false)
        } else if (activeSection == AppSection.LIBRARY) {
            when {
                library.activeCategory == LibraryCategory.CARPETAS && viewModel.navigateUpFolder() -> Unit
                library.activeCategory != null -> viewModel.selectCategory(null)
                else -> animatePlayerSheet(target = 1f, fromDrag = false)
            }
        } else {
            animatePlayerSheet(target = 1f, fromDrag = false)
        }
    }

    val sheetController = controller
    val sheetTrack = playback.current ?: library.tracks.firstOrNull()

    LaunchedEffect(sheetTrack?.uri, library.inspectTrack?.uri, audioConfig.autoAnalyze) {
        val currentTrack = sheetTrack
        if (library.inspectTrack == null && currentTrack != null && currentTrack.isLocal) {
            viewModel.prepareMetadata(currentTrack)
            if (audioConfig.autoAnalyze) {
                viewModel.analyzeSpectral(currentTrack)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                rootCoords = coords
                rootSizePx = coords.size
            }
            .background(
                Brush.verticalGradient(
                    listOf(Ink, InkElevated, Ink),
                ),
            ),
    ) {
        val bottomPadding = 108

        // La sección entra al minimizar y sale al volver, ligada al mismo progreso
        // del morph para que nunca haya un corte entre ambas pantallas.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Sólo la biblioteca retrae el dock: en Ajustes o en el EQ esconder el
                // mini reproductor al scrollear sólo molesta.
                .then(if (activeSection == AppSection.LIBRARY) Modifier.nestedScroll(dockScroll) else Modifier)
                .graphicsLayer {
                    val reveal = (1f - sheetProgress).coerceIn(0f, 1f)
                    alpha = reveal
                    translationY = (1f - reveal) * 28.dp.toPx()
                    val scale = 0.975f + 0.025f * reveal
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            SectionHost(section = activeSection) { section ->
                when (section) {
                    AppSection.LIBRARY -> PowerampLibraryWorkspace(
                        library = library,
                        hasPermission = hasPermission,
                        output = output,
                        currentUri = playback.current?.uri,
                        isPlaying = playback.isPlaying,
                        controller = controller,
                        bottomPadding = bottomPadding,
                        onBackToPlayer = { animatePlayerSheet(target = 1f, fromDrag = false) },
                        onSelectCategory = viewModel::selectCategory,
                        onQueryChange = viewModel::onQueryChange,
                        onRequestPermission = { permissionLauncher.launch(audioPermissions()) },
                        onRescan = {
                            if (hasPermission) viewModel.rescan() else permissionLauncher.launch(audioPermissions())
                        },
                        onOpenSignalPath = { showSignalPath = true },
                        onOpenQueueSheet = { showQueueSheet = true },
                        onTrackClick = { tracks, index ->
                            playAndOpen(controller, tracks, index) {
                                animatePlayerSheet(target = 1f, fromDrag = false)
                            }
                        },
                        onAlbumClick = viewModel::selectAlbum,
                        onArtistClick = viewModel::selectArtist,
                        onTagGroupClick = viewModel::selectTagGroup,
                        onFolderClick = viewModel::navigateToFolder,
                        onNavigateUpFolder = { viewModel.navigateUpFolder() },
                        onInspectTrack = viewModel::setInspectTrack,
                        onMessage = { msg -> userMessage = msg },
                        onSortChange = viewModel::setSortMode,
                        focusUri = libraryFocusUri,
                    )

                    AppSection.EQUALIZER -> EqualizerWorkspace(
                        bottomPadding = bottomPadding,
                        onBackToPlayer = { animatePlayerSheet(target = 1f, fromDrag = false) },
                    )

                    AppSection.SEARCH -> PowerampSearchWorkspace(
                        library = library,
                        hasPermission = hasPermission,
                        currentUri = playback.current?.uri,
                        playingYtmId = playingYtmId,
                        isPlaying = playback.isPlaying,
                        playbackError = playback.error,
                        controller = controller,
                        bottomPadding = bottomPadding,
                        onBackToPlayer = { animatePlayerSheet(target = 1f, fromDrag = false) },
                        onQueryChange = viewModel::onQueryChange,
                        onYtmQueryChange = viewModel::onYtmQueryChange,
                        onSearchYtm = { viewModel.searchYtm() },
                        onToggleLyrics = viewModel::toggleLyricsOnly,
                        onRequestPermission = { permissionLauncher.launch(audioPermissions()) },
                        onLocalTrackClick = { tracks, index -> playTrackList(controller, tracks, index) },
                        onAlbumClick = viewModel::selectAlbum,
                        onArtistClick = viewModel::selectArtist,
                        onInspectTrack = viewModel::setInspectTrack,
                        onYtmTrackClick = { ytmTrack ->
                            controller?.let { player ->
                                val queue = library.ytmTracks.map(YtmService::toMediaItem)
                                val startIndex = library.ytmTracks
                                    .indexOfFirst { it.id == ytmTrack.id }
                                    .coerceAtLeast(0)
                                QueueManager.playItems(player, queue, startIndex)
                                animatePlayerSheet(target = 1f, fromDrag = false)
                            }
                        },
                        onMessage = { msg -> userMessage = msg },
                    )
                }
            }
        }

        MiniPlayer(
            track = sheetTrack,
            isPlaying = playback.isPlaying,
            progress = playback.progress,
            durationMs = playback.durationMs,
            onSeek = { fraction ->
                val player = controller
                val total = player?.duration ?: 0L
                if (player != null && total > 0L) {
                    player.seekTo((total * fraction.coerceIn(0f, 1f)).toLong())
                }
            },
            isDac = output.isDac,
            sheetProgress = sheetProgress,
            onToggle = {
                val player = controller
                if (player != null) {
                    if (player.mediaItemCount == 0 && library.tracks.isNotEmpty()) {
                        playTrackList(player, library.tracks, 0)
                    } else if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }
            },
            onNext = { controller?.seekToNext() },
            onOpen = { animatePlayerSheet(target = 1f, fromDrag = false) },
            onOpenSignalPath = { showSignalPath = true },
            onArtMeasured = { artCoords ->
                val root = rootCoords
                if (root != null && root.isAttached && artCoords.isAttached) {
                    miniArtRestingRect = root.localBoundingBoxOf(artCoords, clipBounds = false)
                }
            },
            onVerticalDragStart = {
                sheetAnimJob?.cancel()
                isSheetDragging = true
                wasSheetDragged = true
            },
            onVerticalDragDelta = { dragAmountPx ->
                val range = currentDragRangePx
                val next = (sheetProgress - dragAmountPx / range).coerceIn(0f, 1f)
                sheetProgress = next
                if (next > 0.01f) showNowPlaying = true
            },
            onVerticalDragEnd = { velocityYPxPerSec ->
                val range = currentDragRangePx
                val progressVel = -velocityYPxPerSec / range
                val shouldOpen = velocityYPxPerSec < -520f ||
                    (sheetProgress > 0.24f && velocityYPxPerSec < 420f)
                animatePlayerSheet(
                    target = if (shouldOpen) 1f else 0f,
                    initialVelocity = progressVel,
                    fromDrag = true,
                )
            },
            onVerticalDragCancel = {
                animatePlayerSheet(
                    target = if (sheetProgress > 0.5f) 1f else 0f,
                    fromDrag = true,
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, dockOffset.roundToPx()) }
                .graphicsLayer { alpha = dockAlpha }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // Scrim progresivo sobre la sección secundaria mientras el reproductor está abierto o en tránsito.
        if (sheetProgress > 0.001f) {
            val blockBackgroundTouches = showNowPlaying || sheetProgress > 0.08f || isSheetDragging
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (sheetProgress.coerceIn(0f, 1f) * 0.68f).coerceIn(0f, 0.68f)
                    }
                    .background(Color.Black)
                    .then(
                        if (blockBackgroundTouches) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures {
                                    animatePlayerSheet(target = 0f, fromDrag = false)
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        if (showNowPlaying || sheetProgress > 0.001f || isSheetDragging) {
            NowPlayingSheet(
                track = sheetTrack,
                controller = sheetController,
                isPlaying = playback.isPlaying,
                hasPermission = hasPermission,
                isScanning = library.isScanning,
                output = output,
                analysis = analysis,
                sheetProgress = sheetProgress,
                lyricsProgress = playerLyricsProgress,
                allowLyricsBack = !modalOpen,
                isFavorite = sheetTrack?.uri?.toString() in favoriteUris,
                onToggleFavorite = {
                    sheetTrack?.takeIf { it.isLocal }?.let { LibraryInsights.toggleFavorite(it.uri.toString()) }
                },
                interactiveEnabled = showNowPlaying || sheetProgress > 0.08f || isSheetDragging,
                maxArtDp = maxArtDp,
                waveformHeight = waveformHeight,
                playPauseScale = playPauseScale,
                miniArtRectProvider = resolveMiniArtRect,
                fullArtSlotRectProvider = resolveFullArtSlotRect,
                onFullArtSlotMeasured = { artCoords ->
                    val root = rootCoords
                    if (root != null && root.isAttached && artCoords.isAttached) {
                        fullArtSlotRect = root.localBoundingBoxOf(artCoords, clipBounds = false)
                    }
                },
                onLyricsArtSlotMeasured = { artCoords ->
                    val root = rootCoords
                    if (root != null && root.isAttached && artCoords.isAttached) {
                        lyricsArtSlotRect = root.localBoundingBoxOf(artCoords, clipBounds = false)
                    }
                },
                onLyricsDragStart = { lyricsAnimJob?.cancel() },
                onLyricsDragDelta = { amount ->
                    playerLyricsProgress = (playerLyricsProgress - amount / lyricsDragRangePx).coerceIn(0f, 1f)
                },
                onLyricsDragEnd = { velocity ->
                    val open = shouldOpenLyrics(playerLyricsProgress, velocity)
                    animateLyrics(if (open) 1f else 0f, -velocity / lyricsDragRangePx)
                },
                onLyricsDragCancel = {
                    animateLyrics(if (playerLyricsProgress > 0.5f) 1f else 0f)
                },
                onShowInLibrary = {
                    val uri = playback.current?.uri
                    if (uri != null) {
                        animateLyrics(0f)
                        animatePlayerSheet(target = 0f, fromDrag = false)
                        viewModel.selectCategory(LibraryCategory.TODAS_LAS_CANCIONES)
                        activeSection = AppSection.LIBRARY
                        libraryFocusUri = uri
                    } else {
                        userMessage = "No song is playing yet"
                    }
                },
                onCloseLyrics = { animateLyrics(0f) },
                onRequestPermission = { permissionLauncher.launch(audioPermissions()) },
                onFallbackPlayFirst = {
                    if (library.tracks.isNotEmpty()) {
                        playTrackList(controller, library.tracks, 0)
                    }
                },
                onVerticalDragStart = {
                    sheetAnimJob?.cancel()
                    isSheetDragging = true
                    wasSheetDragged = true
                },
                onVerticalDragDelta = { dragAmountPx ->
                    val range = currentDragRangePx
                    sheetProgress = (sheetProgress - dragAmountPx / range).coerceIn(0f, 1f)
                },
                onVerticalDragEnd = { velocityYPxPerSec ->
                    val range = currentDragRangePx
                    val progressVel = -velocityYPxPerSec / range
                    val shouldClose = velocityYPxPerSec > 520f ||
                        (sheetProgress < 0.74f && velocityYPxPerSec > -420f)
                    animatePlayerSheet(
                        target = if (shouldClose) 0f else 1f,
                        initialVelocity = progressVel,
                        fromDrag = true,
                    )
                },
                onVerticalDragCancel = {
                    animatePlayerSheet(
                        target = if (sheetProgress > 0.5f) 1f else 0f,
                        fromDrag = true,
                    )
                },
                onDismiss = { animatePlayerSheet(target = 0f, fromDrag = false) },
                onOpenSignalPath = { showSignalPath = true },
                onOpenLibraryShortcut = {
                    hapticTick()
                    viewModel.openLibraryRoot()
                    activeSection = AppSection.LIBRARY
                    animatePlayerSheet(target = 0f, fromDrag = false)
                },
                onOpenEqualizerShortcut = {
                    hapticTick()
                    activeSection = AppSection.EQUALIZER
                    animatePlayerSheet(target = 0f, fromDrag = false)
                },
                onOpenSearchShortcut = {
                    hapticTick()
                    activeSection = AppSection.SEARCH
                    animatePlayerSheet(target = 0f, fromDrag = false)
                },
                onOpenSettingsShortcut = {
                    hapticTick()
                    showSettings = true
                },
            )
        }

        // Carátula compartida en vuelo diagonal (MiniPlayer 48dp izq ↔ Reproductor grande)
        if (sheetProgress > 0.001f && sheetProgress < 0.998f) {
            SharedTransitionArtwork(
                track = sheetTrack,
                sheetProgress = sheetProgress,
                lyricsProgress = playerLyricsProgress,
                isSheetDragging = isSheetDragging,
                wasSheetDragged = wasSheetDragged,
                baseArtDp = maxArtDp,
                miniArtRectProvider = resolveMiniArtRect,
                fullArtSlotRectProvider = resolveFullArtSlotRect,
            )
        }

        AnimatedVisibility(
            visible = userMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (sheetProgress < 0.2f) 92.dp else 24.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                color = InkElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Gold.copy(alpha = 0.6f)),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        userMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cream,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        showQueueSheet = true
                        userMessage = null
                    }) {
                        Text("VIEW QUEUE", color = Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showQueueSheet && sheetController != null) {
        QueueSheet(
            controller = sheetController,
            tracks = library.tracks,
            audioConfig = audioConfig,
            onDismiss = { showQueueSheet = false },
            onMessage = { msg -> userMessage = msg },
        )
    }

    if (settingsDialogVisible) {
        FullScreenSettingsScreen(
            visibilityState = settingsVisibility,
            output = output,
            autoAnalyze = audioConfig.autoAnalyze,
            onToggleAutoAnalyze = viewModel::toggleAutoAnalyze,
            outputMode = audioConfig.outputMode,
            onSelectOutputMode = { mode ->
                // Doble candado: la UI ya deshabilita los modos exclusivos sin DAC,
                // pero el callback tampoco debe aceptarlos.
                if (!mode.requiresDac || output.isDac) {
                    viewModel.setOutputMode(mode)
                    if (mode.needsUsbPermission && !UsbDacManager.current.value.hasPermission) {
                        UsbDacManager.requestPermission(context)
                    }
                }
            },
            strictBitPerfect = audioConfig.strictBitPerfect,
            onToggleStrictBitPerfect = viewModel::toggleStrictBitPerfect,
            fades = audioConfig.fades,
            onToggleFades = { viewModel.setFades(!audioConfig.fades) },
            bufferMs = audioConfig.bufferMs,
            onSelectBufferMs = viewModel::setBufferMs,
            continuity = audioConfig.continuity,
            onSelectContinuity = viewModel::setContinuity,
            volumeCurve = audioConfig.volumeCurve,
            onSelectVolumeCurve = viewModel::setVolumeCurve,
            decoder = audioConfig.decoder,
            onSelectDecoder = { decoder -> viewModel.setDecoder(decoder) },
            usbPermissionGranted = UsbDacManager.current.value.hasPermission,
            onRequestUsbPermission = { UsbDacManager.requestPermission(context) },
            accentHue = AccentTheme.hue,
            onPreviewAccent = { AccentTheme.preview(it) },
            onSelectAccent = { AccentTheme.select(it) },
            onCommitAccent = { AccentTheme.commit() },
            onResetAccent = { AccentTheme.reset() },
            animationSpeed = uiSettings.animationSpeed,
            onSelectAnimationSpeed = { UiSettingsManager.setAnimationSpeed(it) },
            blurStrength = uiSettings.blurStrength,
            onSelectBlurStrength = { UiSettingsManager.setBlurStrength(it) },
            fontOption = uiSettings.fontOption,
            onSelectFontOption = { UiSettingsManager.setFontOption(it) },
            onRescan = {
                if (hasPermission) viewModel.rescan() else permissionLauncher.launch(audioPermissions())
            },
            onDismiss = { showSettings = false },
        )
    }

    if (showSignalPath) {
        AudioSignalPathSheet(
            track = playback.current ?: library.tracks.firstOrNull(),
            output = output,
            onRequestUsb = { UsbDacManager.requestPermission(context) },
            onDismiss = { showSignalPath = false },
        )
    }

    library.selectedAlbum?.let { album ->
        AlbumDetailSheet(
            album = album,
            currentUri = playback.current?.uri,
            isPlaying = playback.isPlaying,
            controller = controller,
            onTrackClick = { index ->
                viewModel.selectAlbum(null)
                playAndOpen(controller, album.tracks, index) {
                    animatePlayerSheet(target = 1f, fromDrag = false)
                }
            },
            onInspectClick = viewModel::setInspectTrack,
            onMessage = { msg -> userMessage = msg },
            onDismiss = { viewModel.selectAlbum(null) },
        )
    }

    library.selectedArtist?.let { artist ->
        ArtistDetailSheet(
            artist = artist,
            currentUri = playback.current?.uri,
            isPlaying = playback.isPlaying,
            controller = controller,
            onTrackClick = { index ->
                viewModel.selectArtist(null)
                playAndOpen(controller, artist.tracks, index) {
                    animatePlayerSheet(target = 1f, fromDrag = false)
                }
            },
            onInspectClick = viewModel::setInspectTrack,
            onMessage = { msg -> userMessage = msg },
            onDismiss = { viewModel.selectArtist(null) },
        )
    }

    library.selectedTagGroup?.let { group ->
        TagGroupDetailSheet(
            group = group,
            currentUri = playback.current?.uri,
            isPlaying = playback.isPlaying,
            controller = controller,
            onTrackClick = { index ->
                viewModel.selectTagGroup(null)
                playAndOpen(controller, group.tracks, index) {
                    animatePlayerSheet(target = 1f, fromDrag = false)
                }
            },
            onInspectClick = viewModel::setInspectTrack,
            onMessage = { msg -> userMessage = msg },
            onDismiss = { viewModel.selectTagGroup(null) },
        )
    }

    library.inspectTrack?.let { track ->
        MetadataInspectorDialog(
            track = track,
            output = output,
            analysis = analysis,
            onDismiss = { viewModel.setInspectTrack(null) },
            onOpenSignalPath = { showSignalPath = true },
            onAnalyzeClick = { viewModel.analyzeSpectral(track) },
        )
    }
}

/**
 * Convierte un MediaItem de YouTube Music en el [Track] que pinta la interfaz.
 *
 * Los datos de audio (códec, bitrate) llegan por extras porque el archivo todavía no existe:
 * el audio se resuelve recién cuando ExoPlayer abre el stream (ver PlaybackService).
 */
internal fun MediaItem.toStreamTrack(): Track {
    val videoId = localConfiguration?.uri?.lastPathSegment.orEmpty()
    return Track(
        id = videoId.hashCode().toLong(),
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = "YouTube Music",
        durationMs = mediaMetadata.durationMs ?: 0L,
        uri = localConfiguration?.uri ?: Uri.EMPTY,
        artworkOverride = mediaMetadata.artworkUri,
        // El códec real se conoce recién al resolver la URL, así que no se inventa acá.
        streamCodecLabel = "YouTube Music",
        albumId = 0L,
        fileName = "",
    )
}

/** Un fling es ~2.2 pasos por segundo: lo suficientemente decidido para no activarse por accidente. */
internal const val SWIPE_FLING_FACTOR = 2.2f

/** Con 0.18 la carátula acompaña al dedo pero deja claro que no hay tema siguiente/anterior. */
internal const val SWIPE_EDGE_RESISTANCE = 0.18f

/** Espera (hasta ~600 ms) a que el reproductor publique el cambio de canción. */
internal suspend fun awaitIndexChange(controller: MediaController, previousIndex: Int): Boolean {
    val deadline = System.currentTimeMillis() + 600
    while (System.currentTimeMillis() < deadline) {
        if (controller.currentMediaItemIndex != previousIndex) return true
        delay(16)
    }
    return controller.currentMediaItemIndex != previousIndex
}

internal fun playTrackList(controller: MediaController?, tracks: List<Track>, index: Int) {
    if (controller == null || tracks.isEmpty()) return
    QueueManager.playTracks(controller, tracks, index)
}

/**
 * Reproduce y **abre el reproductor**.
 *
 * Antes, tocar una canción en la biblioteca sólo la ponía a sonar: el reproductor
 * seguía minimizado y parecía que no pasaba nada. Ahora, elegir una canción siempre
 * lleva al reproductor completo (el mini dock queda abajo para volver a la lista).
 */
internal fun playAndOpen(
    controller: MediaController?,
    tracks: List<Track>,
    index: Int,
    openPlayer: () -> Unit,
) {
    if (controller == null || tracks.isEmpty()) return
    playTrackList(controller, tracks, index)
    openPlayer()
}

internal data class PlaybackSnapshot(
    val current: Track?,
    val isPlaying: Boolean,
    val progress: Float,
    /** Duración real de lo que está sonando, para la barra del mini reproductor. */
    val durationMs: Long = 0L,
    /** Último error del reproductor (p. ej. un stream de YouTube Music que no se pudo resolver). */
    val error: String? = null,
)

@Composable
internal fun rememberPlaybackState(
    controller: MediaController?,
    tracks: List<Track>,
): PlaybackSnapshot {
    var mediaId by remember { mutableStateOf<String?>(null) }
    var currentItem by remember { mutableStateOf<MediaItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableStateOf(0L) }
    var playError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        if (controller == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                mediaId = player.currentMediaItem?.mediaId
                currentItem = player.currentMediaItem
                isPlaying = player.isPlaying
                if (player.playerError == null) playError = null
            }

            override fun onPlayerError(error: PlaybackException) {
                playError = error.message ?: error.errorCodeName
            }
        }
        controller.addListener(listener)
        mediaId = controller.currentMediaItem?.mediaId
        currentItem = controller.currentMediaItem
        isPlaying = controller.isPlaying
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        while (isActive) {
            val duration = player.duration.coerceAtLeast(0)
            durationMs = duration
            progress = if (duration > 0) player.currentPosition.toFloat() / duration else 0f
            delay(400)
        }
    }

    // Primero la biblioteca local (por uri); si el ítem es un stream de YouTube Music no está
    // en la lista, así que se reconstruye desde el MediaItem del reproductor.
    val current = tracks.firstOrNull { it.uri.toString() == mediaId }
        ?: currentItem?.takeIf { it.localConfiguration?.uri?.scheme == YtmService.STREAM_SCHEME }
            ?.toStreamTrack()
    return PlaybackSnapshot(current, isPlaying, progress, durationMs, playError)
}




















/** La misma carátula se desplaza y encoge del centro a la esquina superior izquierda. */












