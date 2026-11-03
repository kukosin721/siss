package com.kukosin.app.ui

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.MockMakers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

class LibrarySortTest {

    private val uri: Uri = mock(Uri::class.java, withSettings().mockMaker(MockMakers.SUBCLASS))

    private fun track(
        id: Long,
        title: String,
        artist: String = "Artista",
        album: String = "Álbum",
        durationMs: Long = 0L,
        year: Int = 0,
        trackNumber: Int = 0,
    ) = com.kukosin.app.data.Track(
        id = id,
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        year = year,
        trackNumber = trackNumber,
    )

    @Test
    fun titleOrderIgnoresCase() {
        val tracks = listOf(track(1, "bravo"), track(2, "Alfa"), track(3, "casa"))
        val sorted = sortTracks(tracks, LibrarySort.TITULO).map { it.title }
        assertEquals(listOf("Alfa", "bravo", "casa"), sorted)
    }

    @Test
    fun artistOrderGroupsByArtistThenAlbumThenTrackNumber() {
        val tracks = listOf(
            track(1, "c", artist = "Zeta"),
            track(2, "b", artist = "alfa", album = "Segundo", trackNumber = 2),
            track(3, "a", artist = "alfa", album = "Primero", trackNumber = 1),
        )
        val sorted = sortTracks(tracks, LibrarySort.ARTISTA).map { it.id }
        assertEquals(listOf(3L, 2L, 1L), sorted)
    }

    @Test
    fun albumOrderUsesTrackNumberInsideEachAlbum() {
        val tracks = listOf(
            track(1, "dos", album = "Uno", trackNumber = 2),
            track(2, "uno", album = "Uno", trackNumber = 1),
            track(3, "otro", album = "Cero", trackNumber = 9),
        )
        val sorted = sortTracks(tracks, LibrarySort.ALBUM).map { it.id }
        assertEquals(listOf(3L, 2L, 1L), sorted)
    }

    @Test
    fun durationOrderIsAscending() {
        val tracks = listOf(track(1, "a", durationMs = 900), track(2, "b", durationMs = 100), track(3, "c", durationMs = 500))
        assertEquals(listOf(2L, 3L, 1L), sortTracks(tracks, LibrarySort.DURACION).map { it.id })
    }

    @Test
    fun yearOrderIsNewestFirst() {
        val tracks = listOf(track(1, "a", year = 1999, trackNumber = 1), track(2, "b", year = 2024, trackNumber = 2))
        assertEquals(listOf(2L, 1L), sortTracks(tracks, LibrarySort.ANO).map { it.id })
    }

    @Test
    fun onlyAlphabeticalOrdersShowTheIndex() {
        assertEquals(true, LibrarySort.TITULO.isAlphabetical)
        assertEquals(true, LibrarySort.ARTISTA.isAlphabetical)
        assertEquals(true, LibrarySort.ALBUM.isAlphabetical)
        assertEquals(false, LibrarySort.DURACION.isAlphabetical)
        assertEquals(false, LibrarySort.ANO.isAlphabetical)
    }

    @Test
    fun firstLetterStripsAccentsAndFallsBackToHash() {
        assertEquals('A', firstLetterOf("Ángel"))
        assertEquals('E', firstLetterOf("  étnico"))
        assertEquals('Z', firstLetterOf("zorro"))
        assertEquals('#', firstLetterOf("123 abc"))
        assertEquals('#', firstLetterOf("   "))
        assertEquals('#', firstLetterOf(""))
    }

    @Test
    fun letterSectionsPointToTheFirstTrackOfEachLetter() {
        val tracks = listOf(
            track(1, "Alfa"),
            track(2, "Arena"),
            track(3, "Bravo"),
            track(4, "123"),
        )
        val (letters, positions) = letterSections(tracks) { it.title }
        assertEquals(listOf('A', 'B', '#'), letters)
        assertEquals(0, positions['A'])
        assertEquals(2, positions['B'])
        assertEquals(3, positions['#'])
    }

    @Test
    fun letterSectionsUseTheChosenSortKey() {
        val tracks = listOf(
            track(1, "tema uno", artist = "Zeta"),
            track(2, "tema dos", artist = "Ana"),
        )
        val (letters, positions) = letterSections(tracks) { it.effectiveArtist }
        assertEquals(listOf('Z', 'A'), letters)
        assertEquals(1, positions['A'])
    }
}
