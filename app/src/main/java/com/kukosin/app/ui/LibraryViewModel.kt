package com.kukosin.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.kukosin.app.data.analysis.LyricsIndex
import androidx.lifecycle.viewModelScope
import com.kukosin.app.data.AlbumGroup
import com.kukosin.app.data.ArtistGroup
import com.kukosin.app.data.FolderNode
import com.kukosin.app.data.MusicLibrary
import com.kukosin.app.data.StartupCache
import com.kukosin.app.data.TagCategoryGroup
import com.kukosin.app.data.Track
import com.kukosin.app.data.analysis.AudioAnalysis
import com.kukosin.app.data.analysis.AudioAnalyzer
import com.kukosin.app.data.ytm.YtmService
import com.kukosin.app.data.ytm.YtmTrack
import com.kukosin.app.data.ytm.describeYtmError
import com.kukosin.app.playback.AudioConfig
import com.kukosin.app.playback.AudioSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryCategory(val title: String) {
    TODAS_LAS_CANCIONES("All songs"),
    CARPETAS("Folders"),
    ALBUMES("Albums"),
    ARTISTAS("Artists"),
    ARTISTAS_DEL_ALBUM("Album artists"),
    GENEROS("Genres"),
    ANOS("Years"),
    COMPOSITORES("Composers"),
    LISTAS_DE_REPRODUCCION("Playlists"),
    LA_COLA("The queue"),
    FAVORITOS("Favorites"),
    MAS_REPRODUCIDO("Most played"),
}

private val filteredTracksCache =
    object : android.util.LruCache<String, List<Track>>(8) {}

data class LibraryUiState(
    val tracks: List<Track> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val albumArtists: List<ArtistGroup> = emptyList(),
    val genres: List<TagCategoryGroup> = emptyList(),
    val years: List<TagCategoryGroup> = emptyList(),
    val composers: List<TagCategoryGroup> = emptyList(),
    val rootFolder: FolderNode? = null,
    val folderPath: List<FolderNode> = emptyList(),
    val activeCategory: LibraryCategory? = null,
    val selectedAlbum: AlbumGroup? = null,
    val selectedArtist: ArtistGroup? = null,
    val selectedTagGroup: TagCategoryGroup? = null,
    val inspectTrack: Track? = null,
    val ytmTracks: List<YtmTrack> = emptyList(),
    val ytmQuery: String = "",
    val isYtmSearching: Boolean = false,
    val ytmError: String? = null,
    val query: String = "",
    /** La consulta se aplica también sobre la letra de las canciones. */
    val lyricsOnly: Boolean = false,
    /** Letras indexadas / total, para el indicador del buscador. */
    val lyricsIndexed: Int = 0,
    val lyricsIndexing: Boolean = false,
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val error: String? = null,
    val sortMode: LibrarySort = LibrarySort.TITULO,
) {
    val currentFolder: FolderNode?
        get() = folderPath.lastOrNull() ?: rootFolder

    val filteredTracks: List<Track>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return tracks
            filteredTracksCache.get(needle)?.let { return it }
            val lowered = needle.lowercase()
            val result = tracks.filter { track ->
                track.matchesText(lowered) ||
                    // Sólo se consulta la letra si el usuario escribió el modo "letra".
                    (lyricsOnly && LyricsIndex.matches(track.uri, LyricsIndex.normalize(lowered)))
            }
            filteredTracksCache.put(needle, result)
            return result
        }

    val filteredAlbums: List<AlbumGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return albums
            return albums.filter { album ->
                album.name.contains(needle, ignoreCase = true) ||
                    album.artist.contains(needle, ignoreCase = true)
            }
        }

    val filteredArtists: List<ArtistGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return artists
            return artists.filter { artist ->
                artist.name.contains(needle, ignoreCase = true)
            }
        }

    val filteredAlbumArtists: List<ArtistGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return albumArtists
            return albumArtists.filter { artist ->
                artist.name.contains(needle, ignoreCase = true)
            }
        }

    val filteredGenres: List<TagCategoryGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return genres
            return genres.filter { it.name.contains(needle, ignoreCase = true) }
        }

    val filteredYears: List<TagCategoryGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return years
            return years.filter { it.name.contains(needle, ignoreCase = true) }
        }

    val filteredComposers: List<TagCategoryGroup>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return composers
            return composers.filter { it.name.contains(needle, ignoreCase = true) }
        }
}

