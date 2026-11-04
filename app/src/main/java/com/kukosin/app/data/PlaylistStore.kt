package com.kukosin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Una lista de reproducción del usuario.
 *
 * Guarda **URIs** y no pistas completas: la biblioteca cambia (se agregan canciones, se
 * renombra un álbum) y una playlist con una copia vieja de la metadata se vería
 * desactualizada. Al mostrarla se resuelven las URIs contra la biblioteca actual, así el
 * título, el artista y la carátula salen siempre del archivo real.
 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Ruta absoluta de la portada elegida por el usuario (puede estar vacía). */
    val coverPath: String = "",
    val trackUris: List<String> = emptyList(),
) {
    val size: Int get() = trackUris.size

    /** Portada en disco, o `null` si no hay o si el archivo ya no existe. */
    val coverFile: File?
        get() = coverPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    fun contains(uri: String): Boolean = trackUris.contains(uri)
}

/**
 * Persistencia de las playlists.
 *
 * Sin Room: son pocas listas y se escriben enteras en un JSON atómico (tmp + rename) en
 * `filesDir`, igual que la caché de la biblioteca. El estado vive en un [StateFlow] para
 * que cualquier pantalla se redibuje sola cuando se crea, renombra o reordena algo.
 */
object PlaylistStore {

    private const val FILE_NAME = "playlists.json"
    private const val VERSION = 1
    private const val COVER_DIR = "playlists/covers"
    private const val MAX_COVER_PX = 1024
    private const val MAX_PLAYLISTS = 500
    private const val MAX_TRACKS_PER_PLAYLIST = 5000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Se inicializa una sola vez (Application o ViewModel); llamadas extra se ignoran. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val loaded = runCatching { read(app) }.getOrDefault(emptyList())
            _playlists.value = loaded
        }
    }

    // ── Lectura ────────────────────────────────────────────────────────────

    private fun read(context: Context): List<Playlist> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        if (root.optInt("version", 0) != VERSION) return emptyList()
        val array = root.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<Playlist>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            if (id.isBlank()) continue
            val uris = item.optJSONArray("uris")?.let { list ->
                buildList(list.length()) {
                    for (j in 0 until list.length()) {
                        val value = list.optString(j, "")
                        if (value.isNotBlank()) add(value)
                    }
                }
            }.orEmpty()
            result.add(
                Playlist(
                    id = id,
                    name = item.optString("name", "Playlist").ifBlank { "Playlist" },
                    createdAt = item.optLong("createdAt", 0L),
                    updatedAt = item.optLong("updatedAt", 0L),
                    coverPath = item.optString("cover", ""),
                    trackUris = uris,
                ),
            )
        }
        return result
    }

    private fun write(context: Context, playlists: List<Playlist>) {
        val root = JSONObject().put("version", VERSION)
        val array = JSONArray()
        for (playlist in playlists) {
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("createdAt", playlist.createdAt)
                    .put("updatedAt", playlist.updatedAt)
                    .put("cover", playlist.coverPath)
                    .put("uris", JSONArray(playlist.trackUris)),
            )
        }
        root.put("items", array)
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun save() {
        val context = appContext ?: return
        val snapshot = _playlists.value
        scope.launch { runCatching { write(context, snapshot) } }
    }

    private fun update(block: (List<Playlist>) -> List<Playlist>) {
        _playlists.value = block(_playlists.value)
        save()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    // ── Operaciones ────────────────────────────────────────────────────────

    fun byId(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    /** Crea una playlist. Devuelve `null` si el nombre quedó vacío o hay demasiadas. */
    fun create(name: String, trackUris: List<String> = emptyList()): Playlist? {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return null
        if (_playlists.value.size >= MAX_PLAYLISTS) return null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = newId(),
            name = clean,
            createdAt = now,
            updatedAt = now,
            trackUris = trackUris.distinct().take(MAX_TRACKS_PER_PLAYLIST),
        )
        update { current -> current + playlist }
        return playlist
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return
        update { current ->
            current.map { if (it.id == id) it.copy(name = clean, updatedAt = System.currentTimeMillis()) else it }
        }
    }

    fun delete(id: String) {
        val target = byId(id) ?: return
        update { current -> current.filterNot { it.id == id } }
        scope.launch { runCatching { target.coverFile?.delete() } }
    }

    /** Devuelve cuántas pistas se agregaron de verdad (las repetidas se ignoran). */
    fun addTracks(id: String, uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        var added = 0
        update { current ->
            current.map { playlist ->
                if (playlist.id != id) return@map playlist
                val merged = playlist.trackUris.toMutableList()
                for (uri in uris) {
                    if (uri.isBlank() || merged.contains(uri)) continue
                    if (merged.size >= MAX_TRACKS_PER_PLAYLIST) break
                    merged.add(uri)
                    added++
                }
                if (added == 0) playlist
                else playlist.copy(trackUris = merged, updatedAt = System.currentTimeMillis())
            }
        }
        return added
    }

    fun removeAt(id: String, index: Int) {
        update { current ->
            current.map { playlist ->
                if (playlist.id != id || index !in playlist.trackUris.indices) return@map playlist
                val list = playlist.trackUris.toMutableList().apply { removeAt(index) }
                playlist.copy(trackUris = list, updatedAt = System.currentTimeMillis())
            }
        }
    }

    fun removeUri(id: String, uri: String) {
        update { current ->
            current.map { playlist ->
                if (playlist.id != id || !playlist.trackUris.contains(uri)) return@map playlist
                playlist.copy(
                    trackUris = playlist.trackUris.filterNot { it == uri },
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun move(id: String, from: Int, to: Int) {
        update { current ->
            current.map { playlist ->
                val list = playlist.trackUris
                if (playlist.id != id) return@map playlist
                if (from !in list.indices || to !in list.indices || from == to) return@map playlist
                val mutable = list.toMutableList()
                val item = mutable.removeAt(from)
                mutable.add(to, item)
                playlist.copy(trackUris = mutable, updatedAt = System.currentTimeMillis())
            }
        }
    }

    /** Copia la imagen elegida a `filesDir/playlists/covers/<id>.jpg` y la fija. */
    fun setCover(id: String, uri: Uri) {
        val context = appContext ?: return
        scope.launch {
            val path = runCatching { copyCover(context, id, uri) }.getOrNull() ?: return@launch
            update { current ->
                current.map { playlist ->
                    if (playlist.id != id) playlist
                    else playlist.copy(coverPath = path, updatedAt = System.currentTimeMillis())
                }
            }
        }
    }

    fun clearCover(id: String) {
        val target = byId(id) ?: return
        update { current ->
            current.map { playlist ->
                if (playlist.id != id) playlist
                else playlist.copy(coverPath = "", updatedAt = System.currentTimeMillis())
            }
        }
        scope.launch { runCatching { target.coverFile?.delete() } }
    }

    private fun copyCover(context: Context, id: String, uri: Uri): String? {
        val directory = File(context.filesDir, COVER_DIR).apply { mkdirs() }
        val target = File(directory, "$id.jpg")
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        if (bitmap == null) {
            // Formato que BitmapFactory no entiende (raro): se guardan los bytes tal cual
            // y Coil se encarga de decodificar.
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target.takeIf { it.length() > 0 }?.absolutePath
        }
        val scaled = scaleDown(bitmap, MAX_COVER_PX)
        target.outputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return target.absolutePath
    }

    private fun scaleDown(source: Bitmap, maxPx: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxPx) return source
        val ratio = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
