package com.kukosin.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.kukosin.app.data.AlbumGroup
import com.kukosin.app.data.ArtistGroup
import com.kukosin.app.data.FolderNode
import com.kukosin.app.data.LibraryInsights
import com.kukosin.app.data.TagCategoryGroup
import com.kukosin.app.data.Track
import com.kukosin.app.data.formatTime
import com.kukosin.app.data.ytm.YtmTrack
import com.kukosin.app.playback.OutputDevice
import com.kukosin.app.playback.QueueManager
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft
import com.kukosin.app.ui.theme.Line
import kotlin.math.roundToInt

internal enum class AppSection {
    LIBRARY,
    EQUALIZER,
    SEARCH,
}

/**
 * Barra inferior con los 4 accesos rápidos del reproductor:
 * Biblioteca · Ecualizador · Búsqueda · Configuración.
 *
 * Es una sola pieza (un "segmented control"): los iconos van unidos dentro del mismo
 * contenedor y separados por divisores finos, sin texto. Antes cada acceso era una
 * tarjeta con etiqueta de dos líneas, que ocupaba 64 dp y competía visualmente con la
 * onda; ahora la barra mide 54 dp, se lee de un vistazo y deja respirar al reproductor.
 */
@Composable
internal fun PlayerShortcutBar(
    enabled: Boolean,
    onOpenLibrary: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = InkSoft,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.22f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShortcutIconButton(
                icon = Icons.Rounded.LibraryMusic,
                label = "Library",
                enabled = enabled,
                onClick = onOpenLibrary,
                modifier = Modifier.weight(1f),
            )
            ShortcutDivider()
            ShortcutIconButton(
                icon = Icons.Rounded.Tune,
                label = "Equalizer",
                enabled = enabled,
                onClick = onOpenEqualizer,
                modifier = Modifier.weight(1f),
            )
            ShortcutDivider()
            ShortcutIconButton(
                icon = Icons.Rounded.Search,
                label = "Search",
                enabled = enabled,
                onClick = onOpenSearch,
                modifier = Modifier.weight(1f),
            )
            ShortcutDivider()
            ShortcutIconButton(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                enabled = enabled,
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Separador vertical fino entre dos accesos de la barra. */
@Composable
private fun ShortcutDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(Gold.copy(alpha = 0.14f)),
    )
}

@Composable
private fun ShortcutIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pressScale(interaction, scale = 0.94f)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) Gold else CreamMuted.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}

private data class CategoryCardSpec(
    val category: LibraryCategory,
    val icon: ImageVector,
    val subtitle: String,
    val badge: String? = null,
)