/**
 * Compara una pista contra una consulta ya en minúsculas.
 *
 * Se hace con `contains(ignoreCase = false)` sobre el texto bajado a minúsculas: es la
 * variante más barata de `String.contains` (no crea un `CharSequence` por comparación,
 * que es lo que hacía `ignoreCase = true` en cada campo y por cada tecla).
 */
private const val QUERY_DEBOUNCE_MS = 90L

/** YouTube Music tarda cientos de ms por consulta: se espera a que el usuario pare. */
private const val YTM_QUERY_DEBOUNCE_MS = 400L

private fun Track.matchesText(loweredNeedle: String): Boolean =
    title.lowercase().contains(loweredNeedle) ||
        artist.lowercase().contains(loweredNeedle) ||
        albumArtist.lowercase().contains(loweredNeedle) ||
        album.lowercase().contains(loweredNeedle) ||
        codecLabel.lowercase().contains(loweredNeedle) ||
        genre.lowercase().contains(loweredNeedle) ||
        composer.lowercase().contains(loweredNeedle)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val tracks = MutableStateFlow<List<Track>>(emptyList())
    private val albums = MutableStateFlow<List<AlbumGroup>>(emptyList())
    private val artists = MutableStateFlow<List<ArtistGroup>>(emptyList())
    private val albumArtists = MutableStateFlow<List<ArtistGroup>>(emptyList())
    private val genres = MutableStateFlow<List<TagCategoryGroup>>(emptyList())
    private val years = MutableStateFlow<List<TagCategoryGroup>>(emptyList())
    private val composers = MutableStateFlow<List<TagCategoryGroup>>(emptyList())
    private val rootFolder = MutableStateFlow<FolderNode?>(null)
    private val folderPath = MutableStateFlow<List<FolderNode>>(emptyList())
    private val activeCategory = MutableStateFlow<LibraryCategory?>(null)
    private val selectedAlbum = MutableStateFlow<AlbumGroup?>(null)
    private val selectedArtist = MutableStateFlow<ArtistGroup?>(null)
    private val selectedTagGroup = MutableStateFlow<TagCategoryGroup?>(null)
    private val inspectTrack = MutableStateFlow<Track?>(null)
    private val ytmTracks = MutableStateFlow<List<YtmTrack>>(emptyList())
    private val ytmQuery = MutableStateFlow("")
    private val isYtmSearching = MutableStateFlow(false)
    private val ytmError = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val lyricsOnly = MutableStateFlow(false)
    private val lyricsIndexed = MutableStateFlow(0)
    private val lyricsIndexing = MutableStateFlow(false)
    private val isScanning = MutableStateFlow(false)
    private val hasScanned = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val analysis = MutableStateFlow<AudioAnalysis?>(null)
    private val sortPrefs = application.getSharedPreferences("kukosin_library_sort", Context.MODE_PRIVATE)
    private val sortMode = MutableStateFlow(
        runCatching { LibrarySort.valueOf(sortPrefs.getString(KEY_SORT, null) ?: "") }
            .getOrDefault(LibrarySort.TITULO),
    )
    private var scanJob: Job? = null
    private var analysisJob: Job? = null
    private var ytmSearchJob: Job? = null
    private var ytmDebounceJob: Job? = null

    private val combinedState: Flow<LibraryUiState> = combine(
        combine(tracks, albums, artists, rootFolder, folderPath) { tr, al, ar, rf, fp ->
            Tuple5(tr, al, ar, rf, fp)
        },
        combine(albumArtists, genres, years, composers) { aar, gn, yr, cp ->
            Tuple4(aar, gn, yr, cp)
        },
        combine(activeCategory, selectedAlbum, selectedArtist, selectedTagGroup) { cat, sal, sar, stg ->
            Tuple4(cat, sal, sar, stg)
        },
        combine(ytmTracks, ytmQuery, isYtmSearching, inspectTrack) { yTr, yQ, ySc, ins ->
            Tuple4(yTr, yQ, ySc, ins)
        },
        combine(query, isScanning, hasScanned, error, ytmError) { q, sc, hasSc, err, yErr ->
            Tuple5(q, sc, hasSc, err, yErr)
        },
    ) { t1, t2, t3, t4, t5 ->
        Tuple5(t1, t2, t3, t4, t5)
    }.combine(
        combine(lyricsOnly, lyricsIndexed, lyricsIndexing) { lo, li, lix ->
            Tuple3(lo, li, lix)
        }
    ) { (t1, t2, t3, t4, t5), (lyrOnly, lyrIdx, lyrIdxing) ->
        val (tr, al, ar, rf, fp) = t1
        val (aar, gn, yr, cp) = t2
        val (cat, sal, sar, stg) = t3
        val (yTr, yQ, ySc, ins) = t4
        val (q, sc, hasSc, err, yErr) = t5
        LibraryUiState(
            tracks = tr,
            albums = al,
            artists = ar,
            albumArtists = aar,
            genres = gn,
            years = yr,
            composers = cp,
            rootFolder = rf,
            folderPath = fp,
            activeCategory = cat,
            selectedAlbum = sal,
            selectedArtist = sar,
            selectedTagGroup = stg,
            inspectTrack = ins,
            ytmTracks = yTr,
            ytmQuery = yQ,
            isYtmSearching = ySc,
            ytmError = yErr,
            query = q,
            lyricsOnly = lyrOnly,
            lyricsIndexed = lyrIdx,
            lyricsIndexing = lyrIdxing,
            isScanning = sc,
            hasScanned = hasSc,
            error = err,
        )
    }

    /**
     * El orden se aplica acá y no en la UI: así la lista que se ve y la que se
     * manda al reproductor son exactamente la misma.
     */
    val uiState: StateFlow<LibraryUiState> = combine(combinedState, sortMode) { state, mode ->
        state.copy(tracks = sortTracks(state.tracks, mode), sortMode = mode)
        // El orden corre fuera del hilo principal: con 10.000 canciones, ordenar en
        // cada tecla de la búsqueda se sentiría.
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LibraryUiState(),
        )

    val analysisState: StateFlow<AudioAnalysis?> = analysis

    fun setSortMode(mode: LibrarySort) {
        sortMode.value = mode
        sortPrefs.edit().putString(KEY_SORT, mode.name).apply()
    }

    fun openLibraryRoot() {
        activeCategory.value = null
        selectedTagGroup.value = null
        query.value = ""
    }

    fun selectCategory(category: LibraryCategory?) {
        activeCategory.value = category
        selectedTagGroup.value = null
        query.value = ""
        if (category == LibraryCategory.CARPETAS) {
            rootFolder.value?.let { folderPath.value = listOf(it) }
        }
    }

    fun selectAlbum(album: AlbumGroup?) {
        selectedAlbum.value = album
    }

    fun selectArtist(artist: ArtistGroup?) {
        selectedArtist.value = artist
    }

    fun selectTagGroup(group: TagCategoryGroup?) {
        selectedTagGroup.value = group
    }

    fun navigateToFolder(folder: FolderNode) {
        folderPath.value = folderPath.value + folder
    }

    fun navigateUpFolder(): Boolean {
        val current = folderPath.value
        if (current.size > 1) {
            folderPath.value = current.dropLast(1)
            return true
        }
        return false
    }

    val audioConfig: StateFlow<AudioConfig> = AudioSettingsManager.configState

    init {
        AudioSettingsManager.init(getApplication())
    }

    /** "Rescan": tira la caché de sondeo (memoria + disco) y reconstruye. */
    fun rescan() {
        MusicLibrary.invalidateProbeCache(getApplication())
        StartupCache.clear(getApplication())
        refresh()
    }

    fun toggleAutoAnalyze() {
        AudioSettingsManager.setAutoAnalyze(!audioConfig.value.autoAnalyze)
    }

    fun setOutputMode(mode: com.kukosin.app.playback.OutputMode) {
        AudioSettingsManager.setOutputMode(mode)
    }

    fun toggleStrictBitPerfect() {
        AudioSettingsManager.setStrictBitPerfect(!audioConfig.value.strictBitPerfect)
    }

    fun setContinuity(preset: com.kukosin.app.playback.ContinuityPreset) {
        com.kukosin.app.playback.AudioSettingsManager.setContinuity(preset)
    }

    fun setVolumeCurve(curve: Int) {
        com.kukosin.app.playback.AudioSettingsManager.setVolumeCurve(curve)
    }

    fun setBufferMs(ms: Int) {
        AudioSettingsManager.setBufferMs(ms)
    }

    fun setFades(enabled: Boolean) {
        AudioSettingsManager.setFades(enabled)
    }

    fun setDecoder(decoder: String) {
        AudioSettingsManager.setDecoder(decoder)
    }

    fun setInspectTrack(track: Track?) {
        inspectTrack.value = track
        if (track != null) {
            prepareMetadata(track)
            if (audioConfig.value.autoAnalyze) {
                analyzeSpectral(track)
            }
        }
    }

    fun prepareMetadata(track: Track) {
        if (analysis.value?.uri == track.uri && analysis.value?.technical?.isNotEmpty() == true) {
            return
        }
        analysisJob?.cancel()
        analysis.value = AudioAnalyzer.extractMetadataOnly(getApplication(), track)
    }

    fun analyzeSpectral(track: Track) {
        if (analysis.value?.uri == track.uri && analysis.value?.isAnalyzing == true) return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val current = analysis.value?.takeIf { it.uri == track.uri }
            analysis.value = (current ?: AudioAnalysis(uri = track.uri)).copy(isAnalyzing = true)
            val result = runCatching { AudioAnalyzer.analyze(getApplication(), track) }
                .getOrElse { error ->
                    AudioAnalysis(
                        uri = track.uri,
                        error = error.message ?: "Could not analyze",
                        verdictTitle = "Analysis error",
                        verdictDetail = error.message.orEmpty(),
                    )
                }
            analysis.value = result
        }
    }

    private var queryJob: Job? = null
    private var lyricsIndexJob: Job? = null

    /**
     * La consulta se aplica con un pequeño retardo (90 ms): escribir rápido ya no
     * dispara 8 filtrados completos, sólo uno. El texto del campo se actualiza al
     * instante en la UI (es estado local de Compose), así que no se siente el retraso.
     */
    fun onQueryChange(value: String) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            query.value = value
        }
    }

    /** Aplica el texto ahora (al pulsar "buscar" o elegir una sugerencia). */
    fun onQueryCommit(value: String) {
        queryJob?.cancel()
        query.value = value
    }

    /**
     * Activa/desactiva la búsqueda dentro de las letras. Al activarla se indexa la
     * biblioteca en segundo plano la primera vez (sólo las canciones que falten).
     */
    fun setLyricsOnly(enabled: Boolean) {
        lyricsOnly.value = enabled
        if (enabled) indexLyrics()
    }

    fun toggleLyricsOnly() = setLyricsOnly(!lyricsOnly.value)

    fun indexLyrics() {
        if (lyricsIndexJob?.isActive == true) return
        lyricsIndexJob = viewModelScope.launch {
            lyricsIndexing.value = true
            try {
                val all = tracks.value
                withContext(Dispatchers.IO) {
                    // Mapa uri -> ruta: antes era `firstOrNull` por cada pista (O(n²)).
                    val paths = all.associate { it.uri.toString() to it.filePath }
                    LyricsIndex.ensureIndexed(
                        context = getApplication(),
                        tracks = all.map { it.uri },
                        filePathFor = { uri -> paths[uri.toString()].orEmpty() },
                        onProgress = { indexed, total ->
                            lyricsIndexed.value = LyricsIndex.indexedCount
                            if (indexed % 16 == 0 || indexed == total) {
                                lyricsIndexing.value = indexed < total
                            }
                        },
                    )
                }
                lyricsIndexed.value = LyricsIndex.indexedCount
            } finally {
                lyricsIndexing.value = false
                // La consulta cacheada pudo quedarse corta: se invalida.
                filteredTracksCache.evictAll()
            }
        }
    }

    /**
     * Consulta de YouTube Music con debounce y búsqueda automática.
     *
     * Antes había que pulsar "buscar": escribir se sentía trabado y no pasaba nada hasta
     * tocar el botón. Ahora se espera a que el usuario deje de teclear (400 ms) y la
     * búsqueda sale sola; el botón sigue existiendo para forzarla al instante.
     */
    fun onYtmQueryChange(value: String) {
        ytmQuery.value = value
        // Job aparte del de búsqueda: `searchYtm` cancela `ytmSearchJob`, y si el debounce
        // viviera en ese mismo job se cancelaría a sí mismo.
        ytmDebounceJob?.cancel()
        val needle = value.trim()
        if (needle.length < 2) {
            if (needle.isEmpty()) {
                ytmTracks.value = emptyList()
                ytmError.value = null
            }
            return
        }
        ytmDebounceJob = viewModelScope.launch {
            delay(YTM_QUERY_DEBOUNCE_MS)
            searchYtm(needle)
        }
    }

    fun searchYtm(q: String = ytmQuery.value) {
        val needle = q.trim()
        if (needle.isEmpty()) {
            ytmTracks.value = emptyList()
            ytmError.value = null
            return
        }
        ytmSearchJob?.cancel()
        ytmSearchJob = viewModelScope.launch {
            isYtmSearching.value = true
            ytmError.value = null
            try {
                val results = YtmService.search(needle)
                ytmTracks.value = results
                if (results.isEmpty()) {
                    ytmError.value = "No results for \"$needle\""
                }
            } catch (throwable: Throwable) {
                ytmTracks.value = emptyList()
                ytmError.value = describeYtmError(throwable)
            } finally {
                isYtmSearching.value = false
            }
        }
    }

    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            isScanning.value = true
            error.value = null
            // 1) Si hay una foto del último escaneo, se dibuja YA (sin esperar a MediaStore
            // ni al sondeo). Es una lista optimista: el escaneo real la reemplaza abajo.
            if (!hasScanned.value && tracks.value.isEmpty()) {
                val snapshot = StartupCache.loadTracks(getApplication())
                if (snapshot.isNotEmpty() && tracks.value.isEmpty()) {
                    updateState(snapshot)
                    hasScanned.value = true
                }
            }
            try {
                val scanned = MusicLibrary.scan(getApplication())
                updateState(scanned)
                hasScanned.value = true
                isScanning.value = false

                val enriched = MusicLibrary.enrich(getApplication(), scanned)
                updateState(enriched)
                // 2) La foto se guarda recién con el resultado completo: si el proceso muere
                // a mitad del escaneo, la próxima apertura no arranca con datos a medias.
                StartupCache.save(getApplication(), enriched)
            } catch (throwable: Throwable) {
                error.value = throwable.message ?: "Could not scan the library"
                hasScanned.value = true
                isScanning.value = false
            }
        }
    }

    private fun updateState(scannedTracks: List<Track>) {
        tracks.value = scannedTracks
        albums.value = MusicLibrary.groupAlbums(scannedTracks)
        artists.value = MusicLibrary.groupArtists(scannedTracks)
        albumArtists.value = MusicLibrary.groupAlbumArtists(scannedTracks)
        genres.value = MusicLibrary.groupGenres(scannedTracks)
        years.value = MusicLibrary.groupYears(scannedTracks)
        composers.value = MusicLibrary.groupComposers(scannedTracks)
        val tree = MusicLibrary.buildFolderTree(scannedTracks)
        // El segundo escaneo enriquece metadatos: sustituimos los nodos antiguos sin
        // perder la carpeta que el usuario tenía abierta, si todavía existe.
        val oldPaths = folderPath.value.map { it.path }
        val restored = mutableListOf(tree)
        for (path in oldPaths.drop(1)) {
            val next = restored.last().subfolders.firstOrNull { it.path == path } ?: break
            restored.add(next)
        }
        rootFolder.value = tree
        folderPath.value = restored
    }
}

/** Clave de prefs del criterio de orden elegido. */
private const val KEY_SORT = "library_sort_mode"

private data class Tuple3<A, B, C>(val a: A, val b: B, val c: C)
private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
