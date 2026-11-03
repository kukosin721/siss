package com.kukosin.app.data

/**
 * Importación y exportación de listas en formato M3U/M3U8.
 *
 * Es el formato que entienden Poweramp, Musicolet, VLC y Winamp, así que sirve para dos
 * cosas concretas: traer listas hechas en otra app y llevarse las de acá.
 *
 * Al importar se resuelve cada línea contra la biblioteca en tres pasos, del más exacto al
 * más tolerante — el mismo archivo puede venir como `content://`, como ruta absoluta de
 * otra PC o sólo como nombre de archivo:
 *
 *   1. la URI tal cual,
 *   2. la ruta (normalizando `\` de Windows y mayúsculas),
 *   3. el nombre del archivo.
 *
 * Lo que no se encuentra se descarta: una lista importada no puede apuntar a archivos que
 * no existen en este teléfono.
 */
object M3uPlaylist {

    private const val HEADER = "#EXTM3U"

    /** Texto `.m3u8` de la lista, con duración y "Artista - Título" en cada entrada. */
    fun serialize(name: String, tracks: List<Track>): String = buildString {
        appendLine(HEADER)
        appendLine("#PLAYLIST:$name")
        for (track in tracks) {
            val seconds = (track.durationMs / 1000L).coerceAtLeast(0L)
            val label = listOf(track.effectiveArtist, track.title)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            appendLine("#EXTINF:$seconds,$label")
            appendLine(track.filePath.ifBlank { track.uri.toString() })
        }
    }

    /** URIs de las pistas de la biblioteca que aparecen en el texto, en orden y sin repetir. */
    fun parse(text: String, library: List<Track>): List<String> {
        val byUri = HashMap<String, Track>(library.size)
        val byPath = HashMap<String, Track>(library.size)
        val byPathKey = HashMap<String, Track>(library.size)
        val byName = HashMap<String, Track>(library.size)
        for (track in library) {
            byUri[track.uri.toString()] = track
            if (track.filePath.isNotBlank()) {
                byPath[track.filePath] = track
                byPathKey[normalize(track.filePath)] = track
            }
            val name = track.fileName.ifBlank { track.filePath.substringAfterLast('/') }
            if (name.isNotBlank()) byName.putIfAbsent(name.lowercase(), track)
        }

        val result = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val track = byUri[line]
                ?: byPath[line]
                ?: byPathKey[normalize(line)]
                ?: byName[line.substringAfterLast('/').substringAfterLast('\\').lowercase()]
                ?: continue
            result.add(track.uri.toString())
        }
        return result.toList()
    }

    /** "Mi lista.m3u8" → "Mi lista". */
    fun playlistNameFrom(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.', fileName).trim()
        return withoutExtension.ifBlank { "Imported playlist" }.take(80)
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim().lowercase()
}
