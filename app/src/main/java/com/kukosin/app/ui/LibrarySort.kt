package com.kukosin.app.ui

import com.kukosin.app.data.Track

/**
 * Criterios de orden de la lista de canciones.
 *
 * El orden se aplica en el ViewModel, sobre la lista que se **muestra** y también
 * sobre la que se **manda al reproductor**: si tocás la cuarta canción de la
 * pantalla, suena esa y no otra.
 *
 * Nota honesta: esta rama no guarda la fecha en la que se agregó cada archivo al
 * teléfono (no hay `DATE_ADDED` en el escaneo), así que el criterio "newest" se
 * apoya en el año del tag y el número de pista. Por eso se llama "Year", no
 * "Recent": es lo que realmente hay.
 */
enum class LibrarySort(val label: String, val shortLabel: String) {
    TITULO("Title (A–Z)", "Title"),
    ARTISTA("Artist (A–Z)", "Artist"),
    ALBUM("Album (A–Z)", "Album"),
    DURACION("Duration (shortest first)", "Duration"),
    ANO("Year (newest first)", "Year"),
    ;

    /** Con un orden alfabético la barra lateral A–Z tiene sentido; con los otros, no. */
    val isAlphabetical: Boolean
        get() = this == TITULO || this == ARTISTA || this == ALBUM

    /** Texto del que se saca la letra del índice lateral. */
    fun sortKey(track: Track): String = when (this) {
        TITULO, DURACION, ANO -> track.title
        ARTISTA -> track.effectiveArtist
        ALBUM -> track.album
    }
}

internal fun sortTracks(tracks: List<Track>, order: LibrarySort): List<Track> = when (order) {
    LibrarySort.TITULO -> tracks.sortedBy { it.title.lowercase() }
    LibrarySort.ARTISTA -> tracks.sortedWith(
        compareBy({ it.effectiveArtist.lowercase() }, { it.album.lowercase() }, { it.trackNumber }),
    )
    LibrarySort.ALBUM -> tracks.sortedWith(
        compareBy({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() }),
    )
    LibrarySort.DURACION -> tracks.sortedBy { it.durationMs }
    LibrarySort.ANO -> tracks.sortedWith(
        compareByDescending<Track> { it.year }.thenBy { it.trackNumber }.thenBy { it.title.lowercase() },
    )
}
