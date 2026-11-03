package com.kukosin.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.kukosin.app.data.Playlist
import com.kukosin.app.data.Track
import com.kukosin.app.data.formatTime
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft
import com.kukosin.app.ui.theme.Line
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Playlists
//
// Tres piezas:
//   1. PlaylistsCategoryView  → la rejilla dentro de Biblioteca → Playlists.
//   2. PlaylistDetailSheet    → ver/editar una lista (portada, nombre, orden, pistas).
//   3. AddToPlaylistSheet     → el "agregar a la playlist" que abre el menú de una pista.
// Más el selector de canciones (para meter temas desde la biblioteca) y el diálogo de
// nombre que comparten crear y renombrar.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "Add to playlist" disponible en toda la app.
 *
 * Los menús de pista viven en archivos distintos (biblioteca, cola, álbum…) y pasaban por
 * cuatro niveles de parámetros para llegar a una acción que es del reproductor, no de la
 * lista. Con este local, cualquier menú de pista la ofrece sin tocar firmas: si es `null`
 * (por ejemplo en una vista previa) el ítem simplemente no aparece.
 */
val LocalAddToPlaylist = staticCompositionLocalOf<((List<Track>) -> Unit)?> { null }

/** Portada cuadrada de una playlist: imagen elegida o degradado con el ícono de cola. */
@Composable
internal fun PlaylistCover(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    iconSize: Dp = 46.dp,
) {
    val file = playlist.coverFile
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(
                        InkSoft,
                        Gold.copy(alpha = 0.28f + ((playlist.name.hashCode() and 7) * 0.015f)),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = null,
            tint = Gold.copy(alpha = 0.8f),
            modifier = Modifier.size(iconSize),
        )
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** "12 songs · 48 min" a partir de las pistas que sí existen en la biblioteca. */
private fun playlistSubtitle(count: Int, durationMs: Long, missing: Int = 0): String {
    val songs = when (count) {
        0 -> "Empty"
        1 -> "1 song"
        else -> "$count songs"
    }
    val parts = mutableListOf(songs)
    if (durationMs > 0) {
        val minutes = (durationMs / 60_000L).coerceAtLeast(1L)
        parts.add(if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min")
    }
    if (missing > 0) parts.add("$missing missing")
    return parts.joinToString(" · ")
}

/**
 * La categoría "Playlists" de la biblioteca.
 *
 * La primera tarjeta siempre es "crear": es lo que más se busca la primera vez y así no
 * hace falta un botón flotante tapando carátulas. Arriba, si hay algo en la cola, se
 * ofrece guardarla tal cual como lista nueva.
 */
@Composable
internal fun PlaylistsCategoryView(
    playlists: List<Playlist>,
    tracksOf: (Playlist) -> List<Track>,
    queueCount: Int,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onCreate: () -> Unit,
    onSaveQueue: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Int = 0,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (playlists.isEmpty()) "No playlists yet" else "${playlists.size} playlists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                    Text(
                        "Build your own lists from the library",
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamMuted,
                    )
                }
                if (queueCount > 0) {
                    Surface(
                        color = InkSoft,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable(onClick = onSaveQueue),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = null,
                                tint = Gold,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Save queue",
                                style = MaterialTheme.typography.labelSmall,
                                color = Gold,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        item {
            NewPlaylistCard(onClick = onCreate)
        }

        gridItemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            RevealItem(index = index, contentKey = playlist.id) {
                PlaylistCard(
                    playlist = playlist,
                    tracks = remember(playlist.trackUris) { tracksOf(playlist) },
                    onClick = { onOpen(playlist) },
                    onPlay = { onPlay(playlist) },
                )
            }
        }
    }
}

