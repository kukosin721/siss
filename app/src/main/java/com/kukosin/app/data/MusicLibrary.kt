package com.kukosin.app.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MusicLibrary {
    private val audioExtensions = listOf(
        "flac", "wav", "aiff", "aif", "alac", "m4a", "aac", "mp3",
        "ogg", "oga", "opus", "dsf", "dff", "ape", "wv", "tta", "tak",
        "mka", "mlp", "thd", "dts", "ac3", "eac3", "wma",
    )

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val byKey = LinkedHashMap<String, Track>()

        fun addTrack(track: Track) {
            val key = when {
                track.filePath.isNotBlank() -> track.filePath.lowercase()
                else -> "${track.title.lowercase()}_${track.artist.lowercase()}_${track.durationMs}"
            }
            byKey.putIfAbsent(key, track)
        }

        queryAudio(context).forEach { addTrack(it) }
        queryFiles(context).forEach { addTrack(it) }

        // Lo ya sondeado se aplica acá mismo: son dos `stat` por archivo (sin abrirlos),
        // así la biblioteca arranca con duración/sample rate/bitrate correctos y la UI no
        // tiene que esperar al sondeo completo para mostrar la lista bien.
        ensureCacheLoaded(context)
        val cached = byKey.mapValues { (_, track) ->
            val stored = probeCache[track.uri.toString()] ?: return@mapValues track
            val stamp = probeStamps[track.uri.toString()]
            val current = LibraryCacheStore.fingerprintFromDisk(track)
            // Misma validación que `probeCached`: si el archivo cambió, no se usa.
            if (stamp != null && current != null && !stamp.matches(current)) track
            else LibraryCacheStore.applyCached(track, stored)
        }
        byKey.clear()
        byKey.putAll(cached)

        byKey.values.sortedBy { it.title.lowercase() }
    }

    // --- Caché de sondeo: memoria + disco -------------------------------------------
    // Sin esto, cada arranque volvía a abrir TODOS los archivos para leer duración,
    // sample rate, profundidad y etiquetas. La caché de disco vive en filesDir.
    private const val PROBE_CONCURRENCY = 4
    private const val SAVE_INTERVAL_MS = 10_000L

    private val probeCache = ConcurrentHashMap<String, Track>()

    /** Firma de disco de cada entrada de [probeCache] (para invalidar una por una). */
    private val probeStamps = ConcurrentHashMap<String, DiskFingerprint>()

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var lastSaveAtMs = 0L

    /**
     * Sondea **sólo lo que falta**.
     *
     * Con la caché aplicada en [scan], acá ya llegan las pistas completas: antes se abría
     * cada archivo otra vez en cada arranque (decenas de segundos con una biblioteca
     * grande) aunque no hubiera cambiado nada.
     */
    suspend fun enrich(context: Context, tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        ensureCacheLoaded(context)
        if (tracks.isEmpty()) return@withContext tracks
        val pending = tracks.indices.filter { index ->
            val track = tracks[index]
            track.sampleRate <= 0 || track.durationMs <= 0 || track.bitDepth <= 0
        }
        if (pending.isEmpty()) return@withContext tracks
        val enriched = arrayOfNulls<Track>(tracks.size)
        val permits = Semaphore(PROBE_CONCURRENCY)
        coroutineScope {
            pending.forEach { index ->
                val track = tracks[index]
                launch {
                    permits.withPermit {
                        // `probeCached` devuelve la pista técnica cacheada; sus etiquetas
                        // están vacías a propósito, así que se combinan con las del escaneo.
                        enriched[index] = LibraryCacheStore.applyCached(track, probeCached(context, track))
                    }
                }
            }
        }
        persistCacheIfDue(context)
        List(tracks.size) { index -> enriched[index] ?: tracks[index] }
    }

    private suspend fun ensureCacheLoaded(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        // `load` ya descarta las entradas cuyo archivo cambió: acá sólo se rellena.
        val stored = LibraryCacheStore.load(context)
        if (stored.isNotEmpty()) {
            probeCache.putAll(stored)
            stored.values.forEach { track ->
                LibraryCacheStore.fingerprintFromDisk(track)?.let { probeStamps[track.uri.toString()] = it }
            }
        }
    }

    private fun persistCacheIfDue(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSaveAtMs < SAVE_INTERVAL_MS) return
        lastSaveAtMs = now
        LibraryCacheStore.save(context, probeCache)
    }

    /**
     * Sondeo con caché **validada por archivo**.
     *
     * La clave sigue siendo la uri, pero una entrada sólo se reutiliza si el tamaño y la
     * fecha de modificación del archivo coinciden con los del momento en que se sondeó.
     * Si el usuario reemplazó el archivo (mismo nombre, otro contenido), se vuelve a
     * sondear en vez de mostrar duración/bitrate/carátula del anterior.
     */
    private fun probeCached(context: Context, track: Track): Track {
        val key = track.uri.toString()
        val fingerprint = LibraryCacheStore.fingerprintFromDisk(track)
        val cached = probeCache[key]
        if (cached != null) {
            val cachedStamp = probeStamps[key]
            // Sin firma (streams, SAF sin path) se confía en la uri: no hay con qué validar.
            if (fingerprint == null || cachedStamp == null || cachedStamp.matches(fingerprint)) {
                return cached
            }
            probeCache.remove(key)
            probeStamps.remove(key)
        }
        val probed = runCatching { probe(context, track) }.getOrDefault(track)
        probeCache[key] = probed
        if (fingerprint != null) probeStamps[key] = fingerprint
        return probed
    }

    /** "Rescan": tira la caché en memoria y en disco. */
    fun invalidateProbeCache(context: Context?) {
        probeCache.clear()
        probeStamps.clear()
        cacheLoaded = false
        lastSaveAtMs = 0L
        if (context != null) LibraryCacheStore.clear(context)
    }

    fun buildFolderTree(tracks: List<Track>): FolderNode {
        // Construimos también los directorios intermedios sin archivos de audio. De otra
        // manera un disco en Música/Artista/Álbum desaparecía si Artista estaba vacío.
        class MutableFolder(val name: String, val path: String) {
            val children = linkedMapOf<String, MutableFolder>()
            val tracks = mutableListOf<Track>()
        }

        val rootPath = "/storage/emulated/0"
        val root = MutableFolder("Internal storage", rootPath)
        tracks.forEach { track ->
            val path = track.filePath.takeIf { it.isNotBlank() }?.let { File(it).parent }
            if (path.isNullOrBlank()) {
                val unknown = root.children.getOrPut("<no-path>") {
                    MutableFolder("Unknown location", "<no-path>")
                }
                unknown.tracks.add(track)
                return@forEach
            }

            val internal = path == rootPath || path.startsWith("$rootPath/")
            var folder = if (internal) root else root.children.getOrPut("<other>") {
                MutableFolder("Other locations", "<other>")
            }
            val parts = (if (internal) path.removePrefix(rootPath) else path)
                .split('/').filter { it.isNotBlank() }
            var currentPath = if (internal) rootPath else ""
            parts.forEach { part ->
                currentPath += "/$part"
                val nodePath = currentPath
                folder = folder.children.getOrPut(nodePath) { MutableFolder(part, nodePath) }
            }
            folder.tracks.add(track)
        }

        fun freeze(folder: MutableFolder): FolderNode = FolderNode(
            name = folder.name,
            path = folder.path,
            subfolders = folder.children.values.map { freeze(it) }.sortedBy { it.name.lowercase() },
            tracks = folder.tracks.sortedWith(
                compareBy<Track> { it.trackNumber.takeIf { number -> number > 0 } ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() },
            ),
        )
        return freeze(root)
    }

    fun groupAlbums(tracks: List<Track>): List<AlbumGroup> {
        return tracks.groupBy { it.album.ifBlank { "Unknown album" } to it.effectiveAlbumArtist }
            .map { (albumKey, albumTracks) ->
                val albumName = albumKey.first
                val first = albumTracks.first()
                val sortedTracks = albumTracks.sortedWith(
                    compareBy<Track> { it.discNumber.takeIf { d -> d > 0 } ?: 1 }
                        .thenBy { it.trackNumber.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() },
                )
                AlbumGroup(
                    name = albumName,
                    artist = first.effectiveAlbumArtist,
                    albumId = first.albumId,
                    tracks = sortedTracks,
                    year = albumTracks.map { it.year }.firstOrNull { it > 0 } ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun groupArtists(tracks: List<Track>): List<ArtistGroup> {
        return tracks.groupBy { it.effectiveArtist }
            .map { (artistName, artistTracks) ->
                val albums = groupAlbums(artistTracks)
                ArtistGroup(
                    name = artistName,
                    albums = albums,
                    tracks = artistTracks.sortedBy { it.title.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun groupAlbumArtists(tracks: List<Track>): List<ArtistGroup> {
        return tracks.groupBy { it.effectiveAlbumArtist }
            .map { (artistName, artistTracks) ->
                val albums = groupAlbums(artistTracks)
                ArtistGroup(
                    name = artistName,
                    albums = albums,
                    tracks = artistTracks.sortedBy { it.title.lowercase() },
                )
            }
            .sortedWith(
                compareBy<ArtistGroup> { it.name == "Unknown artist" }
                    .thenBy { it.name.lowercase() },
            )
    }

    fun groupGenres(tracks: List<Track>): List<TagCategoryGroup> {
        return tracks.groupBy { it.genre.trim().ifBlank { "No genre" } }
            .map { (genreName, genreTracks) ->
                val sorted = genreTracks.sortedBy { it.title.lowercase() }
                val totalMin = (sorted.sumOf { it.durationMs } / 60_000L).coerceAtLeast(1L)
                TagCategoryGroup(
                    name = genreName,
                    subtitle = "${sorted.size} songs · $totalMin min",
                    tracks = sorted,
                )
            }
            .sortedWith(
                compareBy<TagCategoryGroup> { it.name == "No genre" }
                    .thenBy { it.name.lowercase() },
            )
    }

    fun groupYears(tracks: List<Track>): List<TagCategoryGroup> {
        return tracks.groupBy { if (it.year > 0) it.year.toString() else "Unknown year" }
            .map { (yearLabel, yearTracks) ->
                val sorted = yearTracks.sortedBy { it.title.lowercase() }
                val albumCount = sorted.map { it.album.lowercase() }.distinct().size
                TagCategoryGroup(
                    name = yearLabel,
                    subtitle = "$albumCount albums · ${sorted.size} songs",
                    tracks = sorted,
                )
            }
            .sortedWith(
                compareBy<TagCategoryGroup> { it.name == "Unknown year" }
                    .thenByDescending { it.name.toIntOrNull() ?: 0 },
            )
    }

    fun groupComposers(tracks: List<Track>): List<TagCategoryGroup> {
        return tracks.groupBy { it.composer.trim().ifBlank { "Unknown composer" } }
            .map { (composerName, composerTracks) ->
                val sorted = composerTracks.sortedBy { it.title.lowercase() }
                TagCategoryGroup(
                    name = composerName,
                    subtitle = "${sorted.size} songs",
                    tracks = sorted,
                )
            }
            .sortedWith(
                compareBy<TagCategoryGroup> { it.name == "Unknown composer" }
                    .thenBy { it.name.lowercase() },
            )
    }

    private fun queryAudio(context: Context): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projections = listOf(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.COMPOSER,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE,
            ),
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
            ),
        )
        return queryWithFallback(context, collection, projections) { cursor ->
            readTrack(
                cursor = cursor,
                collection = collection,
                idColumn = MediaStore.Audio.Media._ID,
            )
        }
    }

    private fun queryFiles(context: Context): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val likeClauses = audioExtensions.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }
        val args = audioExtensions.map { "%.$it" }.toTypedArray()
        val projections = listOf(
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.TITLE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DURATION,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
            ),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
            ),
        )
        return queryWithFallback(
            context = context,
            collection = collection,
            projections = projections,
            selection = likeClauses,
            selectionArgs = args,
        ) { cursor ->
            readTrack(
                cursor = cursor,
                collection = collection,
                idColumn = MediaStore.Files.FileColumns._ID,
            )
        }
    }

    private fun queryWithFallback(
        context: Context,
        collection: Uri,
        projections: List<Array<String>>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        mapper: (Cursor) -> Track?,
    ): List<Track> {
        var lastError: Exception? = null
        for (projection in projections) {
            try {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val tracks = ArrayList<Track>(cursor.count.coerceAtLeast(0))
                    while (cursor.moveToNext()) {
                        mapper(cursor)?.let(tracks::add)
                    }
                    return tracks
                }
            } catch (error: SecurityException) {
                return emptyList()
            } catch (error: IllegalArgumentException) {
                lastError = error
            } catch (error: UnsupportedOperationException) {
                lastError = error
            }
        }
        lastError?.printStackTrace()
        return emptyList()
    }

    private fun readTrack(cursor: Cursor, collection: Uri, idColumn: String): Track? {
        val id = cursor.longOrNull(idColumn) ?: return null
        val fileName = cursor.stringOrEmpty(MediaStore.MediaColumns.DISPLAY_NAME)
        val mime = cursor.stringOrEmpty(MediaStore.MediaColumns.MIME_TYPE)
        val duration = cursor.longOrNull(MediaStore.MediaColumns.DURATION) ?: 0L
        if (!isMusic(fileName, mime, duration)) return null
        
        val title = cursor.stringOrEmpty(MediaStore.MediaColumns.TITLE)
            .ifBlank { fileName.substringBeforeLast('.').ifBlank { "Untitled" } }
            
        val rawTrack = cursor.intOrZero(MediaStore.Audio.Media.TRACK)
        val trackNum = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack
        val discNum = if (rawTrack >= 1000) rawTrack / 1000 else 0

        val filePath = cursor.stringOrEmpty(MediaStore.MediaColumns.DATA)
        val fileSize = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: File(filePath).takeIf { it.exists() }?.length() ?: 0L

        return Track(
            id = id,
            uri = ContentUris.withAppendedId(collection, id),
            title = title,
            artist = cursor.stringOrEmpty(MediaStore.Audio.Media.ARTIST)
                .takeIf { it.isNotBlank() && it != "<unknown>" }
                ?: "Unknown artist",
            album = cursor.stringOrEmpty(MediaStore.Audio.Media.ALBUM)
                .ifBlank { "Unknown album" },
            albumArtist = cursor.stringOrEmpty("album_artist"),
            durationMs = duration,
            albumId = cursor.longOrNull(MediaStore.Audio.Media.ALBUM_ID) ?: 0L,
            bitrate = cursor.intOrZero(MediaStore.Audio.Media.BITRATE),
            sampleRate = 0,
            bitDepth = 0,
            channels = 0,
            mimeType = mime,
            fileName = fileName,
            year = cursor.intOrZero(MediaStore.Audio.Media.YEAR),
            trackNumber = trackNum,
            discNumber = discNum,
            composer = cursor.stringOrEmpty(MediaStore.Audio.Media.COMPOSER),
            filePath = filePath,
            fileSize = fileSize,
        )
    }

    private fun isMusic(fileName: String, mime: String, durationMs: Long): Boolean {
        if (mime.startsWith("video/", ignoreCase = true)) return false
        if (audioExtensions.any { fileName.endsWith(".$it", ignoreCase = true) }) return true
        return mime.startsWith("audio/", ignoreCase = true) && durationMs >= 1500
    }

    private fun probe(context: Context, track: Track): Track {
        val info = runCatching {
            MediaInfoBuilder().from(context, track.uri).build()
        }.getOrNull()
        val audio = info?.audioStreams?.firstOrNull()
        
        var sampleRate = audio?.sampleRate ?: track.sampleRate
        var channels = audio?.channels ?: track.channels
        var bitDepth = bitDepthFromSampleFormat(audio?.sampleFormat).takeIf { it > 0 } ?: track.bitDepth
        var duration = info?.duration?.takeIf { it > 0 } ?: track.durationMs
        var bitrate = audio?.bitRate?.toInt()?.takeIf { it > 0 } ?: track.bitrate
        var mime = audio?.codecName?.takeIf { it.isNotBlank() }?.let { "audio/$it" } ?: track.mimeType

        var albumArtist = track.albumArtist
        var genre = track.genre
        var composer = track.composer
        var comment = track.comment
        var trackNum = track.trackNumber
        var discNum = track.discNumber
        var yearVal = track.year

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
            
                val mmrArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                if (!mmrArtist.isNullOrBlank()) albumArtist = mmrArtist
            
                val mmrGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                if (!mmrGenre.isNullOrBlank()) genre = mmrGenre

                val mmrComposer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                if (!mmrComposer.isNullOrBlank()) composer = mmrComposer

                val mmrTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                if (!mmrTrack.isNullOrBlank()) {
                    val parsed = mmrTrack.substringBefore('/').toIntOrNull()
                    if (parsed != null && parsed > 0) trackNum = parsed
                }

                val mmrDisc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                if (!mmrDisc.isNullOrBlank()) {
                    val parsed = mmrDisc.substringBefore('/').toIntOrNull()
                    if (parsed != null && parsed > 0) discNum = parsed
                }

                val mmrYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                if (!mmrYear.isNullOrBlank()) {
                    val parsed = mmrYear.take(4).toIntOrNull()
                    if (parsed != null && parsed > 0) yearVal = parsed
                }

                val mmrBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                if (bitrate <= 0 && mmrBitrate != null && mmrBitrate > 0) {
                    bitrate = mmrBitrate
                }
            } finally {
                // Se libera siempre: si setDataSource lanza, antes quedaba un
                // retriever vivo por cada archivo (fuga de descriptores en escaneos grandes).
                runCatching { retriever.release() }
            }
        }

        return track.copy(
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            durationMs = duration,
            bitrate = bitrate,
            mimeType = mime,
            albumArtist = albumArtist,
            genre = genre,
            composer = composer,
            comment = comment,
            trackNumber = trackNum,
            discNumber = discNum,
            year = yearVal,
        )
    }

    private fun Cursor.stringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0) return ""
        return getString(index).orEmpty()
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Cursor.intOrZero(column: String): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return 0
        return getInt(index)
    }
}
