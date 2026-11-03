@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.common
object C {
    const val MICROS_PER_SECOND: Long = 1_000_000L
    const val RESULT_END_OF_INPUT: Int = -1
    const val LENGTH_UNSET: Int = -1
    const val TIME_UNSET: Long = Long.MIN_VALUE
    const val TRACK_TYPE_AUDIO: Int = 1
    const val BUFFER_FLAG_KEY_FRAME: Int = 1
    const val AUDIO_SESSION_ID_UNSET: Int = 0
    const val ENCODING_PCM_16BIT: Int = 2
    const val ENCODING_PCM_24BIT: Int = 21
    const val ENCODING_PCM_32BIT: Int = 22
    const val ENCODING_PCM_FLOAT: Int = 4
}
class PlaybackParameters { companion object { val DEFAULT = PlaybackParameters() } }
class Format {
    val pcmEncoding: Int = 0
    val channelCount: Int = 2
    val sampleRate: Int = 48000

    class Builder {
        fun setSampleMimeType(mimeType: String) = this
        fun setPcmEncoding(encoding: Int) = this
        fun setSampleRate(rate: Int) = this
        fun setChannelCount(count: Int) = this
        fun setCodecs(codecs: String) = this
        fun setAverageBitrate(bitrate: Int) = this
        fun build(): Format = Format()
    }

    companion object {
        const val NO_VALUE: Int = -1
    }
}


// --- lo que necesita el extractor FLAC propio ------------------------------------------
class MediaItem {
    class LocalConfiguration(val uri: android.net.Uri)
    val localConfiguration: LocalConfiguration? = null
}

object MimeTypes {
    const val AUDIO_RAW: String = "audio/raw"
    const val AUDIO_FLAC: String = "audio/flac"
}

class DataSpec

interface DataReader {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}
