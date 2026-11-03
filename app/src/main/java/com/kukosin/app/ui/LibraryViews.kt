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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.animateContentSize
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
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    autoFocus: Boolean = false,
    /** Ofrece buscar también dentro de la letra de cada canción. */
    lyricsOnly: Boolean = false,
    onToggleLyrics: (() -> Unit)? = null,
    /** Letras ya indexadas (0 = todavía no se indexó ninguna). */
    lyricsIndexed: Int = 0,
    lyricsIndexing: Boolean = false,
) {
    // `true` mientras el campo se desplaza horizontalmente (ver `LaunchedEffect` abajo).
    var scrollDragging by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // El arrastre termina cuando el valor deja de cambiar durante 120 ms: al soltar se
    // resincroniza el texto con lo que realmente quedó en el campo.
    LaunchedEffect(scrollState.value) {
        scrollDragging = true
        delay(120)
        scrollDragging = false
    }
    // El texto que se escribe es estado LOCAL: la consulta del ViewModel va con debounce,
    // así escribir rápido no re-filtra la biblioteca en cada tecla (eso era lo que hacía
    // que el buscador se sintiera trabado con bibliotecas grandes).
    var draft by remember(query) { mutableStateOf(query) }
    // La búsqueda pide foco (y teclado) al abrirse sólo en la pantalla de búsqueda:
    // en las listas, el campo es un filtro y no queremos que salte el teclado solo.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    OutlinedTextField(
        value = draft,
        // El campo comparte desplazamiento horizontal con la barra de scroll: al arrastrar
        // la barra, Compose mueve el cursor y reescribe el valor, y los caracteres se
        // "desplazaban" solos. Mientras se arrastra se ignora ese cambio; el texto se
        // sincroniza al soltar.
        onValueChange = { value ->
            if (!scrollDragging) {
                draft = value
                onQueryChange(value)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                if (lyricsOnly) "Search inside the lyrics…" else "Search song, artist, album or format",
            )
        },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = CreamMuted) },
        trailingIcon = if (draft.isNotEmpty()) {
            {
                IconButton(onClick = {
                    draft = ""
                    onQueryChange("")
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = CreamMuted)
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = InkSoft,
            focusedContainerColor = InkSoft,
            unfocusedContainerColor = InkSoft,
            focusedTextColor = Cream,
            unfocusedTextColor = Cream,
            cursorColor = Gold,
        ),
    )
    if (onToggleLyrics != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = lyricsOnly,
                onClick = onToggleLyrics,
                label = { Text("Lyrics", fontWeight = FontWeight.Bold) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Lyrics,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Gold,
                    selectedLabelColor = Ink,
                    selectedLeadingIconColor = Ink,
                    containerColor = InkSoft,
                    labelColor = Cream,
                    iconColor = Gold,
                ),
            )
            Text(
                when {
                    lyricsIndexing -> "Indexing lyrics… ${lyricsIndexed} songs"
                    lyricsOnly && lyricsIndexed == 0 -> "No lyrics indexed yet"
                    lyricsOnly -> "$lyricsIndexed songs with lyrics indexed"
                    else -> "Search inside the lyrics, without accents"
                },
                style = MaterialTheme.typography.labelMedium,
                color = CreamMuted,
            )
        }
    }
}

