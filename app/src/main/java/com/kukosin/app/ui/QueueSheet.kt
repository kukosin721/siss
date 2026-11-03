package com.kukosin.app.ui

import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.kukosin.app.data.Track
import com.kukosin.app.data.formatTime
import com.kukosin.app.playback.AudioConfig
import com.kukosin.app.playback.AudioSettingsManager
import com.kukosin.app.playback.QueueManager
import com.kukosin.app.ui.theme.Cream
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import com.kukosin.app.ui.theme.InkElevated
import com.kukosin.app.ui.theme.InkSoft
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class QueueItemState(
    val index: Int,
    val mediaItem: MediaItem,
    val track: Track?,
    val isCurrent: Boolean,
)

data class QueueSnapshot(
    val items: List<QueueItemState>,
    val currentIndex: Int,
    val totalCount: Int,
    val totalRemainingMs: Long,
)

private fun MediaItem.toFallbackTrack(): Track {
    val meta = mediaMetadata
    val uri = localConfiguration?.uri ?: Uri.EMPTY
    return Track(
        id = mediaId.hashCode().toLong(),
        uri = uri,
        title = meta.title?.toString()?.ifBlank { "Untitled" } ?: "Untitled",
        artist = meta.artist?.toString()?.ifBlank { "Unknown artist" } ?: "Unknown artist",
        album = meta.albumTitle?.toString()?.ifBlank { "Unknown" } ?: "Unknown",
        durationMs = 0L,
        mimeType = "",
        fileName = "",
    )
}

fun getQueueSnapshot(
    controller: MediaController?,
    trackMap: Map<String, Track>,
): QueueSnapshot {
    if (controller == null) return QueueSnapshot(emptyList(), -1, 0, 0L)

    val count = controller.mediaItemCount
    val currentIndex = controller.currentMediaItemIndex
    var remainingMs = 0L

    val items = ArrayList<QueueItemState>(count)
    for (i in 0 until count) {
        val mediaItem = controller.getMediaItemAt(i)
        val mediaId = mediaItem.mediaId

        val track = trackMap[mediaId]
            ?: (if (mediaId.startsWith("ytm_")) trackMap[mediaId.removePrefix("ytm_")] else null)
            ?: mediaItem.toFallbackTrack()

        if (i >= currentIndex) {
            remainingMs += track.durationMs
        }

        items.add(
            QueueItemState(
                index = i,
                mediaItem = mediaItem,
                track = track,
                isCurrent = (i == currentIndex),
            ),
        )
    }

    return QueueSnapshot(
        items = items,
        currentIndex = currentIndex,
        totalCount = count,
        totalRemainingMs = remainingMs,
    )
}

@Composable
fun rememberQueueSnapshot(
    controller: MediaController?,
    tracks: List<Track>,
): QueueSnapshot {
    val trackMap = remember(tracks) {
        val map = HashMap<String, Track>(tracks.size * 2)
        for (track in tracks) {
            map[track.uri.toString()] = track
            map["ytm_${track.id}"] = track
        }
        map
    }

    var queueVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                queueVersion++
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                queueVersion++
            }
            override fun onEvents(player: Player, events: Player.Events) {
                queueVersion++
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    return remember(controller, trackMap, queueVersion) {
        getQueueSnapshot(controller, trackMap)
    }
}

object QueueActions {
    fun playQueueItem(controller: MediaController?, index: Int) {
        if (controller == null) return
        if (index in 0 until controller.mediaItemCount) {
            controller.seekToDefaultPosition(index)
            controller.prepare()
            controller.play()
        }
    }

    fun removePendingItem(controller: MediaController?, relativeIndex: Int) {
        if (controller == null) return
        QueueManager.removePending(controller, relativeIndex)
    }

    fun movePendingItem(controller: MediaController?, fromRelative: Int, toRelative: Int) {
        if (controller == null) return
        QueueManager.movePending(controller, fromRelative, toRelative)
    }

