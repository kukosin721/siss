@file:Suppress("unused", "ClassName")

package com.kukosin.app.data.analysis

/**
 * Stubs de tipado para el arnés `kt-check.sh` (no se incluyen en el build de la app).
 *
 * Solo declaran los tipos de letras que usa `LyricsIndex`, para poder comprobar sus tipos
 * sin el SDK de Android. El `EmbeddedMetadata` real se excluye del chequeo porque depende
 * de `MediaMetadataRetriever`.
 */

data class LyricWord(val timeMs: Long, val text: String)

data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

data class EmbeddedLyrics(
    val plainText: String,
    val synced: List<LyricLine>,
    val source: String,
)

internal object EmbeddedMetadata {
    fun extractLyrics(context: android.content.Context, uri: android.net.Uri, filePath: String): EmbeddedLyrics? = null
}
