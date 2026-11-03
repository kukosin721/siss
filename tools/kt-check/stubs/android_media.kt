@file:Suppress("unused", "UNUSED_PARAMETER")
package android.media
class AudioDeviceInfo {
    companion object { const val TYPE_BUILTIN_SPEAKER: Int = 2 }
    val id: Int = 0
    val type: Int = 0
    val productName: CharSequence? = null
}
/** Manager de audio: sólo lo que usa la app (leer el volumen del sistema). */
class AudioManager {
    fun getStreamMaxVolume(stream: Int): Int = 15
    fun getStreamMinVolume(stream: Int): Int = 0
    fun getStreamVolume(stream: Int): Int = 10

    companion object {
        const val STREAM_MUSIC: Int = 3
    }
}

object AudioFormat {
    const val CHANNEL_OUT_MONO: Int = 4
    const val CHANNEL_OUT_STEREO: Int = 12
}


class MediaMetadataRetriever {
    fun setDataSource(path: String) = Unit
    fun setDataSource(context: android.content.Context, uri: android.net.Uri) = Unit
    fun extractMetadata(keyCode: Int): String? = null
    fun getEmbeddedPicture(): ByteArray? = null
    fun release() = Unit

    companion object {
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_BITRATE = 20
        const val METADATA_KEY_MIMETYPE = 12
        const val METADATA_KEY_ALBUM = 1
        const val METADATA_KEY_ARTIST = 2
        const val METADATA_KEY_ALBUMARTIST = 13
        const val METADATA_KEY_TITLE = 7
        const val METADATA_KEY_GENRE = 6
        const val METADATA_KEY_YEAR = 8
        const val METADATA_KEY_CD_TRACK_NUMBER = 5
        const val METADATA_KEY_DISC_NUMBER = 14
        const val METADATA_KEY_COMPOSER = 4
        const val METADATA_KEY_SAMPLERATE = 31
        const val METADATA_KEY_BITS_PER_SAMPLE = 39
        const val METADATA_KEY_NUM_TRACKS = 10
        const val METADATA_KEY_COMMENT = 3
    }
}
