@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray

/**
 * Stubs mínimos de `androidx.media3.extractor`. Las firmas están copiadas de la 1.5.1
 * (libraries/extractor/...): si una cambia, `kt-check.sh` deja de compilar y avisa.
 */
interface Extractor {
    fun init(output: ExtractorOutput)
    fun sniff(input: ExtractorInput): Boolean
    fun read(input: ExtractorInput, seekPosition: PositionHolder): Int
    fun seek(position: Long, timeUs: Long)
    fun release()

    companion object {
        const val RESULT_CONTINUE: Int = 0
        const val RESULT_SEEK: Int = 1
        const val RESULT_END_OF_INPUT: Int = C.RESULT_END_OF_INPUT
    }
}

interface ExtractorInput {
    fun read(target: ByteArray, offset: Int, length: Int): Int
    fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean
    fun readFully(target: ByteArray, offset: Int, length: Int)
    fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean
    fun skipFully(length: Int)
    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean
    fun peekFully(target: ByteArray, offset: Int, length: Int)
    fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean
    fun advancePeekPosition(length: Int)
    fun resetPeekPosition()
    fun getPeekPosition(): Long
    fun getPosition(): Long
    fun getLength(): Long
}

class PositionHolder {
    var position: Long = 0
}

interface ExtractorOutput {
    fun track(id: Int, type: Int): TrackOutput
    fun endTracks()
    fun seekMap(seekMap: SeekMap)

    companion object {
        val PLACEHOLDER: ExtractorOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = throw UnsupportedOperationException()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
    }
}

interface TrackOutput {
    fun format(format: Format)
    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int
    fun sampleData(data: ParsableByteArray, length: Int)
    fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    )

    class CryptoData(val cryptoMode: Int, val encryptionKey: ByteArray, val encryptedBlocks: Int, val clearBlocks: Int)
}

interface SeekMap {
    fun isSeekable(): Boolean
    fun getDurationUs(): Long
    fun getSeekPoints(timeUs: Long): SeekPoints

    class SeekPoints(val first: SeekPoint, val second: SeekPoint) {
        constructor(point: SeekPoint) : this(point, point)
    }

    class Unseekable(private val durationUs: Long) : SeekMap {
        override fun isSeekable(): Boolean = false
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekPoints =
            SeekPoints(SeekPoint(0, 0), SeekPoint(0, 0))
    }
}

class SeekPoint(val timeUs: Long, val position: Long)

interface ExtractorsFactory {
    fun createExtractors(): Array<Extractor>
    fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        createExtractors()
}

/** `androidx.media3.exoplayer.source`: lo mínimo para el enrutado por pista. */
