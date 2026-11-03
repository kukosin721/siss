package com.kukosin.app.data

import android.net.Uri
import com.kukosin.app.data.artwork.EmbeddedArtwork
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val durationMs: Long = 0L,
    val albumId: Long = 0L,
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val mimeType: String = "",
    val fileName: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val genre: String = "",
    val composer: String = "",
    val filePath: String = "",
    val fileSize: Long = 0L,
    val comment: String = "",
    /** Carátula externa (p. ej. thumbnail de YouTube Music); si está, gana sobre la del álbum. */
    val artworkOverride: Uri? = null,
    /** Códec ya resuelto por el servicio de streaming (WebM/Opus, etc.). */
    val streamCodecLabel: String = "",
) {
    /**
     * Carátula para mostrar en la app: sale del propio archivo a resolución completa
     * (ver [EmbeddedArtwork]); antes era la miniatura de MediaStore, que se veía borrosa.
     */
    val artworkUri: Uri
        get() = artworkOverride ?: EmbeddedArtwork.uriFor(uri, albumId)

    /**
     * Carátula para el sistema (notificación, pantalla de bloqueo): la de MediaStore, porque
     * la carga media3 con su decodificador y no entiende el esquema de [artworkUri].
     */
    val systemArtworkUri: Uri
        get() = artworkOverride ?: "content://media/external/audio/albumart/$albumId".toUri()

    val codecLabel: String
        get() = streamCodecLabel.ifBlank { codecFrom(fileName, mimeType, bitDepth, bitrate) }

    /** true si el audio es un archivo del dispositivo (no un stream de red). */
    val isLocal: Boolean
        get() = uri.scheme == "content" || uri.scheme == "file"

    val effectiveArtist: String
        get() = artist.ifBlank { albumArtist.ifBlank { "Unknown artist" } }

    val effectiveAlbumArtist: String
        get() = albumArtist.ifBlank { artist.ifBlank { "Unknown artist" } }

    val isHiRes: Boolean
        get() = sampleRate >= 48000 || bitDepth > 16 || codecLabel in listOf("FLAC", "DSD", "ALAC", "WavPack", "APE", "TrueHD")

    val qualityLabel: String
        get() {
            val parts = buildList {
                add(codecLabel)
                if (bitDepth > 0) add("$bitDepth-bit")
                when {
                    sampleRate >= 1000 -> add("${"%.1f".format(sampleRate / 1000.0)} kHz")
                    sampleRate > 0 -> add("$sampleRate Hz")
                }
            }
            return parts.joinToString(" · ")
        }

    val fileSizeLabel: String
        get() {
            if (fileSize <= 0) return "Unknown"
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> "%.2f GB".format(gb)
                mb >= 1.0 -> "%.1f MB".format(mb)
                else -> "%.0f KB".format(kb)
            }
        }

    fun toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(effectiveArtist)
            .setAlbumTitle(album)
            .setAlbumArtist(effectiveAlbumArtist)
            .setArtworkUri(systemArtworkUri)
            .setTrackNumber(if (trackNumber > 0) trackNumber else null)
            .setDiscNumber(if (discNumber > 0) discNumber else null)
            .setGenre(genre.ifBlank { null })
            .setComposer(composer.ifBlank { null })
            .setRecordingYear(if (year > 0) year else null)
            .build()
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}

fun codecFrom(fileName: String, mimeType: String, bitDepth: Int, bitrate: Int): String {
    val name = fileName.lowercase()
    val mime = mimeType.lowercase()
    return when {
        name.endsWith(".flac") || "flac" in mime -> "FLAC"
        name.endsWith(".dsf") || name.endsWith(".dff") || "dsd" in mime -> "DSD"
        name.endsWith(".ape") || "ape" in mime -> "APE"
        name.endsWith(".wv") || "wavpack" in mime -> "WavPack"
        name.endsWith(".aiff") || name.endsWith(".aif") || "aiff" in mime -> "AIFF"
        name.endsWith(".wav") || "wav" in mime -> "WAV"
        name.endsWith(".alac") -> "ALAC"
        name.endsWith(".m4a") && bitDepth >= 16 && bitrate > 400_000 -> "ALAC"
        "alac" in mime -> "ALAC"
        name.endsWith(".mka") -> "MKA"
        name.endsWith(".mlp") || "mlp" in mime -> "MLP"
        name.endsWith(".thd") || "truehd" in mime -> "TrueHD"
        name.endsWith(".dts") || "dts" in mime || "dca" in mime -> "DTS"
        name.endsWith(".ac3") || "ac3" in mime -> "AC3"
        name.endsWith(".eac3") || "eac3" in mime -> "E-AC3"
        name.endsWith(".opus") || "opus" in mime -> "Opus"
        name.endsWith(".ogg") || name.endsWith(".oga") || "ogg" in mime || "vorbis" in mime -> "OGG"
        name.endsWith(".mp3") || "mpeg" in mime -> "MP3"
        name.endsWith(".m4a") || name.endsWith(".aac") || "mp4" in mime || "aac" in mime -> "AAC"
        name.endsWith(".tta") -> "TTA"
        name.endsWith(".tak") -> "TAK"
        else -> mime.substringAfter('/', "").uppercase().ifBlank { "AUDIO" }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun bitDepthFromSampleFormat(sampleFormat: String?): Int {
    if (sampleFormat.isNullOrBlank()) return 0
    val format = sampleFormat.lowercase()
    return when {
        "64" in format -> 64
        "32" in format || format.startsWith("fl") -> 32
        "24" in format -> 24
        "16" in format -> 16
        "8" in format -> 8
        else -> 0
    }
}
