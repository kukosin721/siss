package com.kukosin.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Origen del morph de carátula cuando se toca una canción en una lista.
 *
 * Antes, abrir el reproductor desde la biblioteca siempre hacía volar la carátula
 * desde el mini reproductor: la canción que tocabas quedaba en el medio de la
 * pantalla y la portada aparecía desde otro lado. Ahora la fila publica el
 * rectángulo de SU carátula (el que el dedo acaba de tocar) y el reproductor crece
 * desde ahí hasta su posición final, que es el espejo exacto de la animación de
 * cierre (carátula grande → miniatura).
 *
 * Es un objeto mutable a propósito: se escribe en el `onClick` de la fila y se lee
 * dentro de la capa gráfica del reproductor, sin recomponer toda la pantalla. Se
 * limpia cuando la animación termina para que el cierre vuelva al mini reproductor.
 */
internal class TrackPressOrigin {
    private var rect: Rect? = null
    private var stampNanos: Long = 0L

    fun publish(bounds: Rect) {
        rect = bounds
        stampNanos = System.nanoTime()
    }

    /**
     * Devuelve el rectángulo sólo si la fila se tocó hace un instante. Así una lista
     * usada para "reproducir sin abrir" no deja un origen viejo que después haga que
     * la carátula aparezca desde una fila que ya no está en pantalla.
     */
    fun consumeRecent(maxAgeNanos: Long = 2_000_000_000L): Rect? {
        val current = rect
        rect = null
        return current?.takeIf { System.nanoTime() - stampNanos in 0..maxAgeNanos }
    }

    fun clear() {
        rect = null
    }
}

/**
 * `null` significa "esta pantalla no participa": las listas que viven en ventanas
 * flotantes (hojas modales) pueden publicar coordenadas de otra ventana, así que
 * sólo se usa donde la raíz y la fila comparten el mismo espacio.
 */
internal val LocalTrackPressOrigin = staticCompositionLocalOf<TrackPressOrigin?> { null }