@Composable
private fun NewPlaylistCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = Gold.copy(alpha = 0.28f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(InkElevated.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Gold, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("New playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Gold)
        Text("Create a playlist", style = MaterialTheme.typography.labelSmall, color = CreamMuted)
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    tracks: List<Track>,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val duration = remember(tracks) { tracks.sumOf { it.durationMs } }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(InkSoft)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box {
            PlaylistCover(
                playlist = playlist,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = 12.dp,
            )
            if (playlist.size > 0) {
                Surface(
                    color = Ink.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                ) {
                    IconButton(onClick = onPlay, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Play ${playlist.name}",
                            tint = Gold,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Cream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            playlistSubtitle(tracks.size, duration, (playlist.size - tracks.size).coerceAtLeast(0)),
            style = MaterialTheme.typography.labelSmall,
            color = CreamMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Detalle de una playlist.
 *
 * `resolved` viene alineado con `playlist.trackUris` (con `null` donde el archivo ya no
 * está) para que el índice que se ve en pantalla sea el mismo que se reordena o se borra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistDetailSheet(
    playlist: Playlist,
    resolved: List<Track?>,
    currentUri: Uri?,
    isPlaying: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onAddSongs: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCoverPicked: (Uri) -> Unit,
    onRemoveCover: () -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSmoothly: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    val coverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) onCoverPicked(uri) }

    val playable = remember(resolved) { resolved.filterNotNull() }
    val durationMs = remember(playable) { playable.sumOf { it.durationMs } }
    val missing = playlist.size - playable.size

    // Reordenar: misma mecánica que la cola (mantener pulsado y arrastrar), con un
    // desplazamiento de las filas intermedias para que se vea el hueco.
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 6.dp.toPx() }
    var measuredRowHeightPx by remember { mutableFloatStateOf(0f) }
    val itemSlotPx = if (measuredRowHeightPx > 0f) {
        measuredRowHeightPx + itemSpacingPx
    } else {
        with(density) { 68.dp.toPx() }
    }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = dismissSmoothly,
        sheetState = sheetState,
        containerColor = Ink.copy(alpha = 0.97f),
        contentColor = Cream,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.94f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // ── Cabecera: portada + nombre + acciones ──────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { coverLauncher.launch("image/*") },
                ) {
                    PlaylistCover(playlist = playlist, modifier = Modifier.fillMaxSize(), iconSize = 34.dp)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Ink.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = "Change cover",
                            tint = Gold,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playlistSubtitle(playable.size, durationMs, missing.coerceAtLeast(0)),
                        style = MaterialTheme.typography.bodySmall,
                        color = CreamMuted,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options", tint = CreamMuted)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(InkElevated),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = Cream) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = Gold) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Change cover", color = Cream) },
                            leadingIcon = { Icon(Icons.Rounded.Image, null, tint = Gold) },
                            onClick = {
                                menuOpen = false
                                coverLauncher.launch("image/*")
                            },
                        )
                        if (playlist.coverFile != null) {
                            DropdownMenuItem(
                                text = { Text("Remove cover", color = Cream) },
                                leadingIcon = { Icon(Icons.Rounded.Close, null, tint = CreamMuted) },
                                onClick = {
                                    menuOpen = false
                                    onRemoveCover()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Add songs", color = Cream) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = Gold) },
                            onClick = {
                                menuOpen = false
                                onAddSongs()
                            },
                        )
                        HorizontalDivider(color = Line)
                        DropdownMenuItem(
                            text = { Text("Delete playlist", color = Color(0xFFE57373)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Color(0xFFE57373)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
                IconButton(onClick = dismissSmoothly) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = CreamMuted)
                }
            }

            // ── Botones ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (playable.isNotEmpty()) onPlay(playable, 0)
                    },
                    enabled = playable.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = InkSoft,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
                    modifier = Modifier.clickable(enabled = playable.isNotEmpty()) { onShuffle(playable) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = Gold, modifier = Modifier.size(18.dp))
                    }
                }
                Surface(
                    color = InkSoft,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
                    modifier = Modifier.clickable(onClick = onAddSongs),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = "Add songs",
                            tint = Gold,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (playlist.trackUris.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = CreamMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Empty playlist", color = Cream, fontWeight = FontWeight.Bold)
                        Text(
                            "Add songs from the library or from any track menu",
                            color = CreamMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = playlist.trackUris,
                        key = { index, uri -> "${index}_$uri" },
                    ) { index, _ ->
                        val track = resolved.getOrNull(index)
                        val from = dragFrom
                        val to = dragTarget
                        val isBeingDragged = from == index
                        val shiftY = when {
                            isBeingDragged -> dragOffset
                            from != null && to != null && from < to && index > from && index <= to -> -itemSlotPx
                            from != null && to != null && from > to && index < from && index >= to -> itemSlotPx
                            else -> 0f
                        }
                        Box(
                            modifier = Modifier
                                .zIndex(if (isBeingDragged) 10f else 0f)
                                .onSizeChanged { size ->
                                    val heightPx = size.height.toFloat()
                                    if (measuredRowHeightPx <= 0f && heightPx > 0f) measuredRowHeightPx = heightPx
                                }
                                .pointerInputPlaylistDrag(
                                    enabled = !settling && playlist.size > 1,
                                    index = index,
                                    itemSlotPx = itemSlotPx,
                                    lastIndex = playlist.trackUris.lastIndex,
                                    onStart = {
                                        dragFrom = index
                                        dragTarget = index
                                        dragOffset = 0f
                                    },
                                    onDelta = { delta ->
                                        val start = dragFrom
                                        if (start != null && itemSlotPx > 0f) {
                                            dragOffset += delta
                                            dragTarget = (start + (dragOffset / itemSlotPx).roundToInt())
                                                .coerceIn(0, playlist.trackUris.lastIndex)
                                        }
                                    },
                                    onEnd = {
                                        val start = dragFrom
                                        if (start != null && itemSlotPx > 0f) {
                                            val end = (start + (dragOffset / itemSlotPx).roundToInt())
                                                .coerceIn(0, playlist.trackUris.lastIndex)
                                            settling = true
                                            scope.launch {
                                                animate(
                                                    initialValue = dragOffset,
                                                    targetValue = (end - start) * itemSlotPx,
                                                    animationSpec = Motion.snappy(650f, 0.85f),
                                                ) { value, _ -> dragOffset = value }
                                                if (start != end) onMove(start, end)
                                                dragFrom = null
                                                dragTarget = null
                                                dragOffset = 0f
                                                settling = false
                                            }
                                        } else {
                                            dragFrom = null
                                            dragTarget = null
                                            dragOffset = 0f
                                        }
                                    },
                                    onCancel = {
                                        if (dragFrom != null) {
                                            settling = true
                                            scope.launch {
                                                animate(
                                                    initialValue = dragOffset,
                                                    targetValue = 0f,
                                                    animationSpec = Motion.snappy(650f, 0.85f),
                                                ) { value, _ -> dragOffset = value }
                                                dragFrom = null
                                                dragTarget = null
                                                dragOffset = 0f
                                                settling = false
                                            }
                                        } else {
                                            dragOffset = 0f
                                        }
                                    },
                                ),
                        ) {
                            PlaylistTrackRow(
                                ordinal = index + 1,
                                track = track,
                                isCurrent = track != null && track.uri == currentUri,
                                isPlaying = isPlaying && track != null && track.uri == currentUri,
                                isDragging = isBeingDragged,
                                shiftY = shiftY,
                                onPlay = if (track == null) {
                                    null
                                } else {
                                    {
                                        val playableIndex = resolved.take(index).count { it != null }
                                        onPlay(playable, playableIndex)
                                    }
                                },
                                onRemove = { onRemoveAt(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistTrackRow(
    ordinal: Int,
    track: Track?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isDragging: Boolean,
    shiftY: Float,
    onPlay: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = shiftY
                val scale = if (isDragging) 1.02f else 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Gold.copy(alpha = 0.10f) else InkSoft.copy(alpha = 0.55f))
            .then(
                if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier,
            )
            .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = "Reorder",
            tint = CreamMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$ordinal",
            style = MaterialTheme.typography.labelSmall,
            color = CreamMuted.copy(alpha = 0.6f),
            modifier = Modifier.width(18.dp),
        )
        if (track != null) {
            Artwork(track, Modifier.size(44.dp), cornerRadius = 9.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) Gold else Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${track.effectiveArtist} · ${formatTime(track.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    "File not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CreamMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "You can remove it from the list",
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted.copy(alpha = 0.7f),
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from playlist",
                tint = CreamMuted.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * "Add to playlist" desde el menú de una pista.
 *
 * Muestra las listas con cuántas de las canciones elegidas ya están adentro, y deja crear
 * una nueva sin salir de la hoja (escribís el nombre y se crea con esas pistas).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    tracks: List<Track>,
    playlists: List<Playlist>,
    containedCount: (Playlist) -> Int,
    onChoose: (Playlist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSmoothly: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }
    var newName by remember { mutableStateOf("") }
    val uris = remember(tracks) { tracks.map { it.uri.toString() }.toSet() }

    ModalBottomSheet(
        onDismissRequest = dismissSmoothly,
        sheetState = sheetState,
        containerColor = Ink.copy(alpha = 0.97f),
        contentColor = Cream,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add to playlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                    Text(
                        if (tracks.size == 1) tracks.first().title else "${tracks.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = CreamMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = dismissSmoothly) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = CreamMuted)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(60) },
                    placeholder = { Text("New playlist…", color = CreamMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newName.isNotBlank()) {
                                onCreateAndAdd(newName.trim())
                                newName = ""
                            }
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateAndAdd(newName.trim())
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (playlists.isEmpty()) {
                    item {
                        Text(
                            "No playlists yet. Create the first one above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CreamMuted,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                    val already = remember(playlist.trackUris, uris) {
                        playlist.trackUris.count { it in uris }
                    }
                    val allIn = already >= tracks.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !allIn) { onChoose(playlist) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(
                            playlist = playlist,
                            modifier = Modifier.size(44.dp),
                            corner = 10.dp,
                            iconSize = 20.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Cream,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    allIn -> "All songs already in"
                                    already > 0 -> "$already of ${tracks.size} already in · ${playlist.size} in the list"
                                    playlist.size == 1 -> "1 song"
                                    else -> "${playlist.size} songs"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (allIn) Gold.copy(alpha = 0.8f) else CreamMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            if (allIn) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = if (allIn) Gold else CreamMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Selector de canciones de la biblioteca para meter en una lista existente.
 * Tocar una fila la agrega en el acto (y la marca con un check); no hay botón "guardar".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistTrackPickerSheet(
    playlistName: String,
    allTracks: List<Track>,
    existingUris: Set<String>,
    onPick: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSmoothly: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(allTracks, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) allTracks
        else allTracks.filter {
            it.title.lowercase().contains(needle) ||
                it.effectiveArtist.lowercase().contains(needle) ||
                it.album.lowercase().contains(needle)
        }
    }

    ModalBottomSheet(
        onDismissRequest = dismissSmoothly,
        sheetState = sheetState,
        containerColor = Ink.copy(alpha = 0.97f),
        contentColor = Cream,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add songs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                    )
                    Text(
                        playlistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = CreamMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = dismissSmoothly) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = CreamMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search the library…", color = CreamMuted) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(filtered, key = { _, track -> track.uri.toString() }) { _, track ->
                    val already = track.uri.toString() in existingUris
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !already) { onPick(track) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(track, Modifier.size(42.dp), cornerRadius = 9.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (already) CreamMuted else Cream,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.effectiveArtist,
                                style = MaterialTheme.typography.labelSmall,
                                color = CreamMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            if (already) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = if (already) Gold else CreamMuted.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Diálogo de nombre que comparten "crear" y "renombrar". */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val submit = {
        if (value.isNotBlank()) {
            onConfirm(value.trim())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        titleContentColor = Cream,
        textContentColor = CreamMuted,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(60) },
                singleLine = true,
                placeholder = { Text("Playlist name", color = CreamMuted) },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = value.isNotBlank()) {
                Text(confirmLabel, color = Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CreamMuted)
            }
        },
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Arrastre para reordenar: se activa con una pulsación larga (así el toque normal sigue
 * sirviendo para reproducir o para el menú) y avisa del desplazamiento mientras dura.
 */
private fun Modifier.pointerInputPlaylistDrag(
    enabled: Boolean,
    index: Int,
    itemSlotPx: Float,
    lastIndex: Int,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(index, itemSlotPx, lastIndex) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onStart() },
            onDrag = { change, dragAmount ->
                change.consume()
                onDelta(dragAmount.y)
            },
            onDragEnd = { onEnd() },
            onDragCancel = { onCancel() },
        )
    }
}