@Composable
internal fun YtmView(
    ytmTracks: List<YtmTrack>,
    ytmQuery: String,
    isSearching: Boolean,
    errorMessage: String?,
    playingVideoId: String?,
    isPlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTrackClick: (YtmTrack) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = ytmQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            placeholder = { Text("Search YouTube Music Online...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Gold) },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Gold)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold,
                unfocusedBorderColor = InkSoft,
                focusedContainerColor = InkSoft,
                unfocusedContainerColor = InkSoft,
                focusedTextColor = Cream,
                unfocusedTextColor = Cream,
                cursorColor = Gold,
            ),
        )

        errorMessage?.let { message ->
            Surface(
                color = Gold.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cream,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ytmTracks.isEmpty()) {
                item {
                    Text(
                        if (isSearching) "Searching YouTube Music..." else "Search for a song, artist or album to play from YouTube Music.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CreamMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(ytmTracks, key = { _, t -> t.id }) { index, track ->
                RevealItem(index = index, contentKey = track.id) {
                        YtmTrackRow(
                            track = track,
                            isPlaying = track.id == playingVideoId && isPlaying,
                            onClick = { onTrackClick(track) },
                        )
                }
            }
        }
    }
}

@Composable
internal fun YtmTrackRow(
    track: YtmTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(InkSoft)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = CreamMuted)
            if (track.thumbnailUri.isNotBlank()) {
                AsyncImage(
                    model = track.thumbnailUri,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) Gold else Cream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.durationLabel).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = CreamMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                color = Gold.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    "YouTube Music",
                    style = MaterialTheme.typography.labelSmall,
                    color = Gold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        IconButton(onClick = onClick) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Gold,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
internal fun PermissionCard(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Gold,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Your music, lossless", style = MaterialTheme.typography.titleLarge, color = Cream)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kukosin reads your local library, decodes in high-resolution software and sends bit-perfect audio to the USB DAC or the active output.",
            style = MaterialTheme.typography.bodyMedium,
            color = CreamMuted,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Allow audio access")
        }
    }
}

@Composable
internal fun LoadingLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Gold)
            Spacer(Modifier.height(16.dp))
            Text("Scanning the library…", color = CreamMuted)
        }
    }
}

@Composable
internal fun EmptyLibrary(hasQuery: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (hasQuery) "Nothing matches your search" else "No audio files indexed",
            color = CreamMuted,
        )
    }
}

