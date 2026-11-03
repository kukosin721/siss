package com.kukosin.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistencia de la caché de sondeo de la biblioteca.
 *
 * Guarda en `filesDir` lo que costó leer de cada archivo (duración, sample rate,
 * profundidad de bits, carátula, etiquetas). Sin Room ni dependencias nuevas: un
 * JSON escrito de forma atómica (tmp + rename), que es suficiente para un mapa
 * plano de "uri → pista enriquecida".
 *
 * Se invalida al pedir "volver a escanear".
 */
object LibraryCacheStore {

    private const val FILE_NAME = "library_probe_cache.json"
    /**
     * Sube cuando cambia el formato o la semántica de los campos guardados.
     *
     * 3: la caché guarda **sólo campos técnicos** (duración, sample rate, profundidad,
     * bitrate, canales, mime) más la firma de disco; las etiquetas se toman siempre del
     * escaneo. Las cachés v2 (con etiquetas) se descartan solas al comparar la versión.
     */
    private const val VERSION = 3

    /** Tope defensivo: si alguien tiene 100k archivos, no queremos 40 MB de JSON. */
    private const val MAX_ENTRIES = 60_000

    private const val TAG = "LibraryCacheStore"

    /** Campo extra (no forma parte de `Track`) con la firma del archivo. */
    private const val STAMP_KEY = "diskStamp"

    /**
     * Carga la caché y **descarta** las entradas cuyo archivo cambió de tamaño o de
     * fecha de modificación (o que ya no existen).
     *
     * Antes la clave era sólo la uri: si el usuario reemplazaba un MP3 por otro con el
     * mismo nombre, la app seguía mostrando duración, sample rate y carátula del
     * archivo viejo. Con la firma de disco, la caché sólo se reutiliza cuando
     * corresponde y se re-sondea lo que cambió.
     */
    suspend fun load(
        context: Context,
        fingerprintOf: (uri: String, filePath: String) -> DiskFingerprint? = ::fingerprintFromPath,
    ): Map<String, Track> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyMap()
        return runCatching<Map<String, Track>> {
            // Lectura en IO: el JSON puede ser de varios MB y antes se parseaba en el
            // hilo que llamaba (que en el arranque es el principal).
            val text = withContext(Dispatchers.IO) { file.readText() }
            val root = JSONObject(text)
            if (root.optInt("version", 0) != VERSION) {
                emptyMap()
            } else {
                val array = root.optJSONArray("tracks") ?: JSONArray()
                val result = HashMap<String, Track>(array.length())
                var discarded = 0
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val uri = item.optString("uri", "")
                    if (uri.isBlank()) continue
                    // La firma guardada viaja junto a la entrada; si no coincide con el
                    // archivo actual, no sirve.
                    val stored = DiskFingerprint.parse(item.optString(STAMP_KEY, ""))
                    val current = fingerprintOf(uri, item.optString("filePath", ""))
                    if (stored != null && current != null && !stored.matches(current)) {
                        discarded++
                        continue
                    }
                    // Se guarda una pista "técnica" sin etiquetas: sólo los campos que
                    // costó leer del archivo. `applyCached` los copia sobre la pista real.
                    result[uri] = fromCached(item)
                }
                if (discarded > 0) {
                    android.util.Log.i(TAG, "caché: $discarded entradas descartadas (archivo modificado)")
                }
                result
            }
        }.getOrElse { emptyMap() }
    }

    /** Firma del archivo en disco de una pista (null si no es un archivo local legible). */
    fun fingerprintFromDisk(track: Track): DiskFingerprint? =
        fingerprintFromPath(track.uri.toString(), track.filePath)

    fun fingerprintFromPath(uri: String, filePath: String): DiskFingerprint? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        return runCatching {
            if (file.exists()) DiskFingerprint(file.length(), file.lastModified()) else null
        }.getOrNull()
    }

    /**
     * Convierte una pista sondeada a un objeto JSON ligero.
     *
     * Antes se guardaba un `Track` completo, incluidas las **etiquetas de MediaStore**
     * (título, artista, álbum…), y `load` devolvía esas etiquetas como si fueran las del
     * archivo: un retag posterior no se veía hasta pulsar "volver a escanear". El escaneo
     * ya entrega los tags frescos en cada arranque con una sola consulta al
     * `MediaStore` (baratísima); lo único caro es abrir el archivo para leer
     * sample rate, profundidad, bitrate, duración y carátula. Eso es lo que se cachea.
     */
    private fun toCached(track: Track): JSONObject = JSONObject()
        .put("uri", track.uri.toString())
        .put("filePath", track.filePath)
        .put("durationMs", track.durationMs)
        .put("sampleRate", track.sampleRate)
        .put("bitDepth", track.bitDepth)
        .put("bitrate", track.bitrate)
        .put("channels", track.channels)
        .put("mimeType", track.mimeType)

    /**
     * Aplica lo cacheado sobre una pista recién escaneada: **las etiquetas se toman del
     * escaneo** (frescas) y sólo los campos técnicos se toman de la caché.
     *
     * Antes la caché devolvía un `Track` completo con título/artista/álbum viejos: un
     * retag no se veía hasta pulsar "volver a escanear".
     */
    fun applyCached(track: Track, cached: Track): Track = track.copy(
        durationMs = if (cached.durationMs > 0) cached.durationMs else track.durationMs,
        sampleRate = if (cached.sampleRate > 0) cached.sampleRate else track.sampleRate,
        bitDepth = if (cached.bitDepth > 0) cached.bitDepth else track.bitDepth,
        bitrate = if (cached.bitrate > 0) cached.bitrate else track.bitrate,
        channels = if (cached.channels > 0) cached.channels else track.channels,
        mimeType = cached.mimeType.ifBlank { track.mimeType },
    )

    fun save(context: Context, entries: Map<String, Track>) {
        if (entries.isEmpty()) return
        runCatching {
            val array = JSONArray()
            var count = 0
            for (track in entries.values) {
                if (count >= MAX_ENTRIES) break
                val json = toCached(track)
                fingerprintFromDisk(track)?.let { json.put(STAMP_KEY, it.encoded) }
                array.put(json)
                count++
            }
            val root = JSONObject()
                .put("version", VERSION)
                .put("tracks", array)

            val file = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            // Escritura atómica de verdad: se escribe TODO en el temporal, se sincroniza a
            // disco y recién entonces se renombra sobre el destino. Antes se borraba el
            // archivo bueno antes del rename: si el proceso moría en el medio (Android
            // mata la app en segundo plano), la caché se perdía completa.
            java.io.FileOutputStream(tmp).use { output ->
                output.write(root.toString().toByteArray())
                output.flush()
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // El rename puede fallar en algunos volúmenes: se copia y se corrompe menos
                // que dejando la caché vieja a medias.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, "$FILE_NAME.tmp").delete()
        }
    }

    // ------------------------------------------------------------------ JSON

    /** Pista técnica desde la caché: sin etiquetas, sólo lo que se leyó del archivo. */
    private fun fromCached(json: JSONObject): Track {
        val uriString = json.optString("uri", "")
        return Track(
            id = json.optLong("id", uriString.hashCode().toLong()),
            uri = Uri.parse(uriString),
            title = "",
            artist = "",
            album = "",
            filePath = json.optString("filePath", ""),
            durationMs = json.optLong("durationMs", 0L),
            sampleRate = json.optInt("sampleRate", 0),
            bitDepth = json.optInt("bitDepth", 0),
            bitrate = json.optInt("bitrate", 0),
            channels = json.optInt("channels", 0),
            mimeType = json.optString("mimeType", ""),
        )
    }

}
