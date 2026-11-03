package com.kukosin.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Caché de arranque: la lista completa de la biblioteca tal como quedó en el último
 * escaneo, para poder dibujarla **de inmediato** mientras el escaneo real corre detrás.
 *
 * # Por qué existe
 *
 * El arranque tenía una espera inevitable: consultar `MediaStore`, recorrer las carpetas,
 * revisar la firma de disco de cada archivo y recién entonces publicar la lista. En una
 * biblioteca grande eso son varios segundos de pantalla vacía en **cada** apertura de la
 * app, aunque el contenido no hubiera cambiado nada.
 *
 * Con esta caché la app abre con la biblioteca completa y en el mismo instante arranca el
 * escaneo en segundo plano. Cuando termina, si algo cambió, la lista se actualiza sola.
 * La caché no es la fuente de verdad: es una foto optimista que se descarta cuando el
 * escaneo real llega (o cuando la versión del formato cambia).
 *
 * # Qué se guarda
 *
 * Sólo lo que hace falta para dibujar la lista y poder reproducir: identidad, etiquetas y
 * datos técnicos de `Track`. Nada de lo que dependa de la sesión (estado de reproducción,
 * carátulas ya renderizadas, etc.).
 */
object StartupCache {

    private const val FILE_NAME = "library_startup_v1.json"

    /**
     * Sube cuando cambia la forma de `Track` que se guarda. Una caché de otra versión se
     * ignora en silencio (y se reescribe con el próximo escaneo).
     */
    private const val VERSION = 1

    /** Tope defensivo: 100k pistas en JSON serían decenas de MB. */
    private const val MAX_ENTRIES = 60_000

    /** Lista de pistas del último escaneo, o vacía si no hay caché utilizable. */
    suspend fun loadTracks(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != VERSION) return@runCatching emptyList<Track>()
            val array = root.optJSONArray("tracks") ?: JSONArray()
            val result = ArrayList<Track>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                decode(item)?.let(result::add)
            }
            result
        }.getOrElse { emptyList() }
    }

    /** Guarda la lista completa. Se llama al final de cada escaneo exitoso. */
    suspend fun save(context: Context, tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        runCatching {
            val array = JSONArray()
            for (track in tracks.take(MAX_ENTRIES)) {
                array.put(encode(track))
            }
            val root = JSONObject()
                .put("version", VERSION)
                .put("savedAt", System.currentTimeMillis())
                .put("tracks", array)
            val target = File(context.filesDir, FILE_NAME)
            val temporary = File(context.filesDir, "$FILE_NAME.tmp")
            // Escritura atómica: si el proceso muere a mitad, la caché anterior sigue
            // siendo válida en vez de quedar truncada.
            temporary.writeText(root.toString())
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    // ---------------------------------------------------------------- codificación

    private fun encode(track: Track): JSONObject = JSONObject()
        .put("id", track.id)
        .put("uri", track.uri.toString())
        .put("filePath", track.filePath)
        .put("fileName", track.fileName)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        .put("albumArtist", track.albumArtist)
        .put("durationMs", track.durationMs)
        .put("albumId", track.albumId)
        .put("sampleRate", track.sampleRate)
        .put("bitDepth", track.bitDepth)
        .put("bitrate", track.bitrate)
        .put("channels", track.channels)
        .put("mimeType", track.mimeType)
        .put("trackNumber", track.trackNumber)
        .put("discNumber", track.discNumber)
        .put("genre", track.genre)
        .put("composer", track.composer)
        .put("comment", track.comment)
        .put("year", track.year)
        .put("fileSize", track.fileSize)
        .put("artworkOverride", track.artworkOverride?.toString() ?: "")
        .put("streamCodecLabel", track.streamCodecLabel)

    /**
     * Reconstruye una pista. Devuelve null si falta lo mínimo (uri y título): una entrada a
     * medio escribir no debe aparecer en la lista como una pista fantasma.
     */
    private fun decode(item: JSONObject): Track? {
        val uri = item.optString("uri", "")
        if (uri.isBlank()) return null
        val title = item.optString("title", "")
        if (title.isBlank()) return null
        return runCatching {
            Track(
                id = item.optLong("id", 0L),
                uri = android.net.Uri.parse(uri),
                title = title,
                artist = item.optString("artist", ""),
                album = item.optString("album", ""),
                albumArtist = item.optString("albumArtist", ""),
                durationMs = item.optLong("durationMs", 0L),
                albumId = item.optLong("albumId", 0L),
                bitrate = item.optInt("bitrate", 0),
                sampleRate = item.optInt("sampleRate", 0),
                bitDepth = item.optInt("bitDepth", 0),
                channels = item.optInt("channels", 0),
                mimeType = item.optString("mimeType", ""),
                fileName = item.optString("fileName", ""),
                year = item.optInt("year", 0),
                trackNumber = item.optInt("trackNumber", 0),
                discNumber = item.optInt("discNumber", 0),
                genre = item.optString("genre", ""),
                composer = item.optString("composer", ""),
                filePath = item.optString("filePath", ""),
                fileSize = item.optLong("fileSize", 0L),
                comment = item.optString("comment", ""),
                artworkOverride = item.optString("artworkOverride", "")
                    .takeIf { it.isNotBlank() }
                    ?.let(android.net.Uri::parse),
                streamCodecLabel = item.optString("streamCodecLabel", ""),
            )
        }.getOrNull()
    }
}