@Composable
internal fun TrackList(
    tracks: List<Track>,
    currentUri: android.net.Uri?,
    isPlaying: Boolean,
    controller: MediaController? = null,
    onTrackClick: (Int) -> Unit,
    onInspectClick: (Track) -> Unit,
    onMessage: ((String) -> Unit)? = null,
    extraLabel: ((Track) -> String)? = null,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
    indexSort: LibrarySort? = null,
    focusUri: android.net.Uri? = null,
) {
    if (tracks.isEmpty()) {
        EmptyLibrary(hasQuery = true)
        return
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<Char?>(null) }

    // "Show in library": cuando llega una pista desde el reproductor, la lista
    // se desplaza sola hasta ella.
    LaunchedEffect(focusUri, tracks) {
        if (focusUri == null) return@LaunchedEffect
        val index = tracks.indexOfFirst { it.uri == focusUri }
        if (index < 0) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(index) }
            .recoverCatching { listState.scrollToItem(index) }
    }

    // El índice A–Z sólo tiene sentido con orden alfabético y una lista que lo amerite.
    val sections = remember(tracks, indexSort) {
        if (indexSort != null && indexSort.isAlphabetical && tracks.size >= 20) {
            letterSections(tracks) { indexSort.sortKey(it) }
        } else {
            null
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = if (sections != null) 30.dp else 16.dp,
                bottom = bottomPadding.dp,
                top = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                // Dos animaciones distintas, y las dos importan:
                //
                //  - `animateItem()` mueve la fila cuando la lista se reordena o se
                //    filtra, en vez de que el contenido salte de golpe.
                //  - `RevealItem` hace que la fila entre desde abajo, escalonada. Como el
                //    estado vive en el ítem, cada fila que aparece al scrollear se vuelve a
                //    acomodar sola: la lista "respira" mientras la recorrés.
                Box(modifier = Modifier.animateItem()) {
                    RevealItem(index = index, contentKey = track.uri) {
                        TrackRow(
                            track = track,
                            selected = track.uri == currentUri,
                            isPlaying = isPlaying && track.uri == currentUri,
                            controller = controller,
                            onClick = { onTrackClick(index) },
                            onInspect = { onInspectClick(track) },
                            onMessage = onMessage,
                            extraLabel = extraLabel?.invoke(track),
                            highlighted = focusUri != null && track.uri == focusUri,
                        )
                    }
                }
            }
        }

        if (sections != null) {
            val (letters, positions) = sections
            AlphabetIndexBar(
                letters = letters,
                onSelect = { letter ->
                    positions[letter]?.let { target ->
                        scope.launch { listState.scrollToItem(target) }
                    }
                },
                onActiveChange = { activeLetter = it },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 2.dp),
            )
            activeLetter?.let { letter ->
                ActiveLetterBubble(
                    letter = letter,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackRow(
    track: Track,
    selected: Boolean,
    isPlaying: Boolean,
    controller: MediaController? = null,
    onClick: () -> Unit,
    onInspect: (() -> Unit)? = null,
    onMessage: ((String) -> Unit)? = null,
    extraLabel: String? = null,
    highlighted: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    var swipeHandled by remember { mutableStateOf(false) }
    var artworkDragX by remember { mutableFloatStateOf(0f) }
    // Feedback de toque: la fila se hunde un 1,5 % (casi imperceptible, pero el dedo lo
    // registra) mientras está presionada. Antes sólo había ripple del sistema.
    val rowInteraction = remember { MutableInteractionSource() }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Nunca confirmar el dismissal real: solo queremos ejecutar una acción
            // y dejar que el ítem regrese a su estado normal.
            false
        },
    )

    // Ejecutar la acción una sola vez al iniciar el swipe en una dirección.
    // El ítem regresa automáticamente porque confirmValueChange retorna false.
    LaunchedEffect(dismissState.dismissDirection) {
        val dir = dismissState.dismissDirection
        if (!swipeHandled) {
            when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    swipeHandled = true
                    val msg = QueueActions.playNext(controller, track)
                    onMessage?.invoke(msg)
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    swipeHandled = true
                    val msg = QueueActions.addToQueue(controller, track)
                    onMessage?.invoke(msg)
                }
                else -> Unit
            }
        }
    }

    // Reset cuando el ítem vuelve a su posición normal, permitiendo el próximo gesto
    LaunchedEffect(dismissState.dismissDirection) {
        if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled) {
            swipeHandled = false
        }
    }

    val artworkAlpha by animateFloatAsState(
        targetValue = if (artworkDragX > 0f) (1f - (artworkDragX / 90f).coerceIn(0f, 1f)) else 1f,
        animationSpec = Motion.quick(),
        label = "artworkDragAlpha",
    )

    // "Show in library" desde el reproductor: la fila se enciende en dorado un
    // par de segundos para que la encuentres de un vistazo.
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 0.20f else 0f,
        animationSpec = Motion.enter(Motion.EXIT_MS + 40),
        label = "rowHighlight",
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isPlayNext = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) InkSoft else Color.Transparent
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isPlayNext) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = if (isPlayNext) Icons.AutoMirrored.Rounded.PlaylistAdd else Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = if (isPlayNext) "Up next" else "+ Queue",
                    tint = Gold,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) InkSoft else Ink.copy(alpha = 0f))
                    .background(Gold.copy(alpha = highlightAlpha))
                    .pressScale(rowInteraction, scale = 0.985f)
                    .clickable(
                        interactionSource = rowInteraction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .graphicsLayer { alpha = artworkAlpha }
                        .pointerInput(track.uri.toString()) {
                            detectDragGestures(
                                onDragStart = { offset: Offset ->
                                    artworkDragX = 0f
                                },
                                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                    val absY = if (dragAmount.y < 0f) -dragAmount.y else dragAmount.y
                                    if (abs(dragAmount.x) > 2f && absY < 3f) {
                                        change.consume()
                                        artworkDragX += dragAmount.x
                                        if (artworkDragX > 60f) {
                                            controller?.seekToNext()
                                            artworkDragX = 0f
                                        }
                                    } else {
                                        artworkDragX = 0f
                                    }
                                },
                                onDragEnd = {
                                    artworkDragX = 0f
                                },
                                onDragCancel = {
                                    artworkDragX = 0f
                                },
                            )
                        },
                ) {
                    Artwork(track, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) Gold else Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${track.effectiveArtist} · ${track.album}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CreamMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(track.qualityLabel, extraLabel).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Gold.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = Gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelSmall, color = CreamMuted)
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "Options",
                                tint = CreamMuted.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (showMenu) {
                            TrackOptionsMenu(
                                track = track,
                                controller = controller,
                                expanded = showMenu,
                                onDismiss = { showMenu = false },
                                onInspect = { onInspect?.invoke() },
                                onMessage = { msg -> onMessage?.invoke(msg) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun AlbumView(
    albums: List<AlbumGroup>,
    onAlbumClick: (AlbumGroup) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
) {
    if (albums.isEmpty()) {
        EmptyLibrary(hasQuery = true)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(albums, key = { _, album -> album.name + "_" + album.artist }) { index, album ->
            RevealItem(index = index, contentKey = album.name + "_" + album.artist) {
                    AlbumCard(album = album, onClick = { onAlbumClick(album) })
            }
        }
    }
}

@Composable
internal fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(InkSoft)
            .pressScale(interaction, scale = 0.96f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Album, contentDescription = null, tint = CreamMuted, modifier = Modifier.size(48.dp))
            val artwork = album.artworkUri
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (album.isHiRes) {
                Surface(
                    color = Gold,
                    contentColor = Ink,
                    shape = RoundedCornerShape(bottomStart = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        "Hi-Res",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Cream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = CreamMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${album.tracks.size} songs${if (album.year > 0) " · ${album.year}" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = Gold,
        )
    }
}

@Composable
internal fun ArtistView(
    artists: List<ArtistGroup>,
    onArtistClick: (ArtistGroup) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
) {
    if (artists.isEmpty()) {
        EmptyLibrary(hasQuery = true)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(artists, key = { _, artist -> artist.name }) { index, artist ->
            RevealItem(index = index, contentKey = artist.name) {
                    ArtistRow(artist = artist, onClick = { onArtistClick(artist) })
            }
        }
    }
}

@Composable
internal fun ArtistRow(artist: ArtistGroup, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkSoft)
            .pressScale(interaction, scale = 0.98f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(InkElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = Gold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.titleMedium, color = Cream, fontWeight = FontWeight.Bold)
            Text(
                "${artist.albumCount} albums · ${artist.trackCount} songs",
                style = MaterialTheme.typography.bodyMedium,
                color = CreamMuted,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.NavigateNext, contentDescription = null, tint = CreamMuted)
    }
}

@Composable
internal fun FolderBrowserView(
    currentFolder: FolderNode?,
    folderPath: List<FolderNode>,
    currentUri: android.net.Uri?,
    isPlaying: Boolean,
    controller: MediaController? = null,
    onFolderClick: (FolderNode) -> Unit,
    onNavigateUp: () -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onInspectClick: (Track) -> Unit,
    onMessage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
) {
    if (currentFolder == null) {
        EmptyLibrary(hasQuery = false)
        return
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (folderPath.size > 1) {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Gold)
                }
            } else {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = Gold, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = currentFolder.name,
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(currentFolder.subfolders, key = { _, f -> f.path }) { index, subfolder ->
                RevealItem(index = index, contentKey = subfolder.path) {
                        FolderRow(folder = subfolder, onClick = { onFolderClick(subfolder) })
                }
            }

            itemsIndexed(currentFolder.tracks, key = { _, track -> track.uri.toString() }) { index, track ->
                RevealItem(index = index, contentKey = track.uri) {
                        TrackRow(
                            track = track,
                            selected = track.uri == currentUri,
                            isPlaying = isPlaying && track.uri == currentUri,
                            controller = controller,
                            onClick = { onTrackClick(currentFolder.tracks, index) },
                            onInspect = { onInspectClick(track) },
                            onMessage = onMessage,
                        )
                }
            }
        }
    }
}

@Composable
internal fun FolderRow(folder: FolderNode, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(InkSoft)
            .pressScale(interaction, scale = 0.98f)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = Gold, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleMedium, color = Cream, fontWeight = FontWeight.Bold)
            Text(
                "${folder.totalTracksCount} songs",
                style = MaterialTheme.typography.bodyMedium,
                color = CreamMuted,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.NavigateNext, contentDescription = null, tint = CreamMuted)
    }
}
