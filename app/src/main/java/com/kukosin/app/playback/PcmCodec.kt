package com.kukosin.app.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@UnstableApi
internal object PcmCodec {

    fun isSupported(encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    fun decode(
        src: ByteArray,
        srcOffset: Int,
        dst: DoubleArray,
        dstOffset: Int,
        count: Int,
        encoding: Int,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var p = srcOffset
                var d = dstOffset
                repeat(count) {
                    val lo = src[p].toInt() and 0xFF
                    val hi = src[p + 1].toInt()
                    val raw = (hi shl 8) or lo
                    dst[d] = raw.toShort().toDouble() / 32768.0
                    p += 2
                    d++
                }
            }

            C.ENCODING_PCM_24BIT -> {
                var p = srcOffset
                var d = dstOffset
                repeat(count) {
                    val b0 = src[p].toInt() and 0xFF
                    val b1 = src[p + 1].toInt() and 0xFF
                    val b2 = src[p + 2].toInt()
                    val raw = (b2 shl 16) or (b1 shl 8) or b0
                    dst[d] = raw.toDouble() / 8388608.0
                    p += 3
                    d++
                }
            }

            C.ENCODING_PCM_32BIT -> {
                var p = srcOffset
                var d = dstOffset
                repeat(count) {
                    val b0 = src[p].toInt() and 0xFF
                    val b1 = src[p + 1].toInt() and 0xFF
                    val b2 = src[p + 2].toInt() and 0xFF
                    val b3 = src[p + 3].toInt()
                    val raw = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    dst[d] = raw.toDouble() / 2147483648.0
                    p += 4
                    d++
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                var p = srcOffset
                var d = dstOffset
                repeat(count) {
                    val b0 = src[p].toInt() and 0xFF
                    val b1 = src[p + 1].toInt() and 0xFF
                    val b2 = src[p + 2].toInt() and 0xFF
                    val b3 = src[p + 3].toInt() and 0xFF
                    val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    dst[d] = Float.fromBits(bits).toDouble()
                    p += 4
                    d++
                }
            }
        }
    }

    fun encode(
        src: DoubleArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int,
        count: Int,
        encoding: Int,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var s = srcOffset
                var p = dstOffset
                repeat(count) {
                    val v = quantize16(src[s])
                    dst[p] = (v and 0xFF).toByte()
                    dst[p + 1] = ((v shr 8) and 0xFF).toByte()
                    s++
                    p += 2
                }
            }

            C.ENCODING_PCM_24BIT -> {
                var s = srcOffset
                var p = dstOffset
                repeat(count) {
                    val v = quantize24(src[s])
                    dst[p] = (v and 0xFF).toByte()
                    dst[p + 1] = ((v shr 8) and 0xFF).toByte()
                    dst[p + 2] = ((v shr 16) and 0xFF).toByte()
                    s++
                    p += 3
                }
            }

            C.ENCODING_PCM_32BIT -> {
                var s = srcOffset
                var p = dstOffset
                repeat(count) {
                    val v = quantize32(src[s])
                    dst[p] = (v and 0xFF).toByte()
                    dst[p + 1] = ((v shr 8) and 0xFF).toByte()
                    dst[p + 2] = ((v shr 16) and 0xFF).toByte()
                    dst[p + 3] = ((v shr 24) and 0xFF).toByte()
                    s++
                    p += 4
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                var s = srcOffset
                var p = dstOffset
                repeat(count) {
                    val v = src[s].toFloat().coerceIn(-1f, 1f).toRawBits()
                    dst[p] = (v and 0xFF).toByte()
                    dst[p + 1] = ((v shr 8) and 0xFF).toByte()
                    dst[p + 2] = ((v shr 16) and 0xFF).toByte()
                    dst[p + 3] = ((v shr 24) and 0xFF).toByte()
                    s++
                    p += 4
                }
            }
        }
    }

    private fun quantize16(x: Double): Int {
        if (abs(x) >= 1.0) return if (x > 0.0) 32767 else -32768
        return (x * 32768.0).roundToInt().coerceIn(-32768, 32767)
    }

    private fun quantize24(x: Double): Int {
        if (abs(x) >= 1.0) return if (x > 0.0) 8388607 else -8388608
        return (x * 8388608.0).roundToInt().coerceIn(-8388608, 8388607)
    }

    private fun quantize32(x: Double): Int {
        if (abs(x) >= 1.0) return if (x > 0.0) Int.MAX_VALUE else Int.MIN_VALUE
        return (x * 2147483648.0).roundToLong()
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }
}
