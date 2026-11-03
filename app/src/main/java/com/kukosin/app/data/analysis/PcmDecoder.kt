package com.kukosin.app.data.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

data class PcmCapture(
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncodingBits: Int,
    val monoWindows: List<FloatArray>,
    val lsbPaddingRatio: Float?,
    val lsbAvgValue: Float?,
)

internal object PcmDecoder {
    private const val WINDOW = 4096
    private const val WINDOWS_PER_POINT = 8
    private const val TIMEOUT_US = 10_000L

    fun capture(context: Context, uri: Uri, durationMs: Long): PcmCapture? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            val seekPoints = when {
                durationMs > 30_000 -> listOf(
                    (durationMs * 1000L * 0.22).toLong(),
                    (durationMs * 1000L * 0.50).toLong(),
                    (durationMs * 1000L * 0.75).toLong(),
                )
                durationMs > 10_000 -> listOf(
                    (durationMs * 1000L * 0.30).toLong(),
                    (durationMs * 1000L * 0.65).toLong(),
                )
                else -> listOf(0L)
            }

            val allWindows = ArrayList<FloatArray>()
            var bits = 16
            var zeroLsbCount = 0
            var lsbSum = 0L
            var sampleCount = 0

            for (startUs in seekPoints) {
                if (allWindows.size >= 24) break
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val segmentSamples = ArrayList<Float>(WINDOW * WINDOWS_PER_POINT + WINDOW)
                var inputDone = false
                var outputDone = false
                val deadline = System.nanoTime() + 1_500_000_000L

                while (!outputDone && segmentSamples.size < WINDOW * WINDOWS_PER_POINT && System.nanoTime() < deadline) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: break
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                bits = when (out.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                    AudioFormat.ENCODING_PCM_32BIT -> 32
                                    else -> 16
                                }
                            }
                        }
                        outIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.order(ByteOrder.nativeOrder())
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                when (bits) {
                                    32 -> {
                                        while (buffer.remaining() >= 4 * channels && segmentSamples.size < WINDOW * WINDOWS_PER_POINT) {
                                            val sample = buffer.float
                                            segmentSamples += sample
                                            repeat(channels - 1) { if (buffer.remaining() >= 4) buffer.float }
                                        }
                                    }
                                    24 -> {
                                        while (buffer.remaining() >= 3 * channels && segmentSamples.size < WINDOW * WINDOWS_PER_POINT) {
                                            val b0 = buffer.get().toInt() and 0xFF
                                            val b1 = buffer.get().toInt() and 0xFF
                                            val b2 = buffer.get().toInt()
                                            if (b0 == 0) zeroLsbCount++
                                            lsbSum += b0
                                            sampleCount++
                                            var value = (b2 shl 16) or (b1 shl 8) or b0
                                            if (value and 0x800000 != 0) value = value or -0x1000000
                                            segmentSamples += (value / 8388608f)
                                            repeat(channels - 1) { if (buffer.remaining() >= 3) { buffer.get(); buffer.get(); buffer.get() } }
                                        }
                                    }
                                    else -> {
                                        while (buffer.remaining() >= 2 * channels && segmentSamples.size < WINDOW * WINDOWS_PER_POINT) {
                                            val shortVal = buffer.short
                                            val lsb = shortVal.toInt() and 0xFF
                                            if (lsb == 0) zeroLsbCount++
                                            lsbSum += lsb
                                            sampleCount++
                                            segmentSamples += shortVal / 32768f
                                            repeat(channels - 1) { if (buffer.remaining() >= 2) buffer.short }
                                        }
                                    }
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
                codec.stop()
                codec.release()

                var offset = 0
                while (offset + WINDOW <= segmentSamples.size) {
                    allWindows += segmentSamples.subList(offset, offset + WINDOW).toFloatArray()
                    offset += WINDOW / 2
                }
            }

            if (allWindows.isEmpty()) return null
            val padding = if (bits >= 24 && sampleCount > 0) zeroLsbCount.toFloat() / sampleCount else null
            val avgLsb = if (sampleCount > 0) lsbSum.toFloat() / sampleCount else null
            PcmCapture(sampleRate, channels, bits, allWindows, padding, avgLsb)
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}

private operator fun ArrayList<Float>.plusAssign(value: Float) {
    add(value)
}