@Composable
internal fun PowerampLibraryWorkspace(
    library: LibraryUiState,
    hasPermission: Boolean,
    output: OutputDevice,
    currentUri: Uri?,
    isPlaying: Boolean,
    controller: MediaController?,
    bottomPadding: Int,
    onBackToPlayer: () -> Unit,
    onSelectCategory: (LibraryCategory?) -> Unit,
    onQueryChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRescan: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onOpenQueueSheet: () -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onAlbumClick: (AlbumGroup) -> Unit,
    onArtistClick: (ArtistGroup) -> Unit,
    onTagGroupClick: (TagCategoryGroup) -> Unit,
    onFolderClick: (FolderNode) -> Unit,
    onNavigateUpFolder: () -> Unit,
    onInspectTrack: (Track) -> Unit,
    onMessage: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    /** Pista a resaltar y a la que hay que desplazar la lista (viene del reproductor). */
    focusUri: Uri? = null,
    modifier: Modifier = Modifier,
) {
    val activeCategory = library.activeCategory
    val queueSnapshot = rememberQueueSnapshot(controller, library.tracks)
    val queueCount = queueSnapshot.totalCount
    val favoriteUris by LibraryInsights.favorites.collectAsStateWithLifecycle()
    val playCounts by LibraryInsights.playCounts.collectAsStateWithLifecycle()
    val favoriteTracks = remember(library.tracks, library.query, favoriteUris) {
        library.filteredTracks.filter { it.uri.toString() in favoriteUris }
    }
    val mostPlayedTracks = remember(library.tracks, library.query, playCounts) {
        library.filteredTracks
            .filter { (playCounts[it.uri.toString()] ?: 0) > 0 }
            .sortedWith(compareByDescending<Track> { playCounts[it.uri.toString()] ?: 0 }
                .thenBy { it.title.lowercase() })
    }
    val favoriteCount = remember(library.tracks, favoriteUris) {
        library.tracks.count { it.uri.toString() in favoriteUris }
    }
    val playedCount = remember(library.tracks, playCounts) {
        library.tracks.count { (playCounts[it.uri.toString()] ?: 0) > 0 }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Barra superior estilo Poweramp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    when {
                        activeCategory == LibraryCategory.CARPETAS && library.folderPath.size > 1 -> onNavigateUpFolder()
                        activeCategory != null -> onSelectCategory(null)
                        else -> onBackToPlayer()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Gold,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeCategory?.title ?: "Library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (activeCategory == null) {
                        if (library.isScanning) "Scanning collection…" else "${library.tracks.size} tracks · Back to player"
                    } else {
                        "Library categories"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                color = InkSoft,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
                modifier = Modifier.clickable(onClick = onOpenSignalPath),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (output.isDac) Icons.Rounded.Usb else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (output.isDac) "DAC" else "Audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = Gold,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            IconButton(onClick = onRescan) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Rescan",
                    tint = CreamMuted,
                )
            }
        }

        // ---------------------------------------------------------------------------
        // Navegación animada de la Biblioteca
        //
        // Antes este bloque era un `if (activeCategory == null) { … } else { … }`: tocar
        // "Álbumes" reemplazaba el contenido de golpe y la nueva pantalla ya estaba ahí,
        // compuesta, sin ninguna pista de qué acababa de pasar. Ahora la categoría (y cada
        // carpeta dentro de Carpetas) se desliza: entra desde el lado hacia el que vas y la
        // anterior sale por el lado contrario, con un desenfoque muy corto encima que le da
        // la sensación de profundidad sin costar una pasada de GPU sostenida.
        // ---------------------------------------------------------------------------
        var navDirection by remember { mutableIntStateOf(1) }

        // Clave de navegación: la categoría activa y, en Carpetas, la ruta completa. Así
        // entrar a una subcarpeta es una navegación más (se anima) aunque la categoría
        // siga siendo la misma.
        val navKey = when (activeCategory) {
            null -> "root"
            LibraryCategory.CARPETAS -> "folders/" + library.folderPath.joinToString("/") { it.path }
            else -> activeCategory.name
        }

        // Desenfoque de navegación: sólo existe DURANTE el cruce. Se apaga solo y no deja
        // ningún `RenderEffect` activo cuando la pantalla está quieta. Con Blur = Off en
        // Ajustes este bloque ni se ejecuta.
        val blur = LocalUiSettings.current.blur
        val animationSpeed = LocalUiSettings.current.animationSpeed
        val navBlurFactor = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(navKey) {
            if (blur.enabled && animationSpeed.scale > 0f) {
                navBlurFactor.snapTo(0f)
                navBlurFactor.animateTo(1f, Motion.quick(Motion.SCREEN_MS / 3))
                navBlurFactor.animateTo(0f, Motion.leave(Motion.SCREEN_MS / 2))
            }
        }

        AnimatedContent(
            targetState = navKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    // 7 dp en "Soft": se lee como desenfoque de movimiento, no como una
                    // pantalla borrosa. Con Off no se aplica ni una vez.
                    if (blur.enabled && navBlurFactor.value > 0.01f) {
                        Modifier.blur(
                            radius = (blur.navigate.value * navBlurFactor.value).dp,
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                        )
                    } else {
                        Modifier
                    },
                ),
            transitionSpec = { libraryNavTransform(navDirection) },
            label = "libraryNavigation",
        ) { _ ->
            Column(modifier = Modifier.fillMaxSize()) {
        if (activeCategory == null) {
                val categories = remember(
                    library.tracks.size,
                    library.albums.size,
                    library.artists.size,
                    library.albumArtists.size,
                    library.genres.size,
                    library.years.size,
                    library.composers.size,
                    library.rootFolder,
                    queueCount,
                    favoriteCount,
                    playedCount,
                ) {
                    listOf(
                        CategoryCardSpec(
                            category = LibraryCategory.TODAS_LAS_CANCIONES,
                            icon = Icons.Rounded.MusicNote,
                            subtitle = "${library.tracks.size} songs",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.CARPETAS,
                            icon = Icons.Rounded.Folder,
                            subtitle = "${library.rootFolder?.subfolders?.size ?: 0} folders",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.ALBUMES,
                            icon = Icons.Rounded.Album,
                            subtitle = "${library.albums.size} albums",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.ARTISTAS,
                            icon = Icons.Rounded.Person,
                            subtitle = "${library.artists.size} artists",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.ARTISTAS_DEL_ALBUM,
                            icon = Icons.Rounded.Group,
                            subtitle = "${library.albumArtists.size} album artists",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.GENEROS,
                            icon = Icons.Rounded.Category,
                            subtitle = "${library.genres.size} genres",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.ANOS,
                            icon = Icons.Rounded.DateRange,
                            subtitle = "${library.years.size} years",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.COMPOSITORES,
                            icon = Icons.Rounded.Piano,
                            subtitle = "${library.composers.size} composers",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.LISTAS_DE_REPRODUCCION,
                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            subtitle = "Custom playlists",
                            badge = "Soon",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.LA_COLA,
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            subtitle = "$queueCount in queue",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.FAVORITOS,
                            icon = Icons.Rounded.Favorite,
                            subtitle = "$favoriteCount tracks marked",
                        ),
                        CategoryCardSpec(
                            category = LibraryCategory.MAS_REPRODUCIDO,
                            icon = Icons.Rounded.Star,
                            subtitle = "$playedCount tracks played",
                        ),
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = bottomPadding.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    library.error?.let { message ->
                        item(span = { GridItemSpan(2) }) {
                            Surface(
                                color = InkSoft,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Gold.copy(alpha = 0.45f)),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Could not scan the library", color = Cream, fontWeight = FontWeight.Bold)
                                    Text(message, color = CreamMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (!hasPermission) {
                        item(span = { GridItemSpan(2) }) {
                            Surface(
                                color = InkSoft,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Gold.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Audio permission pending",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Cream,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            "Grant it to index your local folders and tracks.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CreamMuted,
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Button(
                                        onClick = onRequestPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("Allow", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // La tarjeta entra escalonada y se hunde al tocarla: el toque responde
                    // antes de que la categoría termine de abrirse.
                    itemsIndexed(categories, key = { _, spec -> spec.category.name }) { index, spec ->
                        RevealItem(index = index, contentKey = spec.category) {
                                val interaction = remember { MutableInteractionSource() }
                                LibraryCategoryCard(
                                    spec = spec,
                                    interaction = interaction,
                                    onClick = {
                                        navDirection = 1
                                        onSelectCategory(spec.category)
                                    },
                                )
                        }
                    }
                }
            } else {
                val requiresLocalScan = activeCategory in setOf(
                    LibraryCategory.TODAS_LAS_CANCIONES,
                    LibraryCategory.CARPETAS,
                    LibraryCategory.ALBUMES,
                    LibraryCategory.ARTISTAS,
                    LibraryCategory.ARTISTAS_DEL_ALBUM,
                    LibraryCategory.GENEROS,
                    LibraryCategory.ANOS,
                    LibraryCategory.COMPOSITORES,
                    LibraryCategory.FAVORITOS,
                    LibraryCategory.MAS_REPRODUCIDO,
                )

                when {
                    requiresLocalScan && !hasPermission -> PermissionCard(onGrant = onRequestPermission)
                    requiresLocalScan && library.isScanning && library.tracks.isEmpty() -> LoadingLibrary()
                    requiresLocalScan && library.hasScanned && library.tracks.isEmpty() -> EmptyLibrary(hasQuery = false)
                    else -> {
                        when (activeCategory) {
                            LibraryCategory.TODAS_LAS_CANCIONES -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            if (library.filteredTracks.isNotEmpty()) {
                                                onTrackClick(library.filteredTracks, 0)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Play (${library.filteredTracks.size})", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (library.filteredTracks.isNotEmpty()) {
                                                val shuffled = library.filteredTracks.shuffled()
                                                onTrackClick(shuffled, 0)
                                            }
                                        },
                                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Shuffle", color = Gold, fontWeight = FontWeight.Bold)
                                    }
                                    SortMenuButton(
                                        current = library.sortMode,
                                        onSelect = onSortChange,
                                    )
                                }
                                TrackList(
                                    tracks = library.filteredTracks,
                                    currentUri = currentUri,
                                    isPlaying = isPlaying,
                                    controller = controller,
                                    onTrackClick = { idx -> onTrackClick(library.filteredTracks, idx) },
                                    onInspectClick = onInspectTrack,
                                    onMessage = onMessage,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                    indexSort = library.sortMode,
                                    focusUri = focusUri,
                                )
                            }

                            LibraryCategory.CARPETAS -> {
                                FolderBrowserView(
                                    currentFolder = library.currentFolder,
                                    folderPath = library.folderPath,
                                    currentUri = currentUri,
                                    isPlaying = isPlaying,
                                    controller = controller,
                                    onFolderClick = { folder ->
                                        navDirection = 1
                                        onFolderClick(folder)
                                    },
                                    onNavigateUp = {
                                        navDirection = -1
                                        onNavigateUpFolder()
                                    },
                                    onTrackClick = onTrackClick,
                                    onInspectClick = onInspectTrack,
                                    onMessage = onMessage,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.ALBUMES -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                AlbumView(
                                    albums = library.filteredAlbums,
                                    onAlbumClick = onAlbumClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.ARTISTAS -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                ArtistView(
                                    artists = library.filteredArtists,
                                    onArtistClick = onArtistClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.ARTISTAS_DEL_ALBUM -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                ArtistView(
                                    artists = library.filteredAlbumArtists,
                                    onArtistClick = onArtistClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.GENEROS -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                TagCategoryListView(
                                    groups = library.filteredGenres,
                                    fallbackIcon = Icons.Rounded.Category,
                                    onGroupClick = onTagGroupClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.ANOS -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                TagCategoryListView(
                                    groups = library.filteredYears,
                                    fallbackIcon = Icons.Rounded.DateRange,
                                    onGroupClick = onTagGroupClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.COMPOSITORES -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                TagCategoryListView(
                                    groups = library.filteredComposers,
                                    fallbackIcon = Icons.Rounded.Piano,
                                    onGroupClick = onTagGroupClick,
                                    modifier = Modifier.weight(1f),
                                    bottomPadding = bottomPadding,
                                )
                            }

                            LibraryCategory.LA_COLA -> {
                                InlineQueueCategoryView(
                                    controller = controller,
                                    snapshot = queueSnapshot,
                                    isPlaying = isPlaying,
                                    bottomPadding = bottomPadding,
                                    onOpenFullQueueEditor = onOpenQueueSheet,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            LibraryCategory.LISTAS_DE_REPRODUCCION -> {
                                UpcomingCategoryPlaceholder(
                                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                    title = "Playlists",
                                    description = "Here you can create your own playlists, save current queues and import M3U/M3U8 files. Their logic will be implemented in the next step.",
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            LibraryCategory.FAVORITOS -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                if (favoriteTracks.isEmpty() && library.query.isBlank()) {
                                    UpcomingCategoryPlaceholder(
                                        icon = Icons.Rounded.Favorite,
                                        title = "No favorites yet",
                                        description = "Tap the heart in the player to save your favorite local songs here.",
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    TrackList(
                                        tracks = favoriteTracks,
                                        currentUri = currentUri,
                                        isPlaying = isPlaying,
                                        controller = controller,
                                        onTrackClick = { index -> onTrackClick(favoriteTracks, index) },
                                        onInspectClick = onInspectTrack,
                                        onMessage = onMessage,
                                        modifier = Modifier.weight(1f),
                                        bottomPadding = bottomPadding,
                                    )
                                }
                            }

                            LibraryCategory.MAS_REPRODUCIDO -> {
                                SearchField(query = library.query, onQueryChange = onQueryChange)
                                if (mostPlayedTracks.isEmpty() && library.query.isBlank()) {
                                    UpcomingCategoryPlaceholder(
                                        icon = Icons.Rounded.Star,
                                        title = "No plays yet",
                                        description = "Your local songs will be ranked here as you start listening to them.",
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    TrackList(
                                        tracks = mostPlayedTracks,
                                        currentUri = currentUri,
                                        isPlaying = isPlaying,
                                        controller = controller,
                                        onTrackClick = { index -> onTrackClick(mostPlayedTracks, index) },
                                        onInspectClick = onInspectTrack,
                                        onMessage = onMessage,
                                        extraLabel = { track ->
                                            val count = playCounts[track.uri.toString()] ?: 0
                                            "$count ${if (count == 1) "play" else "plays"}"
                                        },
                                        modifier = Modifier.weight(1f),
                                        bottomPadding = bottomPadding,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Transición de una navegación dentro de la Biblioteca.
 *
 * El recorrido es corto (1/12 del ancho) a propósito: mover una pantalla entera desde el
 * borde se siente como un empujón y hay que esperar a que termine para leer. Con ~40 dp el
 * ojo sigue el contenido mientras se mueve, y el cruce se lee como "esto se acomodó" en
 * vez de "esto cambió".
 */
private fun libraryNavTransform(direction: Int): ContentTransform {
    val travel = { full: Int -> (full / 12).coerceAtLeast(30) }
    val enter = slideInHorizontally(
        animationSpec = Motion.enter(Motion.SCREEN_MS),
        initialOffsetX = { full -> direction * travel(full) },
    ) + fadeIn(tween(Motion.SCREEN_MS / 2))
    val exit = slideOutHorizontally(
        animationSpec = Motion.leave(Motion.SCREEN_MS),
        targetOffsetX = { full -> -direction * travel(full) },
    ) + fadeOut(tween(Motion.SCREEN_MS))
    // `sizeTransform = null` es el valor por defecto de `togetherWith`: las dos pantallas
    // ocupan el contenedor, así que animar el tamaño sólo costaría layout por frame.
    return enter togetherWith exit
}

@Composable
private fun LibraryCategoryCard(
    spec: CategoryCardSpec,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Surface(
        color = InkSoft,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            // El hundimiento va ANTES del `clickable` en la cadena para que la capa de la
            // tarjeta se escale con el dedo, sin mover el punto de toque.
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Gold.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = Gold,
                        modifier = Modifier.size(22.dp),
                    )
                }

                if (spec.badge != null) {
                    Surface(
                        color = InkElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = spec.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Gold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                        contentDescription = null,
                        tint = CreamMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = spec.category.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Cream,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = spec.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CreamMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TagCategoryListView(
    groups: List<TagCategoryGroup>,
    fallbackIcon: ImageVector,
    onGroupClick: (TagCategoryGroup) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int,
) {
    if (groups.isEmpty()) {
        EmptyLibrary(hasQuery = true)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomPadding.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(groups, key = { _, g -> g.name }) { index, group ->
            RevealItem(index = index, contentKey = group.name) {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(InkSoft)
                        .pressScale(interaction, scale = 0.98f)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) { onGroupClick(group) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(InkElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(fallbackIcon, contentDescription = null, tint = Gold)
                        val art = group.artworkUri
                        if (art != null) {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = CreamMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                        contentDescription = null,
                        tint = CreamMuted,
                    )
            }
            }
        }
    }
}

@Composable
private fun InlineQueueCategoryView(
    controller: MediaController?,
    snapshot: QueueSnapshot,
    isPlaying: Boolean,
    bottomPadding: Int,
    onOpenFullQueueEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller == null || snapshot.items.isEmpty()) {
        UpcomingCategoryPlaceholder(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = "The queue is empty",
            description = "Play any song or album to build the active queue.",
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenFullQueueEditor,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reorder queue (${snapshot.totalCount})", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { QueueManager.toggleShuffle(controller) },
                border = BorderStroke(1.dp, Gold.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = Gold, modifier = Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = { QueueManager.clearUpcoming(controller) },
                border = BorderStroke(1.dp, Line),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear up next", tint = CreamMuted, modifier = Modifier.size(18.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = bottomPadding.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(snapshot.items, key = { _, item -> item.mediaItem.mediaId }) { idx, item ->
                val title = item.track?.title ?: item.mediaItem.mediaMetadata.title?.toString().orEmpty().ifBlank { "Untitled" }
                val artist = item.track?.effectiveArtist ?: item.mediaItem.mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" }
                Surface(
                    color = if (item.isCurrent) Gold.copy(alpha = 0.14f) else InkSoft,
                    shape = RoundedCornerShape(14.dp),
                    border = if (item.isCurrent) BorderStroke(1.dp, Gold.copy(alpha = 0.6f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        // La cola se reacomoda en vez de saltar: cambiar el orden o
                        // quitar una pista ahora se ve como un movimiento.
                        .animateItem()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            controller.seekToDefaultPosition(item.index)
                            controller.play()
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${idx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.isCurrent) Gold else CreamMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (item.isCurrent) Gold else Cream,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = CreamMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.isCurrent && isPlaying) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingCategoryPlaceholder(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = InkSoft,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.3f)),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Gold.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Cream,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CreamMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagGroupDetailSheet(
    group: TagCategoryGroup,
    currentUri: Uri?,
    isPlaying: Boolean,
    controller: MediaController?,
    onTrackClick: (Int) -> Unit,
    onInspectClick: (Track) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
        contentColor = Cream,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                    Text(
                        text = "${group.subtitle} · ${formatTime(group.totalDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gold,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onTrackClick(0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                if (controller != null) {
                    OutlinedButton(
                        onClick = {
                            val count = QueueManager.addToCustomQueue(
                                controller,
                                group.tracks.map(Track::toMediaItem),
                                playNext = false,
                            )
                            onMessage("$count songs added to the queue")
                        },
                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("To queue", color = Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }

            TrackList(
                tracks = group.tracks,
                currentUri = currentUri,
                isPlaying = isPlaying,
                controller = controller,
                onTrackClick = onTrackClick,
                onInspectClick = onInspectClick,
                onMessage = onMessage,
                modifier = Modifier.height(420.dp),
                bottomPadding = 24,
            )
        }
    }
}

/**
 * Pantalla dedicada de Búsqueda (Biblioteca local + YouTube Music Online).
 */
@Composable
internal fun PowerampSearchWorkspace(
    library: LibraryUiState,
    hasPermission: Boolean,
    currentUri: Uri?,
    playingYtmId: String?,
    isPlaying: Boolean,
    playbackError: String?,
    controller: MediaController?,
    bottomPadding: Int,
    onBackToPlayer: () -> Unit,
    onQueryChange: (String) -> Unit,
    onYtmQueryChange: (String) -> Unit,
    onSearchYtm: () -> Unit,
    onToggleLyrics: () -> Unit,
    onRequestPermission: () -> Unit,
    onLocalTrackClick: (List<Track>, Int) -> Unit,
    onAlbumClick: (AlbumGroup) -> Unit,
    onArtistClick: (ArtistGroup) -> Unit,
    onInspectTrack: (Track) -> Unit,
    onYtmTrackClick: (YtmTrack) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchSource by remember { mutableIntStateOf(0) } // 0 = Local, 1 = YouTube Music

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackToPlayer) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Gold, modifier = Modifier.size(26.dp))
            }
            Text(
                text = "Search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = searchSource == 0,
                    onClick = { searchSource = 0 },
                    label = { Text("Local", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
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
                FilterChip(
                    selected = searchSource == 1,
                    onClick = { searchSource = 1 },
                    label = { Text("YouTube Music", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
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
            }
        }

        if (searchSource == 1) {
            YtmView(
                ytmTracks = library.ytmTracks,
                ytmQuery = library.ytmQuery,
                isSearching = library.isYtmSearching,
                errorMessage = library.ytmError ?: playbackError,
                playingVideoId = playingYtmId,
                isPlaying = isPlaying,
                onQueryChange = onYtmQueryChange,
                onSearch = onSearchYtm,
                onTrackClick = onYtmTrackClick,
                modifier = Modifier.weight(1f),
                bottomPadding = bottomPadding,
            )
        } else {
            if (!hasPermission) {
                PermissionCard(onGrant = onRequestPermission)
            } else {
                // Pantalla de búsqueda: el campo pide foco y teclado al abrirse.
                SearchField(
                    query = library.query,
                    onQueryChange = onQueryChange,
                    autoFocus = true,
                    lyricsOnly = library.lyricsOnly,
                    onToggleLyrics = onToggleLyrics,
                    lyricsIndexed = library.lyricsIndexed,
                    lyricsIndexing = library.lyricsIndexing,
                )
                val q = library.query.trim()
                if (q.isNotEmpty() && library.filteredAlbums.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(library.filteredAlbums.take(10), key = { it.name + "_" + it.artist }) { album ->
                            Surface(
                                color = InkSoft,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Line),
                                modifier = Modifier
                                    .width(150.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onAlbumClick(album) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(InkElevated),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Album, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
                                        album.artworkUri?.let { art ->
                                            AsyncImage(
                                                model = art,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.labelMedium, color = Cream, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(album.artist, style = MaterialTheme.typography.labelSmall, color = CreamMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                if (q.isNotEmpty() && library.filteredArtists.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(library.filteredArtists.take(10), key = { it.name }) { artist ->
                            Surface(
                                color = InkSoft,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Line),
                                modifier = Modifier.clickable { onArtistClick(artist) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Person, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(artist.name, style = MaterialTheme.typography.labelMedium, color = Cream, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                TrackList(
                    tracks = library.filteredTracks,
                    currentUri = currentUri,
                    isPlaying = isPlaying,
                    controller = controller,
                    onTrackClick = { idx -> onLocalTrackClick(library.filteredTracks, idx) },
                    onInspectClick = onInspectTrack,
                    onMessage = onMessage,
                    modifier = Modifier.weight(1f),
                    bottomPadding = bottomPadding,
                )
            }
        }
    }
}

/**
 * Menú de orden de la lista.
 *
 * Se aplica en el ViewModel, así que también cambia el orden de lo que se manda al
 * reproductor: la canción que tocás es la que suena.
 */
@Composable
private fun SortMenuButton(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.Sort, contentDescription = "Sort list", tint = Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(current.shortLabel, color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            LibrarySort.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (option == current) Gold else Ink,
                            fontWeight = if (option == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}
