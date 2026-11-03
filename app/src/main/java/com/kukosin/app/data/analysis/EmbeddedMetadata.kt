package com.kukosin.app.data.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class LyricWord(
    val timeMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
) {
    val isKaraoke: Boolean get() = words.size > 1
}

data class EmbeddedLyrics(
    val plainText: String,
    val synced: List<LyricLine>,
    val source: String,
)

data class TagSnapshot(
    val fields: List<Pair<String, String>>,
    val lyrics: EmbeddedLyrics?,
)

internal object EmbeddedMetadata {
    /**
     * Extrae **sólo** las letras, sin volcar etiquetas ni abrir
     * `MediaMetadataRetriever`. Es la ruta rápida que usa [LyricsIndex] al indexar la
     * biblioteca para la búsqueda por letra: cuesta una fracción de [extract].
     */
    fun extractLyrics(context: Context, uri: Uri, filePath: String): EmbeddedLyrics? {
        var lyrics: EmbeddedLyrics? = null
        runCatching {
            openStream(context, uri, filePath)?.use { raw ->
                val input = BufferedInputStream(raw, 64 * 1024)
                input.mark(1024 * 1024)
                val header = ByteArray(12)
                val read = input.read(header)
                input.reset()
                if (read >= 3 && header.copyOf(3).decodeToString() == "ID3") {
                    lyrics = parseId3Only(input)
                } else if (read >= 4 && header.copyOf(4).decodeToString() == "fLaC") {
                    input.skip(4)
                    lyrics = parseFlacFromBlockStart(input, LinkedHashMap())?.let { it }
                } else if (read >= 4 && header.copyOf(4).decodeToString() == "OggS") {
                    lyrics = parseOggComments(input, LinkedHashMap())
                } else {
                    lyrics = scanMp4Lyrics(input, LinkedHashMap())
                }
            }
        }
        return lyrics
    }

    /**
     * ID3 hasta encontrar USLT/SYLT. Se detiene en cuanto tiene letras en vez de
     * recorrer todos los frames (y no arma el mapa de etiquetas).
     */
    private fun parseId3Only(input: InputStream): EmbeddedLyrics? {
        val fields = LinkedHashMap<String, String>()
        return parseId3(input, fields)
    }

    fun extract(context: Context, uri: Uri, filePath: String): TagSnapshot {
        val fields = LinkedHashMap<String, String>()
        dumpRetriever(context, uri, fields)
        var lyrics: EmbeddedLyrics? = null
        runCatching {
            openStream(context, uri, filePath)?.use { raw ->
                val input = BufferedInputStream(raw, 64 * 1024)
                input.mark(1024 * 1024)
                val header = ByteArray(12)
                val read = input.read(header)
                input.reset()
                if (read >= 3 && header.copyOf(3).decodeToString() == "ID3") {
                    parseId3(input, fields)?.let { lyrics = it }
                    input.mark(32)
                    val maybeFlac = ByteArray(4)
                    if (input.read(maybeFlac) == 4 && maybeFlac.decodeToString() == "fLaC") {
                        parseFlacFromBlockStart(input, fields)?.let { lyrics = lyrics ?: it }
                    }
                } else if (read >= 4 && header.copyOf(4).decodeToString() == "fLaC") {
                    input.skip(4)
                    parseFlacFromBlockStart(input, fields)?.let { lyrics = it }
                } else if (read >= 4 && header.copyOf(4).decodeToString() == "OggS") {
                    parseOggComments(input, fields)?.let { lyrics = it }
                } else {
                    lyrics = scanMp4Lyrics(input, fields)
                }
            }
        }
        return TagSnapshot(fields.toList(), lyrics)
    }

    private fun openStream(context: Context, uri: Uri, filePath: String): InputStream? {
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: filePath.takeIf { it.isNotBlank() }?.let { java.io.File(it).takeIf { file -> file.exists() }?.inputStream() }
    }