    fun clearQueue(controller: MediaController?) {
        if (controller == null) return
        QueueManager.clearUpcoming(controller)
    }

    fun playNext(controller: MediaController?, track: Track): String {
        if (controller == null) return "No player available"
        QueueManager.addToCustomQueue(controller, listOf(track.toMediaItem()), playNext = true)
        return "Up next: ${track.title}"
    }

    fun addToQueue(controller: MediaController?, track: Track): String {
        if (controller == null) return "No player available"
        QueueManager.addToCustomQueue(controller, listOf(track.toMediaItem()), playNext = false)
        return "Added to custom queue: ${track.title}"
    }

    fun addListToQueue(
        controller: MediaController?,
        tracks: List<Track>,
        playNext: Boolean = false,
    ): String {
        if (controller == null || tracks.isEmpty()) return "No songs to add"
        val added = QueueManager.addToCustomQueue(
            controller,
            tracks.map(Track::toMediaItem),
            playNext = playNext,
        )
        return if (playNext) {
            "$added songs will play next"
        } else {
            "$added songs added to the custom queue"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    controller: MediaController,
    tracks: List<Track>,
    audioConfig: AudioConfig,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snapshot = rememberQueueSnapshot(controller, tracks)
    val shuffleEnabled by QueueManager.shuffleEnabled.collectAsStateWithLifecycle()
    val customCountState by QueueManager.customCount.collectAsStateWithLifecycle()

    var showPlayedHistory by remember { mutableStateOf(audioConfig.queueShowHistory) }

    LaunchedEffect(audioConfig.queueShowHistory) {
        showPlayedHistory = audioConfig.queueShowHistory
    }

    var position by remember { mutableLongStateOf(controller.currentPosition) }
    var duration by remember { mutableLongStateOf(controller.duration.coerceAtLeast(0)) }
    LaunchedEffect(controller) {
        while (true) {
            position = controller.currentPosition
            duration = controller.duration.coerceAtLeast(0)
            delay(400)
        }
    }

    val items = snapshot.items
    val currentIndex = snapshot.currentIndex
    val pendingStart = currentIndex + 1
    val pendingSize = (items.size - pendingStart).coerceAtLeast(0)
    val customCount = customCountState.coerceIn(0, pendingSize)
    val pendingItems: List<QueueItemState> =
        if (pendingSize > 0) items.subList(pendingStart, items.size) else emptyList()
    val historyItems: List<QueueItemState> =
        if (currentIndex > 0) items.subList(0, currentIndex) else emptyList()
    val currentItem = items.getOrNull(currentIndex)

    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 8.dp.toPx() }
    var measuredRowHeightPx by remember { mutableFloatStateOf(0f) }
    val itemSlotPx = if (measuredRowHeightPx > 0f) {
        measuredRowHeightPx + itemSpacingPx
    } else {
        with(density) { (if (audioConfig.queueCompactMode) 58.dp else 78.dp).toPx() }
    }

    var draggingRelativeIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetRelativeIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSettlingDrop by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(audioConfig.queueCompactMode) {
        measuredRowHeightPx = 0f
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
        contentColor = Cream,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            QueueHeader(
                totalCount = snapshot.totalCount,
                remainingMs = snapshot.totalRemainingMs,
                shuffleEnabled = shuffleEnabled,
                onToggleShuffle = { QueueManager.toggleShuffle(controller) },
                onDismiss = onDismiss,
            )

            Spacer(Modifier.height(12.dp))

            if (items.isEmpty()) {
                EmptyQueueState(Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QueueStat(
                        label = "UP NEXT",
                        value = pendingSize.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    QueueStat(
                        label = "REMAINING",
                        value = formatTime(snapshot.totalRemainingMs),
                        modifier = Modifier.weight(1f),
                    )
                    QueueStat(
                        label = "PLAYED",
                        value = historyItems.size.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (currentItem != null) {
                        item(key = "now_playing_${currentItem.index}_${currentItem.mediaItem.mediaId}") {
                            CurrentlyPlayingCard(
                                item = currentItem,
                                isCompact = audioConfig.queueCompactMode,
                                isPlaying = controller.isPlaying,
                                positionMs = position,
                                durationMs = duration,
                            )
                        }
                    }

                    if (customCount > 0) {
                        item(key = "custom_banner") {
                            CustomQueueBanner(customCount = customCount)
                        }
                    }

                    if (pendingItems.isNotEmpty()) {

                        val currentInitialIdx = draggingRelativeIndex
                        val currentTargetIdx = dragTargetRelativeIndex

                        itemsIndexed(
                            items = pendingItems,
                            key = { _, item -> "pending_${item.index}_${item.mediaItem.mediaId}" },
                        ) { relativeIndex, item ->
                            val isBeingDragged = (currentInitialIdx == relativeIndex)
                            val shiftY = when {
                                isBeingDragged -> dragOffset
                                currentInitialIdx != null && currentTargetIdx != null &&
                                    currentInitialIdx < currentTargetIdx &&
                                    relativeIndex > currentInitialIdx && relativeIndex <= currentTargetIdx -> -itemSlotPx
                                currentInitialIdx != null && currentTargetIdx != null &&
                                    currentInitialIdx > currentTargetIdx &&
                                    relativeIndex < currentInitialIdx && relativeIndex >= currentTargetIdx -> itemSlotPx
                                else -> 0f
                            }

                            Box(
                                modifier = Modifier
                                    .zIndex(if (isBeingDragged) 10f else 0f)
                                    .onGloballyPositioned { coords ->
                                        if (measuredRowHeightPx <= 0f && coords.size.height > 0) {
                                            measuredRowHeightPx = coords.size.height.toFloat()
                                        }
                                    }
                                    .pointerInput(relativeIndex, itemSlotPx, pendingItems) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                if (!isSettlingDrop && draggingRelativeIndex == null) {
                                                    draggingRelativeIndex = relativeIndex
                                                    dragTargetRelativeIndex = relativeIndex
                                                    dragOffset = 0f
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val startIdx = draggingRelativeIndex
                                                if (startIdx != null && itemSlotPx > 0f) {
                                                    dragOffset += dragAmount.y
                                                    dragTargetRelativeIndex =
                                                        (startIdx + (dragOffset / itemSlotPx).roundToInt())
                                                            .coerceIn(0, pendingItems.lastIndex)
                                                }
                                            },
                                            onDragEnd = {
                                                val startIdx = draggingRelativeIndex
                                                if (startIdx != null && startIdx in 0..pendingItems.lastIndex && itemSlotPx > 0f) {
                                                    val endIdx =
                                                        (startIdx + (dragOffset / itemSlotPx).roundToInt())
                                                            .coerceIn(0, pendingItems.lastIndex)
                                                    dragTargetRelativeIndex = endIdx
                                                    isSettlingDrop = true
                                                    val settleOffset = (endIdx - startIdx) * itemSlotPx
                                                    scope.launch {
                                                        animate(
                                                            initialValue = dragOffset,
                                                            targetValue = settleOffset,
                                                            animationSpec = Motion.snappy(
                                                                stiffness = 650f,
                                                                dampingRatio = 0.85f,
                                                            ),
                                                        ) { value, _ ->
                                                            dragOffset = value
                                                        }
                                                        if (startIdx != endIdx) {
                                                            QueueActions.movePendingItem(controller, startIdx, endIdx)
                                                        }
                                                        draggingRelativeIndex = null
                                                        dragTargetRelativeIndex = null
                                                        dragOffset = 0f
                                                        isSettlingDrop = false
                                                    }
                                                } else {
                                                    draggingRelativeIndex = null
                                                    dragTargetRelativeIndex = null
                                                    dragOffset = 0f
                                                    isSettlingDrop = false
                                                }
                                            },
                                            onDragCancel = {
                                                if (draggingRelativeIndex != null) {
                                                    isSettlingDrop = true
                                                    scope.launch {
                                                        animate(
                                                            initialValue = dragOffset,
                                                            targetValue = 0f,
                                                            animationSpec = Motion.snappy(
                                                                stiffness = 650f,
                                                                dampingRatio = 0.85f,
                                                            ),
                                                        ) { value, _ ->
                                                            dragOffset = value
                                                        }
                                                        draggingRelativeIndex = null
                                                        dragTargetRelativeIndex = null
                                                        dragOffset = 0f
                                                        isSettlingDrop = false
                                                    }
                                                } else {
                                                    dragOffset = 0f
                                                }
                                            },
                                        )
                                    },
                            ) {
                                SwipeableQueueItemRow(
                                    item = item,
                                    isCompact = audioConfig.queueCompactMode,
                                    isDragging = isBeingDragged,
                                    dragOffset = shiftY,
                                    ordinal = relativeIndex + 1,
                                    isCustom = relativeIndex < customCount,
                                    onPlay = { QueueActions.playQueueItem(controller, item.index) },
                                    onRemove = {
                                        QueueActions.removePendingItem(controller, relativeIndex)
                                    },
                                )
                            }
                        }
                    }

                    if (historyItems.isNotEmpty()) {
                        item(key = "header_history") {
                            HistoryHeader(
                                count = historyItems.size,
                                expanded = showPlayedHistory,
                                onToggle = {
                                    showPlayedHistory = !showPlayedHistory
                                    AudioSettingsManager.setQueueShowHistory(showPlayedHistory)
                                },
                            )
                        }

                        if (showPlayedHistory) {
                            itemsIndexed(
                                items = historyItems.asReversed(),
                                key = { _, item -> "history_${item.index}_${item.mediaItem.mediaId}" },
                            ) { _, item ->
                                HistoryItemRow(
                                    item = item,
                                    isCompact = audioConfig.queueCompactMode,
                                    onPlay = { QueueActions.playQueueItem(controller, item.index) },
                                    onAdd = {
                                        val track = item.track
                                        if (track != null) {
                                            onMessage(QueueActions.addToQueue(controller, track))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${pendingSize} up next",
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamMuted,
                    )
                    TextButton(
                        onClick = { QueueActions.clearQueue(controller) },
                        enabled = pendingItems.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            tint = if (pendingItems.isNotEmpty()) Gold else CreamMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Clear up next",
                            color = if (pendingItems.isNotEmpty()) Gold else CreamMuted.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    totalCount: Int,
    remainingMs: Long,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(InkSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Playback queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shuffleEnabled) {
                        Spacer(Modifier.width(6.dp))
                        Tag(text = "SHUFFLE", color = Gold)
                    }
                }
                val remaining = if (remainingMs > 0) " · ${formatTime(remainingMs)} remaining" else ""
                Text(
                    "$totalCount songs$remaining",
                    style = MaterialTheme.typography.labelMedium,
                    color = CreamMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleEnabled) Gold else CreamMuted,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = CreamMuted,
                )
            }
        }
    }
}

@Composable
private fun QueueStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = InkElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, InkSoft),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = CreamMuted.copy(alpha = 0.8f),
                fontWeight = FontWeight.Bold,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyQueueState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = CreamMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "The queue is empty",
                style = MaterialTheme.typography.titleMedium,
                color = CreamMuted,
            )
            Text(
                "Play something from the library or add songs with “Add to queue”",
                style = MaterialTheme.typography.bodySmall,
                color = CreamMuted.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CustomQueueBanner(customCount: Int) {
    Surface(
        color = Gold.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Custom queue active",
                    style = MaterialTheme.typography.labelMedium,
                    color = Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "$customCount ${if (customCount == 1) "song" else "songs"} · the main queue continues afterwards",
                    style = MaterialTheme.typography.bodySmall,
                    color = CreamMuted,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    badge: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Gold),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = CreamMuted,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Tag(text = count.toString(), color = CreamMuted)
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Tag(text = badge, color = Gold)
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = InkElevated.copy(alpha = 0.7f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, InkSoft),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = CreamMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "PLAYED",
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Tag(text = count.toString(), color = CreamMuted)
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide played" else "Show played",
                tint = CreamMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CurrentlyPlayingCard(
    item: QueueItemState,
    isCompact: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
) {
    val track = item.track
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = InkSoft),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, Gold.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                track?.let { Artwork(it, Modifier.size(if (isCompact) 56.dp else 64.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "Untitled",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${track?.effectiveArtist ?: "Unknown"} · ${track?.album ?: "Album"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!isCompact && track != null) {
                        Text(
                            track.qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = CreamMuted.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = Gold,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Now playing",
                            tint = Ink,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isPlaying) "Playing" else "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Gold,
                trackColor = Ink,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTime(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                )
                Text(
                    formatTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQueueItemRow(
    item: QueueItemState,
    isCompact: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    ordinal: Int,
    isCustom: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var removalJob by remember { mutableStateOf<Job?>(null) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.StartToEnd -> {
                    // Diferimos la eliminación para que se vea el fade out (vanish)
                    removalJob?.cancel()
                    removalJob = scope.launch {
                        delay(220)
                        onRemove()
                    }
                    true
                }
                else -> {
                    removalJob?.cancel()
                    removalJob = null
                    false
                }
            }
        },
    )

    // Animación de desaparición suave (vanish): fade out + leve escala hacia abajo
    val vanishAlpha by animateFloatAsState(
        targetValue = if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) 0f else 1f,
        animationSpec = Motion.leave(),
        label = "queueVanishAlpha",
    )
    val vanishScale by animateFloatAsState(
        targetValue = if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) 0.94f else 1f,
        animationSpec = Motion.leave(),
        label = "queueVanishScale",
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.StartToEnd -> Color(0xFFD32F2F)
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .padding(horizontal = 16.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        content = {
            QueueItemRow(
                item = item,
                isCompact = isCompact,
                isDragging = isDragging,
                dragOffset = dragOffset,
                ordinal = ordinal,
                isCustom = isCustom,
                onPlay = onPlay,
                onRemove = onRemove,
                vanishAlpha = vanishAlpha,
                vanishScale = vanishScale,
            )
        },
    )
}

@Composable
private fun QueueItemRow(
    item: QueueItemState,
    isCompact: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    ordinal: Int,
    isCustom: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    vanishAlpha: Float = 1f,
    vanishScale: Float = 1f,
) {
    val track = item.track
    val itemScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = Motion.snappy(stiffness = 400f, dampingRatio = 0.65f),
        label = "dragScale",
    )
    val itemRotation by animateFloatAsState(
        targetValue = if (isDragging) -1.4f else 0f,
        animationSpec = Motion.snappy(stiffness = 300f, dampingRatio = 0.55f),
        label = "dragRotation",
    )
    val animatedTranslationY by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (isDragging) {
            snap()
        } else {
            Motion.snappy(stiffness = 550f, dampingRatio = 0.8f)
        },
        label = "dragTranslationY",
    )

    Surface(
        color = if (isDragging) InkSoft else InkElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when {
                isDragging -> Gold
                isCustom -> Gold.copy(alpha = 0.45f)
                else -> InkSoft
            },
        ),
        tonalElevation = if (isDragging) 12.dp else 0.dp,
        shadowElevation = if (isDragging) 16.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = vanishAlpha
                scaleX = vanishScale * itemScale
                scaleY = vanishScale * itemScale
                translationY = animatedTranslationY
                rotationZ = itemRotation
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(start = 2.dp, end = 2.dp, top = if (isCompact) 4.dp else 6.dp, bottom = if (isCompact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (isCompact) 22.dp else 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isCustom) Gold else Color.Transparent),
            )

            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Hold to reorder",
                tint = if (isCustom) Gold.copy(alpha = 0.75f) else CreamMuted.copy(alpha = 0.45f),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(18.dp),
            )

            Text(
                ordinal.toString().padStart(2, '0'),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCustom) Gold else CreamMuted.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(20.dp),
            )

            Spacer(Modifier.width(8.dp))

            track?.let {
                Artwork(it, Modifier.size(if (isCompact) 44.dp else 56.dp))
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    color = Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isCompact) {
                    Text(
                        "${track?.effectiveArtist ?: "Unknown"} · ${track?.album ?: "Album"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CreamMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null) {
                        Text(
                            track.qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Gold.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.width(6.dp))

            if (track != null && track.durationMs > 0) {
                Text(
                    formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = CreamMuted,
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove from queue",
                    tint = CreamMuted.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryItemRow(
    item: QueueItemState,
    isCompact: Boolean,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
) {
    val track = item.track
    Surface(
        color = InkElevated.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, InkSoft.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(start = 10.dp, end = 2.dp, top = if (isCompact) 4.dp else 6.dp, bottom = if (isCompact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            track?.let {
                Artwork(it, Modifier.size(if (isCompact) 40.dp else 48.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Untitled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CreamMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isCompact) {
                    Text(
                        track?.effectiveArtist ?: "Unknown",
                        style = MaterialTheme.typography.labelSmall,
                        color = CreamMuted.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = "Add back to queue",
                    tint = Gold.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun QueueCustomizationDialog(
    audioConfig: AudioConfig,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = InkElevated,
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Customize Queue",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Gold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = CreamMuted)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Queue item style",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cream,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AudioSettingsManager.setQueueCompactMode(false) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = !audioConfig.queueCompactMode,
                        onClick = { AudioSettingsManager.setQueueCompactMode(false) },
                        colors = RadioButtonDefaults.colors(selectedColor = Gold),
                    )
                    Text(
                        "Detailed Hi-Fi view (with specs)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cream,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AudioSettingsManager.setQueueCompactMode(true) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = audioConfig.queueCompactMode,
                        onClick = { AudioSettingsManager.setQueueCompactMode(true) },
                        colors = RadioButtonDefaults.colors(selectedColor = Gold),
                    )
                    Text(
                        "Compact view (more songs visible)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cream,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Counter badge",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Cream,
                        )
                        Text(
                            "Shows the number of tracks on the queue button",
                            style = MaterialTheme.typography.bodySmall,
                            color = CreamMuted,
                        )
                    }
                    Switch(
                        checked = audioConfig.showQueueBadge,
                        onCheckedChange = { AudioSettingsManager.setShowQueueBadge(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Ink,
                            checkedTrackColor = Gold,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Show played tracks",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Cream,
                        )
                        Text(
                            "Keep the history in view to add tracks back",
                            style = MaterialTheme.typography.bodySmall,
                            color = CreamMuted,
                        )
                    }
                    Switch(
                        checked = audioConfig.queueShowHistory,
                        onCheckedChange = { AudioSettingsManager.setQueueShowHistory(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Ink,
                            checkedTrackColor = Gold,
                        ),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TrackOptionsMenu(
    track: Track,
    controller: MediaController?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onInspect: () -> Unit,
    onMessage: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(InkElevated),
    ) {
        DropdownMenuItem(
            text = { Text("Play next", color = Cream) },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = Gold)
            },
            onClick = {
                val msg = QueueActions.playNext(controller, track)
                onMessage(msg)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Add to custom queue", color = Cream) },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Gold)
            },
            onClick = {
                val msg = QueueActions.addToQueue(controller, track)
                onMessage(msg)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Technical info / Metadata", color = Cream) },
            leadingIcon = {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = CreamMuted)
            },
            onClick = {
                onInspect()
                onDismiss()
            },
        )
    }
}
