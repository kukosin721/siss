package com.kukosin.app.data.ytm

import android.util.Log
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Canción de YouTube Music (metadatos, sin URL de audio resuelta todavía). */
data class YtmTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "YouTube Music",
    val thumbnailUri: String = "",
    val durationMs: Long = 0L,
) {
    val watchUrl: String
        get() = "https://music.youtube.com/watch?v=$id"

    val durationLabel: String
        get() {
            if (durationMs <= 0L) return ""
            val totalSeconds = durationMs / 1000
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
}

/** Pista de audio ya resuelta, lista para entregar a ExoPlayer. */
data class YtmAudio(
    val url: String,
    val mimeType: String,
    val codecLabel: String,
    val bitrateKbps: Int,
    val itag: Int,
) {
    val qualityLabel: String
        get() {
            val kbps = if (bitrateKbps > 0) "$bitrateKbps kbps" else "bitrate variable"
            return "$codecLabel $kbps"
        }
}

/** Error de YouTube Music con un mensaje ya listo para mostrar en la UI. */
class YtmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Búsqueda y reproducción de YouTube Music usando NewPipeExtractor.
 *
 * Antes el proyecto usaba la API de Piped, que hoy devuelve `audioStreams` vacío porque YouTube
 * exige po_token/SABR: la búsqueda devolvía canciones pero ninguna se podía reproducir.
 * NewPipeExtractor resuelve las URLs reales de audio en el propio dispositivo.
 */
object YtmService {
    private const val TAG = "KukosinYtm"
    private const val SEARCH_LIMIT = 40

    /** Un listado de búsqueda no cambia en 10 minutos: cachearlo evita re-consultar. */
    private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L

    private const val SEARCH_CACHE_ENTRIES = 32
    private const val STREAM_CACHE_TTL_MS = 90 * 60 * 1000L

    /** Filtro de búsqueda de YouTube Music: solo canciones. */
    private const val MUSIC_SONGS_FILTER = "music_songs"

    /** `=w226-h226` / `=s176` en las URLs de lh3.googleusercontent.com. */
    private val THUMBNAIL_SIZE = Regex("=w\\d+-h\\d+")
    private val THUMBNAIL_SQUARE = Regex("=s\\d+")

    /** Esquema de las URIs "symbolic" de YTM: el audio real se resuelve al abrir el stream. */
    const val STREAM_SCHEME = "kukosin-ytm"

    // Contrato de extras de MediaMetadata para audio remoto (lo lee PlayerApp).
    const val EXTRA_STREAM_VIDEO_ID = "com.kukosin.app.stream.VIDEO_ID"
    const val EXTRA_STREAM_THUMBNAIL = "com.kukosin.app.stream.THUMBNAIL"

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    private data class CachedAudio(val audio: YtmAudio, val expiresAt: Long)

    private val audioCache = ConcurrentHashMap<String, CachedAudio>()

    /**
     * Resultados de búsqueda con caducidad. Antes no se cacheaban y volver a la misma
     * consulta (o escribir/borrar una letra) disparaba una petición nueva a YouTube
     * cada vez: eso era gran parte de la lentitud percibida en la búsqueda.
     */
    private data class CachedSearch(val results: List<YtmTrack>, val expiresAt: Long)

    private val searchCache = ConcurrentHashMap<String, CachedSearch>()

    /**
     * Resoluciones en curso, por videoId. Si dos partes de la app piden la misma canción
     * a la vez, comparten la misma petición en vez de hacer dos (y de pisarse la caché).
     */
    private val inFlightAudio = ConcurrentHashMap<String, Deferred<YtmAudio>>()

    private val searchInFlight = ConcurrentHashMap<String, Deferred<List<YtmTrack>>>()

    /**
     * Ámbito propio para las peticiones compartidas: si quien las pidió cancela (por
     * ejemplo, el usuario sigue escribiendo), la petición no se corta y el siguiente
     * que la necesite la aprovecha.
     */
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Busca canciones en YouTube Music. Con una consulta vacía devuelve una lista vacía:
     * no se inventan resultados de ejemplo.
     */
    suspend fun search(query: String): List<YtmTrack> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.isEmpty()) return@withContext emptyList()
        val key = needle.lowercase()

        searchCache[key]?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis()) {
                Log.d(TAG, "search \"$needle\": ${cached.results.size} results from cache")
                return@withContext cached.results
            }
            searchCache.remove(key)
        }

        // Deduplicación: la misma consulta pedida dos veces seguidas comparte resultado.
        // `putIfAbsent` cierra la carrera entre dos corrutinas que llegan a la vez.
        val deferred = requestScope.async {
            val results = searchRemote(needle)
            searchCache[key] = CachedSearch(results, System.currentTimeMillis() + SEARCH_CACHE_TTL_MS)
            trimSearchCache()
            results
        }
        val winner = searchInFlight.putIfAbsent(key, deferred)
        if (winner != null) {
            deferred.cancel()
            return@withContext winner.await()
        }
        try {
            deferred.await()
        } finally {
            searchInFlight.remove(key, deferred)
        }
    }

    private suspend fun searchRemote(needle: String): List<YtmTrack> {
        ensureInitialized()

        val startedAt = System.currentTimeMillis()
        return runCatching {
            // Ojo: SearchInfo.getInfo(extractor) NO llama a fetchPage() y devuelve una lista
            // vacía con NPEs silenciosos. Este overload sí hace el fetch antes de parsear.
            val queryHandler = ServiceList.YouTube.searchQHFactory.fromQuery(
                needle,
                listOf(MUSIC_SONGS_FILTER),
                "",
            )
            val info = SearchInfo.getInfo(ServiceList.YouTube, queryHandler)
            info.relatedItems
                .asSequence()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toYtmTrack() }
                .distinctBy { it.id }
                .take(SEARCH_LIMIT)
                .toList()
        }.onSuccess { results ->
            Log.d(TAG, "search \"$needle\": ${results.size} results in ${System.currentTimeMillis() - startedAt} ms")
        }.onFailure { throwable ->
            Log.w(TAG, "search \"$needle\" failed: ${throwable::class.java.simpleName}: ${throwable.message}")
        }.getOrElse { throwable ->
            throw YtmException(describeYtmError(throwable), throwable)
        }
    }

    /**
     * Resuelve la URL de audio reproducible de una canción.
     * Las URLs de googlevideo caducan, por eso se cachean solo [STREAM_CACHE_TTL_MS].
     */
    suspend fun resolveAudio(track: YtmTrack): YtmAudio =
        resolveAudio(track.id, track.watchUrl)

    suspend fun resolveAudio(
        videoId: String,
        watchUrl: String = "https://music.youtube.com/watch?v=$videoId",
    ): YtmAudio = withContext(Dispatchers.IO) {
        audioCache[videoId]?.let { cached ->
            if (cached.expiresAt > System.currentTimeMillis()) {
                Log.d(TAG, "resolveAudio($videoId): URL from cache")
                return@withContext cached.audio
            }
            audioCache.remove(videoId)
        }

        // Si ya hay una resolución en curso para este video, se espera esa en vez de
        // pedir otra URL (dos peticiones simultáneas devolvían dos URLs distintas y la
        // segunda invalidaba la que ya estaba sonando).
        val deferred = requestScope.async { resolveAudioRemote(videoId, watchUrl) }
        val winner = inFlightAudio.putIfAbsent(videoId, deferred)
        if (winner != null) {
            deferred.cancel()
            return@withContext winner.await()
        }
        try {
            deferred.await()
        } finally {
            inFlightAudio.remove(videoId, deferred)
        }
    }

    private suspend fun resolveAudioRemote(videoId: String, watchUrl: String): YtmAudio {
        ensureInitialized()

        val startedAt = System.currentTimeMillis()
        val streamInfo = runCatching {
            StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
        }.recoverCatching {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        }.getOrElse { throwable ->
            throw YtmException(describeYtmError(throwable), throwable)
        }

        val stream = pickBestAudio(streamInfo.audioStreams)
            ?: throw YtmException(
                "YouTube did not return an audio track for this song. " +
                    "It may be restricted content or the extractor is outdated " +
                    "(update NewPipeExtractor in gradle/libs.versions.toml).",
            )

        val audio = YtmAudio(
            url = stream.content,
            mimeType = stream.format?.mimeType ?: "audio/mp4",
            codecLabel = stream.codecLabel(),
            bitrateKbps = stream.bitrateKbps(),
            itag = stream.itag,
        )

        Log.d(
            TAG,
            "resolveAudio($videoId): ${audio.codecLabel} ${audio.bitrateKbps}kbps " +
                "itag=${audio.itag} in ${System.currentTimeMillis() - startedAt} ms",
        )
        audioCache[videoId] = CachedAudio(audio, System.currentTimeMillis() + STREAM_CACHE_TTL_MS)
        trimCache()
        return audio
    }

    /**
     * Empaqueta la canción como [MediaItem] **sin resolver** el audio todavía.
     *
     * La URI apunta al esquema [STREAM_SCHEME]; [com.kukosin.app.playback.PlaybackService] la
     * resuelve a la URL real de googlevideo justo antes de abrir el stream. Así se puede
     * encolar toda la búsqueda sin hacer 40 peticiones de red al tocar play.
     */
    fun toMediaItem(track: YtmTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.thumbnailUri.takeIf { it.isNotBlank() }?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(
                bundleOf(
                    EXTRA_STREAM_VIDEO_ID to track.id,
                    EXTRA_STREAM_THUMBNAIL to track.thumbnailUri,
                ),
            )
            .build()

        return MediaItem.Builder()
            .setMediaId("ytm_${track.id}")
            .setUri("$STREAM_SCHEME://stream/${track.id}".toUri())
            .setMediaMetadata(metadata)
            .build()
    }

    fun clearCache() {
        audioCache.clear()
        searchCache.clear()
    }

    //region Audio selection

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
        val playable = streams.filter { it.isUrl && it.content.isNotBlank() }
        if (playable.isEmpty()) return null

        // Los streams de audio de YouTube suelen venir como PROGRESSIVE_HTTP (URL directa).
        // Si solo hubiera manifiestos DASH/HLS no se pueden reproducir sin los módulos
        // media3-exoplayer-dash / -hls, así que se prefieren las URLs directas.
        val direct = playable.filter {
            it.deliveryMethod != DeliveryMethod.DASH && it.deliveryMethod != DeliveryMethod.HLS
        }
        val candidates = direct.ifEmpty { playable }

        return candidates.sortedWith(
            compareByDescending<AudioStream> { it.bitrateKbps() }
                .thenByDescending { it.format?.codecRank() ?: 0 },
        ).firstOrNull()
    }

    private fun AudioStream.bitrateKbps(): Int {
        val raw = if (averageBitrate > 0) averageBitrate else bitrate
        if (raw <= 0) return 0
        // NewPipe entrega kbps en los itag, pero algunos extractores devuelven bps.
        return if (raw > 10_000) raw / 1000 else raw
    }

    private fun AudioStream.codecLabel(): String = when (format) {
        MediaFormat.WEBMA_OPUS -> "Opus"
        MediaFormat.M4A -> "AAC"
        MediaFormat.WEBMA -> "Vorbis"
        MediaFormat.MP3 -> "MP3"
        else -> "AUDIO"
    }

    //endregion

    //region Helpers

    /**
     * El extractor devuelve la miniatura con el tamaño incrustado en la URL (`=w226-h226`),
     * pero Google sirve la imagen original si se le pide más grande. Con esto la carátula del
     * reproductor deja de verse pixelada.
     */
    private fun upscaleThumbnailUrl(url: String): String {
        if (url.isBlank()) return url
        var out = THUMBNAIL_SIZE.replace(url, "=w1080-h1080")
        out = THUMBNAIL_SQUARE.replace(out, "=s1080")
        return out
    }

    private fun StreamInfoItem.toYtmTrack(): YtmTrack? {
        val videoId = extractVideoId(url) ?: return null
        val thumbnail = thumbnails
            .maxByOrNull { it.height }
            ?.url
            ?: thumbnails.firstOrNull()?.url
            ?: ""

        return YtmTrack(
            id = videoId,
            title = name.ifBlank { "YouTube Music song" },
            artist = uploaderName.ifBlank { "Unknown artist" },
            thumbnailUri = upscaleThumbnailUrl(thumbnail),
            durationMs = duration.coerceAtLeast(0L) * 1000L,
        )
    }

    private val videoIdRegex = Regex("[?&]v=([A-Za-z0-9_-]{11})")

    private fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        videoIdRegex.find(url)?.let { return it.groupValues[1] }
        val lastSegment = url.substringAfterLast('/').substringBefore('?')
        return lastSegment.takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return@withLock
            val locale = Locale.getDefault()
            val language = locale.language.takeIf { it.isNotBlank() } ?: "es"
            val country = locale.country.takeIf { it.isNotBlank() } ?: "CL"
            NewPipe.init(
                NewPipeDownloader(),
                Localization(language, country),
                ContentCountry(country),
            )
            initialized = true
        }
    }

    private fun trimSearchCache() {
        val now = System.currentTimeMillis()
        searchCache.entries.removeAll { it.value.expiresAt <= now }
        if (searchCache.size > SEARCH_CACHE_ENTRIES) {
            searchCache.keys.take(searchCache.size - SEARCH_CACHE_ENTRIES)
                .forEach { searchCache.remove(it) }
        }
    }

    private fun trimCache() {
        if (audioCache.size <= 24) return
        val now = System.currentTimeMillis()
        audioCache.entries.removeAll { it.value.expiresAt <= now }
        if (audioCache.size > 24) {
            audioCache.keys.take(audioCache.size - 24).forEach { audioCache.remove(it) }
        }
    }

    private fun MediaFormat.codecRank(): Int = when (this) {
        MediaFormat.WEBMA_OPUS -> 4
        MediaFormat.M4A -> 3
        MediaFormat.WEBMA -> 2
        MediaFormat.MP3 -> 1
        else -> 0
    }

    //endregion
}

/** Traduce las excepciones de NewPipeExtractor a algo que el usuario pueda entender. */
fun describeYtmError(error: Throwable): String = when (error) {
    is YtmException -> error.message ?: "Could not complete the operation on YouTube Music."
    is SignInConfirmNotBotException, is ReCaptchaException ->
        "YouTube requested an anti-bot check. Wait a minute and try again."
    is AgeRestrictedContentException ->
        "That content has an age restriction and YouTube will not deliver it without an account."
    is YoutubeMusicPremiumContentException ->
        "It is YouTube Music Premium exclusive content."
    is GeographicRestrictionException ->
        "The content is blocked in your region."
    is PrivateContentException ->
        "The content is private."
    is ContentNotSupportedException ->
        "Content format not supported by the extractor."
    is ContentNotAvailableException ->
        "YouTube does not have that content available."
    is IOException ->
        "No connection to YouTube. Check your network and try again."
    else ->
        "YouTube Music error: ${error.message ?: error::class.java.simpleName}"
}