    private fun dumpRetriever(context: Context, uri: Uri, fields: MutableMap<String, String>) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, uri)
            val keys = buildList {
                add(MediaMetadataRetriever.METADATA_KEY_TITLE to "Title (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artist (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_ALBUM to "Album (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "Album Artist")
                add(MediaMetadataRetriever.METADATA_KEY_AUTHOR to "Author")
                add(MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Composer (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_WRITER to "Writer")
                add(MediaMetadataRetriever.METADATA_KEY_GENRE to "Genre (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_DATE to "Date")
                add(MediaMetadataRetriever.METADATA_KEY_YEAR to "Year (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_DURATION to "Duration ms (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "MIME (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_BITRATE to "Bitrate (MMR)")
                add(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "Track")
                add(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "Disc")
                add(MediaMetadataRetriever.METADATA_KEY_COMPILATION to "Compilation")
                add(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS to "Media tracks")
                add(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO to "Has audio")
                add(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO to "Has video")
                if (Build.VERSION.SDK_INT >= 31) {
                    add(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE to "Sample rate (MMR)")
                    add(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE to "Bits per sample (MMR)")
                }
            }
            keys.forEach { (key, label) ->
                val value = retriever.extractMetadata(key)
                if (!value.isNullOrBlank()) fields.putIfAbsent(label, value)
            }
        }
        retriever.release()
    }

    private fun parseId3(input: InputStream, fields: MutableMap<String, String>): EmbeddedLyrics? {
        // La ruta "sólo letras" pasa un mapa descartable: si ya hay SYLT (sincronizada),
        // no hace falta seguir leyendo el resto del tag.

        val header = ByteArray(10)
        if (input.read(header) != 10) return null
        if (header.copyOf(3).decodeToString() != "ID3") return null
        val version = header[3].toInt()
        val size = synchsafe(header, 6)
        val tag = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(tag, offset, size - offset)
            if (n <= 0) break
            offset += n
        }
        var pos = 0
        var lyrics: EmbeddedLyrics? = null
        while (pos + 10 < tag.size) {
            val id = tag.copyOfRange(pos, pos + 4).toString(Charsets.ISO_8859_1)
            if (id[0] < 'A' || id[0] > 'Z') break
            val frameSize = if (version >= 4) synchsafe(tag, pos + 4) else {
                ((tag[pos + 4].toInt() and 0xFF) shl 24) or
                    ((tag[pos + 5].toInt() and 0xFF) shl 16) or
                    ((tag[pos + 6].toInt() and 0xFF) shl 8) or
                    (tag[pos + 7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || pos + 10 + frameSize > tag.size) break
            val body = tag.copyOfRange(pos + 10, pos + 10 + frameSize)
            when (id) {
                "USLT", "ULT" -> lyrics = lyrics ?: parseUslt(body, "ID3 USLT")
                "SYLT", "SLT" -> lyrics = mergeSylt(lyrics, body)
                "COMM" -> putComment(fields, "ID3 comment", body)
                "TENC" -> fields.putIfAbsent("Encoder", decodeTextFrame(body))
                "TSSE" -> fields.putIfAbsent("Encoder settings", decodeTextFrame(body))
                "TIT2" -> fields.putIfAbsent("ID3 title", decodeTextFrame(body))
                "TPE1" -> fields.putIfAbsent("ID3 artist", decodeTextFrame(body))
                "TPE2" -> fields.putIfAbsent("Album Artist ID3", decodeTextFrame(body))
                "TALB" -> fields.putIfAbsent("ID3 album", decodeTextFrame(body))
                "TCON" -> fields.putIfAbsent("ID3 genre", decodeTextFrame(body))
                "TYER", "TDRC" -> fields.putIfAbsent("ID3 date", decodeTextFrame(body))
                "TRCK" -> fields.putIfAbsent("ID3 track", decodeTextFrame(body))
                "TPOS" -> fields.putIfAbsent("ID3 disc", decodeTextFrame(body))
                "TCOM" -> fields.putIfAbsent("ID3 composer", decodeTextFrame(body))
                "TPUB" -> fields.putIfAbsent("Label", decodeTextFrame(body))
                "TSRC" -> fields.putIfAbsent("ISRC", decodeTextFrame(body))
                "TBPM" -> fields.putIfAbsent("BPM", decodeTextFrame(body))
                "TKEY" -> fields.putIfAbsent("Key", decodeTextFrame(body))
                "TCOP" -> fields.putIfAbsent("Copyright", decodeTextFrame(body))
                "TLEN" -> fields.putIfAbsent("Tag duration ms", decodeTextFrame(body))
                "TXXX" -> parseTxxx(body, fields)
            }
            pos += 10 + frameSize
        }
        return lyrics
    }

    private fun parseTxxx(body: ByteArray, fields: MutableMap<String, String>) {
        val full = decodeTextFrame(body)
        if (full.isBlank()) return
        val parts = full.split('\u0000', '=', limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) {
            val key = parts[0].trim().uppercase()
            val valStr = parts[1].trim()
            val label = when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> "ReplayGain Track"
                "REPLAYGAIN_TRACK_PEAK" -> "ReplayGain Track Peak"
                "REPLAYGAIN_ALBUM_GAIN" -> "ReplayGain Album"
                "REPLAYGAIN_ALBUM_PEAK" -> "ReplayGain Album Peak"
                "BARCODE", "UPC", "EAN" -> "Barcode / UPC"
                "CATALOGNUMBER" -> "Catalog Number"
                "LABEL" -> "Record Label"
                "MQAENCODER" -> "MQA encoder"
                else -> "TXXX ($key)"
            }
            fields.putIfAbsent(label, valStr)
        } else {
            fields.putIfAbsent("TXXX Extra", full)
        }
    }

    private fun parseFlacFromBlockStart(input: InputStream, fields: MutableMap<String, String>): EmbeddedLyrics? {
        var lyrics: EmbeddedLyrics? = null
        repeat(32) {
            val blockHeader = ByteArray(4)
            if (input.read(blockHeader) != 4) return lyrics
            val last = blockHeader[0].toInt() and 0x80 != 0
            val type = blockHeader[0].toInt() and 0x7F
            val size = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            if (size < 0 || size > 8_000_000) return lyrics
            val data = ByteArray(size)
            var got = 0
            while (got < size) {
                val n = input.read(data, got, size - got)
                if (n <= 0) return lyrics
                got += n
            }
            when (type) {
                0 -> parseFlacStreamInfo(data, fields)
                4 -> lyrics = lyrics ?: ingestVorbis(data, fields, "Vorbis / FLAC")
            }
            if (last) return lyrics
        }
        return lyrics
    }

    private fun parseFlacStreamInfo(data: ByteArray, fields: MutableMap<String, String>) {
        if (data.size < 18) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val minBlock = buf.short.toInt() and 0xFFFF
        val maxBlock = buf.short.toInt() and 0xFFFF
        fields["FLAC min block"] = minBlock.toString()
        fields["FLAC max block"] = maxBlock.toString()
        val packed = ((data[10].toLong() and 0xFF) shl 40) or
            ((data[11].toLong() and 0xFF) shl 32) or
            ((data[12].toLong() and 0xFF) shl 24) or
            ((data[13].toLong() and 0xFF) shl 16) or
            ((data[14].toLong() and 0xFF) shl 8) or
            (data[15].toLong() and 0xFF)
        val sampleRate = (packed shr 12).toInt()
        val channels = (((packed shr 9) and 7).toInt()) + 1
        val bits = (((packed shr 4) and 31).toInt()) + 1
        fields["FLAC sample rate"] = "$sampleRate Hz"
        fields["FLAC channels"] = channels.toString()
        fields["FLAC bits"] = "$bits-bit"
    }

    private fun parseOggComments(input: InputStream, fields: MutableMap<String, String>): EmbeddedLyrics? {
        val bytes = ByteArray(256 * 1024)
        val n = input.read(bytes)
        if (n <= 0) return null
        val text = bytes.copyOf(n)
        val marker = "vorbis".toByteArray(Charsets.US_ASCII)
        val index = indexOf(text, marker)
        if (index < 0) return null
        var pos = index + marker.size
        if (pos + 4 >= text.size) return null
        if (text.getOrNull(index - 1)?.toInt() != 3) return ingestVorbis(text.copyOfRange(pos, n), fields, "Vorbis / OGG")
        return ingestVorbis(text.copyOfRange(pos, n), fields, "Vorbis / OGG")
    }

    private fun ingestVorbis(data: ByteArray, fields: MutableMap<String, String>, source: String): EmbeddedLyrics? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = buf.int
        if (vendorLen < 0 || vendorLen > buf.remaining()) return null
        val vendor = data.copyOfRange(buf.position(), buf.position() + vendorLen).toString(Charsets.UTF_8)
        fields["Vendor"] = vendor
        buf.position(buf.position() + vendorLen)
        if (buf.remaining() < 4) return null
        val count = buf.int
        var lyrics: EmbeddedLyrics? = null
        repeat(count.coerceAtMost(512)) {
            if (buf.remaining() < 4) return@repeat
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return@repeat
            val comment = data.copyOfRange(buf.position(), buf.position() + len).toString(Charsets.UTF_8)
            buf.position(buf.position() + len)
            val key = comment.substringBefore('=').uppercase()
            val value = comment.substringAfter('=', missingDelimiterValue = "")
            if (value.isBlank()) return@repeat
            when (key) {
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRICSUTF8" ->
                    lyrics = lyrics ?: toLyrics(value, "$source $key")
                "SYNCEDLYRICS", "LYRICS_SYNCHED" ->
                    lyrics = mergePlain(lyrics, toLyrics(value, "$source $key"))
                "ENCODER", "VENDOR" -> fields.putIfAbsent("Encoder", value)
                "ISRC" -> fields.putIfAbsent("ISRC", value)
                "REPLAYGAIN_TRACK_GAIN" -> fields.putIfAbsent("ReplayGain track", value)
                "REPLAYGAIN_ALBUM_GAIN" -> fields.putIfAbsent("ReplayGain album", value)
                "DATE", "YEAR" -> fields.putIfAbsent("Tag date", value)
                "TITLE", "ARTIST", "ALBUM", "GENRE", "COMPOSER", "ALBUMARTIST", "TRACKNUMBER", "DISCNUMBER" ->
                    fields.putIfAbsent(key.lowercase().replaceFirstChar { it.titlecase() }, value)
                else -> fields.putIfAbsent(key.lowercase(), value)
            }
        }
        return lyrics
    }

    private fun scanMp4Lyrics(input: InputStream, fields: MutableMap<String, String>): EmbeddedLyrics? {
        val bytes = ByteArray(768 * 1024)
        val n = input.read(bytes)
        if (n <= 0) return null
        val data = bytes.copyOf(n)
        val marker = "©lyr".toByteArray(Charsets.ISO_8859_1)
        val at = indexOf(data, marker)
        if (at < 0) return null
        val dataAt = indexOf(data, "data".toByteArray(), at)
        if (dataAt < 0 || dataAt + 16 >= data.size) return null
        val size = ByteBuffer.wrap(data, dataAt - 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val start = dataAt + 8
        val end = (dataAt - 4 + size).coerceAtMost(data.size)
        if (start >= end) return null
        val payload = data.copyOfRange(start, end)
        val text = decodeMaybeUtf16(payload).trim()
        if (text.isBlank()) return null
        fields["MP4 lyrics"] = "Embedded (${text.length} characters)"
        return toLyrics(text, "MP4 ©lyr")
    }

    private fun parseUslt(body: ByteArray, source: String): EmbeddedLyrics? {
        if (body.isEmpty()) return null
        val encoding = body[0].toInt()
        val charset = charsetFor(encoding)
        var pos = 4
        if (body.size <= pos) return null
        while (pos < body.size && !(body[pos] == 0.toByte() && (encoding < 1 || encoding > 2 || pos + 1 >= body.size || body[pos + 1] == 0.toByte()))) {
            pos++
            if (pos > body.size - 1) break
        }
        pos += if (encoding == 1 || encoding == 2) 2 else 1
        if (pos >= body.size) return null
        val text = body.copyOfRange(pos, body.size).toString(charset).trim('\u0000', ' ', '\n')
        if (text.isBlank()) return null
        return toLyrics(text, source)
    }

    private fun mergeSylt(current: EmbeddedLyrics?, body: ByteArray): EmbeddedLyrics? {
        if (body.size < 6) return current
        val encoding = body[0].toInt()
        val charset = charsetFor(encoding)
        var pos = 6
        while (pos < body.size && body[pos] != 0.toByte()) pos++
        pos += if (encoding == 1 || encoding == 2) 2 else 1
        val events = ArrayList<Pair<Long, String>>()
        while (pos + 1 < body.size) {
            val start = pos
            while (pos < body.size && body[pos] != 0.toByte()) pos++
            val text = body.copyOfRange(start, pos).toString(charset)
            pos++
            if (pos + 4 > body.size) break
            val stamp = ByteBuffer.wrap(body, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
            pos += 4
            events += stamp to text
        }
        val lines = groupSyltEvents(events)
        if (lines.isEmpty()) return current
        val plain = lines.joinToString("\n") { it.text }
        return EmbeddedLyrics(plain, lines, "ID3 SYLT")
    }

    private fun putComment(fields: MutableMap<String, String>, label: String, body: ByteArray) {
        if (body.size < 5) return
        val text = decodeTextFrame(body.copyOfRange(4, body.size))
        if (text.isNotBlank()) fields.putIfAbsent(label, text)
    }

    private fun decodeTextFrame(body: ByteArray): String {
        if (body.isEmpty()) return ""
        return body.copyOfRange(1, body.size).toString(charsetFor(body[0].toInt())).trim('\u0000')
    }

    private fun charsetFor(encoding: Int): Charset = when (encoding) {
        1, 2 -> Charsets.UTF_16
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun decodeMaybeUtf16(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.toString(Charsets.UTF_16LE)
        }
        return bytes.toString(Charsets.UTF_8).ifBlank { bytes.toString(Charsets.ISO_8859_1) }
    }

    private fun toLyrics(text: String, source: String): EmbeddedLyrics {
        val synced = parseLrc(text)
        return EmbeddedLyrics(text.trim(), synced, source)
    }

    private fun mergePlain(current: EmbeddedLyrics?, extra: EmbeddedLyrics): EmbeddedLyrics {
        return current ?: extra
    }

    fun parseLrc(text: String): List<LyricLine> {
        val stampRegex = Regex("""[\[<](\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?[]>]""")
        val metaRegex = Regex("""^\[([a-zA-Z]+):(.*)]$""")
        val result = mutableListOf<LyricLine>()
        var offsetMs = 0L
        text.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            val meta = metaRegex.matchEntire(trimmed)
            if (meta != null && meta.groupValues[1].any { it.isLetter() }) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }
            val stamps = stampRegex.findAll(trimmed).toList()
            if (stamps.isEmpty()) return@forEach
            val prefixOnly = stamps.all { it.value.startsWith("[") } &&
                stamps.zipWithNext().all { (a, b) ->
                    trimmed.substring(a.range.last + 1, b.range.first).isBlank()
                } &&
                trimmed.substring(0, stamps.first().range.first).isBlank()
            if (prefixOnly) {
                val content = trimmed.substring(stamps.last().range.last + 1).trim()
                if (content.isBlank()) return@forEach
                stamps.forEach { match ->
                    result += LyricLine(parseStamp(match) + offsetMs, content)
                }
            } else {
                val words = ArrayList<LyricWord>()
                stamps.forEachIndexed { index, match ->
                    val from = match.range.last + 1
                    val to = if (index + 1 < stamps.size) stamps[index + 1].range.first else trimmed.length
                    val piece = trimmed.substring(from, to)
                    if (piece.isNotBlank()) {
                        words += LyricWord(parseStamp(match) + offsetMs, piece)
                    }
                }
                if (words.isEmpty()) return@forEach
                result += LyricLine(
                    timeMs = parseStamp(stamps.first()) + offsetMs,
                    text = words.joinToString("") { it.text }.replace(Regex("""\s+"""), " ").trim(),
                    words = words,
                )
            }
        }
        return result.sortedBy { it.timeMs }
    }

    internal fun groupSyltEvents(events: List<Pair<Long, String>>): List<LyricLine> {
        if (events.isEmpty()) return emptyList()
        val fragments = events.map { it.first to it.second.replace("\u0000", "") }
        val wordLike = fragments.count { (_, text) ->
            val t = text.trim()
            t.isNotEmpty() && t.length <= 16 && !t.contains('\n')
        } * 2 >= fragments.size
        if (!wordLike) {
            return fragments.mapNotNull { (time, text) ->
                val t = text.trim()
                if (t.isBlank()) null else LyricLine(time, t)
            }
        }
        val lines = ArrayList<LyricLine>()
        val words = ArrayList<LyricWord>()
        fun flush() {
            if (words.isEmpty()) return
            val display = words.joinToString("") { it.text }
            lines += LyricLine(
                timeMs = words.first().timeMs,
                text = display.replace(Regex("""\s+"""), " ").trim(),
                words = words.toList(),
            )
            words.clear()
        }
        fragments.forEach { (time, text) ->
            if (text.contains('\n')) {
                text.split('\n').forEachIndexed { i, part ->
                    if (part.isNotEmpty()) words += LyricWord(time, part)
                    if (i < text.count { it == '\n' }) flush()
                }
            } else if (text.isBlank()) {
                flush()
            } else {
                val gap = words.lastOrNull()?.let { time - it.timeMs } ?: 0L
                if (words.isNotEmpty() && gap > 2_400L) flush()
                words += LyricWord(time, text)
            }
        }
        flush()
        return lines
    }

    private fun parseStamp(match: MatchResult): Long {
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val frac = match.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min * 60_000L + sec * 1000L + ms
    }

    private fun synchsafe(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..(data.size - needle.size)) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
