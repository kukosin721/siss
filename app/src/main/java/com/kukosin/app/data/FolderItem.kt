package com.kukosin.app.data

data class FolderNode(
    val name: String,
    val path: String,
    val subfolders: List<FolderNode> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val totalTracksCount: Int
        get() = tracks.size + subfolders.sumOf { it.totalTracksCount }

    val directItemsCount: Int
        get() = subfolders.size + tracks.size
}

data class AlbumGroup(
    val name: String,
    val artist: String,
    val albumId: Long,
    val tracks: List<Track>,
    val year: Int = 0,
) {
    val totalDurationMs: Long
        get() = tracks.sumOf { it.durationMs }

    val artworkUri
        get() = tracks.firstOrNull()?.artworkUri

    val isHiRes: Boolean
        get() = tracks.any { it.isHiRes }
}

data class ArtistGroup(
    val name: String,
    val albums: List<AlbumGroup>,
    val tracks: List<Track>,
) {
    val trackCount: Int
        get() = tracks.size

    val albumCount: Int
        get() = albums.size
}

data class TagCategoryGroup(
    val name: String,
    val subtitle: String,
    val tracks: List<Track>,
) {
    val trackCount: Int
        get() = tracks.size

    val totalDurationMs: Long
        get() = tracks.sumOf { it.durationMs }

    val artworkUri
        get() = tracks.firstOrNull()?.artworkUri

    val isHiRes: Boolean
        get() = tracks.any { it.isHiRes }
}
