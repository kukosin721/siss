package com.kukosin.app.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.MockMakers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

class MusicLibraryGroupingTest {
    private val uri: Uri = mock(Uri::class.java, withSettings().mockMaker(MockMakers.SUBCLASS))

    private fun track(
        id: Long,
        title: String,
        path: String = "",
        album: String = "Álbum",
        artist: String = "Artista",
        albumArtist: String = "",
        genre: String = "",
        year: Int = 0,
        composer: String = "",
        trackNumber: Int = 0,
    ) = Track(
        id = id,
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        filePath = path,
        genre = genre,
        year = year,
        composer = composer,
        trackNumber = trackNumber,
    )

    @Test
    fun foldersIncludeEmptyAncestorsExternalStorageAndUnknownLocations() {
        val tracks = listOf(
            track(1, "Primera", "/storage/emulated/0/Music/Artista/Álbum/01.flac"),
            track(2, "Segunda", "/storage/emulated/0/Music/Artista/Álbum/Disco 2/02.flac"),
            track(3, "Externa", "/storage/ABCD/Music/03.flac"),
            track(4, "Sin ruta"),
        )
        val root = MusicLibrary.buildFolderTree(tracks)
        val album = root.subfolders.first { it.name == "Music" }
            .subfolders.first { it.name == "Artista" }
            .subfolders.first { it.name == "Álbum" }
        assertEquals(listOf("Primera"), album.tracks.map { it.title })
        assertEquals(listOf("Segunda"), album.subfolders.single().tracks.map { it.title })
        assertEquals(4, root.totalTracksCount)
        assertTrue(root.subfolders.any { it.name == "Other locations" && it.totalTracksCount == 1 })
        assertTrue(root.subfolders.any { it.name == "Unknown location" && it.totalTracksCount == 1 })
    }

    @Test
    fun albumNamesAreNotMergedAcrossDifferentAlbumArtists() {
        val tracks = listOf(
            track(1, "Segundo", album = "Greatest Hits", artist = "A", trackNumber = 2),
            track(2, "Primero", album = "Greatest Hits", artist = "A", trackNumber = 1),
            track(3, "Otro", album = "Greatest Hits", artist = "B"),
        )
        val albums = MusicLibrary.groupAlbums(tracks)
        assertEquals(2, albums.size)
        assertEquals(listOf("Primero", "Segundo"), albums.first { it.artist == "A" }.tracks.map { it.title })
    }

    @Test
    fun groupsUseTagsAndPutUnknownValuesLast() {
        val tracks = listOf(
            track(1, "Uno", artist = "Cantante A", albumArtist = "Varios", genre = " Rock ", year = 2020, composer = "Compositor"),
            track(2, "Dos", artist = "Cantante B", albumArtist = "Varios", genre = "Jazz", year = 1999, composer = "Otra persona"),
            track(3, "Tres", artist = "Cantante C"),
        )
        assertEquals(2, MusicLibrary.groupAlbumArtists(tracks).first { it.name == "Varios" }.trackCount)
        assertEquals(listOf("Jazz", "Rock", "No genre"), MusicLibrary.groupGenres(tracks).map { it.name })
        assertEquals(listOf("2020", "1999", "Unknown year"), MusicLibrary.groupYears(tracks).map { it.name })
        assertEquals("Unknown composer", MusicLibrary.groupComposers(tracks).last().name)
    }
}
