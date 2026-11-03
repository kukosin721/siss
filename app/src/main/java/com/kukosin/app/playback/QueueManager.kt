package com.kukosin.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.kukosin.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestiona el orden real de reproducción de la cola.
 *
 * La lista de medios del reproductor es siempre la fuente de verdad:
 *
 *     [ ya reproducidas ] [ actual ] [ cola personalizada ] [ cola principal ]
 *
 * El modo aleatorio se materializa: al activarlo se reordena la cola de verdad, de modo
 * que la vista de cola muestra exactamente el orden en el que sonarán las canciones.
 *
 * La cola personalizada son las canciones añadidas a mano. Se reproducen justo después
 * del tema actual y, al terminar, la reproducción continúa con la cola principal.
 */
object QueueManager {

    private var controller: MediaController? = null
    private var baseOrder: List<MediaItem> = emptyList()
    private var lastIndex: Int = -1

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /** Número de canciones pendientes que pertenecen a la cola personalizada. */
    private val _customCount = MutableStateFlow(0)
    val customCount: StateFlow<Int> = _customCount.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = controller ?: return
            val index = player.currentMediaItemIndex
            val delta = index - lastIndex
            lastIndex = index
            if (
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                when {
                    delta > 0 -> skipCustom(delta)
                    delta < 0 -> resetCustom()
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            controller?.let { lastIndex = it.currentMediaItemIndex }
        }
    }

    fun attach(controller: MediaController) {
        if (this.controller === controller) return
        this.controller?.removeListener(listener)
        this.controller = controller
        lastIndex = controller.currentMediaItemIndex
        controller.shuffleModeEnabled = false
        controller.addListener(listener)
    }

    fun detach(controller: MediaController) {
        if (this.controller !== controller) return
        controller.removeListener(listener)
        this.controller = null
    }

    /** Reemplaza la cola principal y empieza a reproducir desde [index]. */
    fun playTracks(controller: MediaController, tracks: List<Track>, index: Int) {
        playItems(controller, tracks.map(Track::toMediaItem), index)
    }

    /**
     * Prepara la biblioteca en el reproductor en pausa si aún no hay nada cargado (estilo Poweramp),
     * para que la pantalla principal del reproductor tenga canción y carátula listas al abrir.
     */
    fun cueTracksIfEmpty(controller: MediaController, tracks: List<Track>) {
        if (tracks.isEmpty() || controller.mediaItemCount > 0) return
        val items = tracks.map(Track::toMediaItem)
        baseOrder = items
        resetCustom()
        _shuffleEnabled.value = false
        controller.shuffleModeEnabled = false
        controller.playWhenReady = false
        controller.setMediaItems(items, 0, 0L)
        controller.prepare()
    }

    fun playItems(controller: MediaController, items: List<MediaItem>, index: Int) {
        if (items.isEmpty()) return
        val safeIndex = index.coerceIn(0, items.lastIndex)
        baseOrder = items
        resetCustom()
        controller.shuffleModeEnabled = false

        val shuffled = _shuffleEnabled.value
        val ordered = if (shuffled) {
            listOf(items[safeIndex]) + items.filterIndexed { i, _ -> i != safeIndex }.shuffled()
        } else {
            items
        }
        controller.setMediaItems(ordered, if (shuffled) 0 else safeIndex, 0L)
        controller.prepare()
        controller.play()
    }

    /**
     * Añade canciones a la cola personalizada: una sección aparte que suena antes de
     * retomar la cola principal. Devuelve cuántas se añadieron.
     */
    fun addToCustomQueue(
        controller: MediaController,
        items: List<MediaItem>,
        playNext: Boolean,
    ): Int {
        if (items.isEmpty()) return 0
        if (controller.mediaItemCount == 0) {
            playItems(controller, items, 0)
            return items.size
        }

        val current = controller.currentMediaItemIndex
        val custom = _customCount.value
        val insertAt = if (playNext) current + 1 else current + 1 + custom
        controller.addMediaItems(insertAt, items)
        _customCount.value = custom + items.size
        if (controller.playbackState == Player.STATE_IDLE) {
            controller.prepare()
        }
        return items.size
    }

    /** Alterna el modo aleatorio materializando el orden real de la cola principal. */
    fun toggleShuffle(controller: MediaController) {
        val enable = !_shuffleEnabled.value
        val count = controller.mediaItemCount
        val start = customBlockEnd(controller)
        // Con 0 o 1 tema pendiente no hay nada que reordenar: no se toca el flag.
        if (count - start <= 1) return

        _shuffleEnabled.value = enable
        controller.shuffleModeEnabled = false

        val tail = List(count - start) { controller.getMediaItemAt(start + it) }
        if (enable) {
            baseOrder = List(count) { controller.getMediaItemAt(it) }
            applyPermutation(controller, start, tail, tail.shuffled())
        } else {
            val pending = tail.toMutableList()
            val restored = ArrayList<MediaItem>(tail.size)
            for (item in baseOrder) {
                val position = pending.indexOf(item)
                if (position >= 0) restored.add(pending.removeAt(position))
            }
            restored.addAll(pending)
            applyPermutation(controller, start, tail, restored)
        }
    }

    /** Quita una canción pendiente usando su posición relativa al tema actual. */
    fun removePending(controller: MediaController, relativeIndex: Int) {
        if (relativeIndex < 0) return
        val absolute = controller.currentMediaItemIndex + 1 + relativeIndex
        if (absolute >= controller.mediaItemCount) return
        if (relativeIndex < _customCount.value) {
            _customCount.value = (_customCount.value - 1).coerceAtLeast(0)
        }
        controller.removeMediaItem(absolute)
    }

    /** Mueve una canción pendiente dentro del orden de reproducción. */
    fun movePending(controller: MediaController, fromRelative: Int, toRelative: Int) {
        if (fromRelative == toRelative || fromRelative < 0 || toRelative < 0) return
        val start = controller.currentMediaItemIndex + 1
        val count = controller.mediaItemCount
        if (start + fromRelative >= count || start + toRelative >= count) return
        controller.moveMediaItem(start + fromRelative, start + toRelative)
    }

    /** Quita todo lo que queda por sonar (cola personalizada + cola principal). */
    fun clearUpcoming(controller: MediaController) {
        val current = controller.currentMediaItemIndex
        val count = controller.mediaItemCount
        resetCustom()
        if (count > current + 1) {
            controller.removeMediaItems(current + 1, count)
        }
    }

    private fun customBlockEnd(controller: MediaController): Int =
        controller.currentMediaItemIndex + 1 + _customCount.value

    private fun skipCustom(count: Int) {
        if (_customCount.value == 0) return
        _customCount.value = (_customCount.value - count).coerceAtLeast(0)
    }

    private fun resetCustom() {
        if (_customCount.value != 0) _customCount.value = 0
    }

    /** Aplica [to] (una permutación de [from]) con una secuencia de movimientos. */
    private fun applyPermutation(
        controller: MediaController,
        start: Int,
        from: List<MediaItem>,
        to: List<MediaItem>,
    ) {
        val working = from.toMutableList()
        for (i in to.indices) {
            val wanted = to[i]
            val position = working.indexOfFirst { it == wanted }
            if (position > i) {
                controller.moveMediaItem(start + position, start + i)
                working.add(i, working.removeAt(position))
            }
        }
    }
}
