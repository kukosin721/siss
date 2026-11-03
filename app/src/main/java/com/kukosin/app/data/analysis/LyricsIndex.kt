package com.kukosin.app.data.analysis

import android.content.Context
import android.net.Uri
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Texto de las letras, normalizado y cacheado, para poder buscar dentro de ellas.
 *
 * Dos problemas que resuelve:
 *
 *  1. **Velocidad.** Leer las letras de cada archivo implica abrir el contenedor y
 *     parsear ID3/Vorbis/MP4 (decenas de milisegundos por archivo, cientos de MB de
 *     I/O para una biblioteca mediana). Buscar por letra así se sentía "trabado".
 *     Acá cada archivo se lee **una vez**, se guarda solo el texto normalizado (no
 *     los tags ni las marcas de tiempo) y se reutiliza en todas las búsquedas.
 *
 *  2. **Diacríticos y mayúsculas.** "Corazón" debe encontrarse escribiendo "corazon".
 *     [normalize] quita los acentos y baja a minúsculas una sola vez, cuando se
 *     indexa, en vez de en cada pulsación de tecla.
 *
 * El trabajo pesado se hace en un hilo de fondo ([ensureIndexed]) y la UI sólo lee
 * [textFor] / [matches], que son consultas O(1) sobre el mapa ya construido.
 */
object LyricsIndex {

    /** Tope de archivos que se escanean por pasada (bibliotecas enormes). */
    private const val MAX_TRACKS = 20_000

    /** Tope de caracteres guardados por canción: alcanza para buscar, no llena la RAM. */
    private const val MAX_CHARS = 12_000

    private val texts = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val indexing = AtomicBoolean(false)

    /** Cuántas letras hay indexadas (para mostrar progreso en la UI). */
    @Volatile
    var indexedCount: Int = 0
        private set

    /** `true` mientras hay una pasada de indexación en curso. */
    val isIndexing: Boolean get() = indexing.get()

    /**
     * Letra (texto plano + sincronizada) en minúsculas y sin acentos.
     * Cadena vacía = ese archivo no tiene letras.
     */
    fun textFor(uri: Uri): String = texts[uri.toString()].orEmpty()

    fun hasLyrics(uri: Uri): Boolean = texts.containsKey(uri.toString())

    /** ¿El texto ya indexado contiene [needle]? [needle] debe venir de [normalize]. */
    fun matches(uri: Uri, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val text = texts[uri.toString()] ?: return false
        return text.contains(needle)
    }

    /** Guarda el texto de una canción ya analizada (evita releer el archivo). */
    fun put(uri: Uri, lyrics: EmbeddedLyrics?) {
        val key = uri.toString()
        val text = buildText(lyrics)
        if (text.isEmpty()) {
            texts.remove(key)
            missing.add(key)
        } else {
            missing.remove(key)
            texts[key] = text
        }
        indexedCount = texts.size
    }

    /**
     * Indexa las letras que falten. Es incremental y cancelable: si se llama de nuevo
     * mientras corre, no se solapan dos pasadas.
     *
     * @param uriFor devuelve la uri a abrir (content:// o file://) de cada entrada.
     * @param onProgress recibe (indexadas, total) aproximadamente cada archivo.
     */
    fun ensureIndexed(
        context: Context,
        tracks: List<Uri>,
        filePathFor: (Uri) -> String,
        onProgress: (indexed: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        if (!indexing.compareAndSet(false, true)) return
        try {
            val pending = tracks.take(MAX_TRACKS).filter { uri ->
                val key = uri.toString()
                !texts.containsKey(key) && !missing.contains(key)
            }
            if (pending.isEmpty()) return
            pending.forEachIndexed { index, uri ->
                val extracted = runCatching {
                    EmbeddedMetadata.extractLyrics(context, uri, filePathFor(uri))
                }.getOrNull()
                put(uri, extracted)
                if (index % 8 == 0) {
                    onProgress(index + 1, pending.size)
                }
            }
            onProgress(pending.size, pending.size)
        } finally {
            indexing.set(false)
        }
    }

    fun clear() {
        texts.clear()
        missing.clear()
        indexedCount = 0
    }

    // ------------------------------------------------------------------ interno

    private fun buildText(lyrics: EmbeddedLyrics?): String {
        if (lyrics == null) return ""
        val builder = StringBuilder()
        if (lyrics.plainText.isNotBlank()) builder.append(lyrics.plainText).append('\n')
        for (line in lyrics.synced) {
            for (word in line.words) {
                builder.append(word.text).append(' ')
            }
            if (line.words.isEmpty() && line.text.isNotBlank()) {
                builder.append(line.text).append('\n')
            }
        }
        return normalize(builder.toString().take(MAX_CHARS))
    }

    /**
     * Minúsculas sin acentos ni signos: "Corazón, ¿dónde estás?" -> "corazon donde estas".
     * Se aplica al índice y a la consulta con la misma función, así el `contains` es
     * exacto y no hay que normalizar en cada comparación.
     */
    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (char in decomposed) {
            when {
                char.isLetterOrDigit() -> builder.append(char.lowercaseChar())
                // La descomposición NFD deja el acento como marca combinante aparte
                // (U+0301 tras la "o" de "corazón"): se descarta, no se convierte en
                // espacio, o "corazón" quedaría como "corazo n" y no se encontraría.
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                char == '\'' || char == '’' -> Unit      // don't -> dont
                else -> builder.append(' ')
            }
        }
        // Colapsa espacios múltiples: buscar "te quiero" no debe depender de saltos de línea.
        return builder.toString().replace(Regex("\\s+"), " ").trim()
    }
}
