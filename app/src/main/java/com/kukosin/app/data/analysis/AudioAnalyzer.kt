package com.kukosin.app.data.analysis

import android.content.Context
import android.net.Uri
import com.kukosin.app.data.Track
import com.kukosin.app.data.bitDepthFromSampleFormat
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AuthenticityVerdict {
    AUTHENTIC_HIRES,
    AUTHENTIC_CD,
    AUTHENTIC_LOSSY,
    UPSAMPLED,
    LOSSY_TRANSCODE,
    INCONCLUSIVE,
}

data class AudioAnalysis(
    val uri: Uri,
    val isAnalyzing: Boolean = false,
    val error: String? = null,
    val lyrics: EmbeddedLyrics? = null,
    val tags: List<Pair<String, String>> = emptyList(),
    val spectrumDb: List<Float> = emptyList(),
    val spectrumHz: List<Float> = emptyList(),
    val cutoffHz: Int = 0,
    val nyquistHz: Int = 0,
    val peakDb: Float = 0f,
    val rmsDb: Float = 0f,
    val dynamicRangeDb: Float = 0f,
    val hfEnergyRatio: Float = 0f,
    val lsbPaddingRatio: Float? = null,
    val verdict: AuthenticityVerdict = AuthenticityVerdict.INCONCLUSIVE,
    val verdictTitle: String = "",
    val verdictDetail: String = "",
    val technical: List<Pair<String, String>> = emptyList(),
)

object AudioAnalyzer {

    /**
     * Cantidad de barras del espectro. Antes eran 96: con 288 se distingue el corte
     * de un MP3 transcodificado sin que el gráfico se vuelva una mancha.
     */
    const val SPECTRUM_BANDS = 288

    fun extractMetadataOnly(context: Context, track: Track): AudioAnalysis {
        val tags = LinkedHashMap<String, String>()
        val snapshot = runCatching { EmbeddedMetadata.extract(context, track.uri, track.filePath) }.getOrNull()
        snapshot?.fields?.forEach { tags[it.first] = it.second }

        val mediaInfo = runCatching { MediaInfoBuilder().from(context, track.uri).build() }.getOrNull()
        val audio = mediaInfo?.audioStreams?.firstOrNull()
        if (mediaInfo != null) {
            tags["Container"] = mediaInfo.format.ifBlank { track.mimeType }
            tags["FFmpeg duration"] = "${mediaInfo.duration} ms"
            if (mediaInfo.chapters.isNotEmpty()) tags["Chapters"] = mediaInfo.chapters.size.toString()
        }
        if (audio != null) {
            tags["Codec FFmpeg"] = audio.codecName
            tags["Sample format FFmpeg"] = audio.sampleFormat.orEmpty()
            tags["Sample rate FFmpeg"] = "${audio.sampleRate} Hz"
            tags["FFmpeg channels"] = audio.channels.toString()
            if (audio.bitRate > 0) tags["Bitrate FFmpeg"] = "${audio.bitRate / 1000} kbps (${audio.bitRate} bps)"
        }

        val sampleRate = when {
            audio != null && audio.sampleRate > 0 -> audio.sampleRate
            track.sampleRate > 0 -> track.sampleRate
            else -> 0
        }
        val bitDepth = bitDepthFromSampleFormat(audio?.sampleFormat).takeIf { it > 0 } ?: track.bitDepth
        val channels = (audio?.channels ?: track.channels).coerceAtLeast(0)
        val bitrate = (audio?.bitRate?.toInt()?.takeIf { it > 0 } ?: track.bitrate)
        val losslessUncompressed = if (sampleRate > 0 && bitDepth > 0 && channels > 0) {
            sampleRate.toLong() * bitDepth * channels
        } else 0L
        if (losslessUncompressed > 0) {
            tags["Uncompressed rate (PCM)"] = "${losslessUncompressed / 1000} kbps"
            if (bitrate > 0) {
                val ratio = losslessUncompressed.toDouble() / bitrate
                val compressionPct = ((1.0 - (1.0 / ratio)) * 100.0).coerceIn(0.0, 99.0)
                tags["Compression ratio"] = "%.2f:1 (%.1f%% compressed)".format(ratio, compressionPct)
            }
        }
        tags["File size"] = track.fileSizeLabel
        tags["Full path"] = track.filePath.ifBlank { track.uri.toString() }
        tags["Declared Hi-Res"] = if (sampleRate >= 88200 || bitDepth > 16) "Yes" else "No"

        return AudioAnalysis(
            uri = track.uri,
            lyrics = snapshot?.lyrics,
            tags = tags.toList(),
            nyquistHz = if (sampleRate > 0) sampleRate / 2 else 0,
            verdict = AuthenticityVerdict.INCONCLUSIVE,
            verdictTitle = "Authenticity check pending",
            verdictDetail = "Press 'Verify Authenticity and Spectrum' to analyze PCM samples, measure the real cutoff frequency and validate whether the file is genuine Hi-Res or a transcode.",
            technical = tags.toList(),
        )
    }

    suspend fun analyze(context: Context, track: Track): AudioAnalysis {
        val tags = LinkedHashMap<String, String>()
        val snapshot = runCatching { EmbeddedMetadata.extract(context, track.uri, track.filePath) }.getOrNull()
        snapshot?.fields?.forEach { tags[it.first] = it.second }

        val mediaInfo = runCatching { MediaInfoBuilder().from(context, track.uri).build() }.getOrNull()
        val audio = mediaInfo?.audioStreams?.firstOrNull()
        if (mediaInfo != null) {
            tags["Container"] = mediaInfo.format.ifBlank { track.mimeType }
            tags["FFmpeg duration"] = "${mediaInfo.duration} ms"
            if (mediaInfo.chapters.isNotEmpty()) tags["Chapters"] = mediaInfo.chapters.size.toString()
        }
        if (audio != null) {
            tags["Codec FFmpeg"] = audio.codecName
            tags["Sample format FFmpeg"] = audio.sampleFormat.orEmpty()
            tags["Sample rate FFmpeg"] = "${audio.sampleRate} Hz"
            tags["FFmpeg channels"] = audio.channels.toString()
            if (audio.bitRate > 0) tags["Bitrate FFmpeg"] = "${audio.bitRate / 1000} kbps (${audio.bitRate} bps)"
            if (!audio.language.isNullOrBlank()) tags["Track language"] = audio.language.orEmpty()
            if (!audio.title.isNullOrBlank()) tags["Stream title"] = audio.title.orEmpty()
        }

        val sampleRate = when {
            audio != null && audio.sampleRate > 0 -> audio.sampleRate
            track.sampleRate > 0 -> track.sampleRate
            else -> 0
        }
        val bitDepth = bitDepthFromSampleFormat(audio?.sampleFormat).takeIf { it > 0 } ?: track.bitDepth
        val channels = (audio?.channels ?: track.channels).coerceAtLeast(0)
        val bitrate = (audio?.bitRate?.toInt()?.takeIf { it > 0 } ?: track.bitrate)
        val losslessUncompressed = if (sampleRate > 0 && bitDepth > 0 && channels > 0) {
            sampleRate.toLong() * bitDepth * channels
        } else 0L
        if (losslessUncompressed > 0) {
            tags["Uncompressed rate (PCM)"] = "${losslessUncompressed / 1000} kbps"
            if (bitrate > 0) {
                val ratio = losslessUncompressed.toDouble() / bitrate
                val compressionPct = ((1.0 - (1.0 / ratio)) * 100.0).coerceIn(0.0, 99.0)
                tags["Compression ratio"] = "%.2f:1 (%.1f%% compressed)".format(ratio, compressionPct)
            }
        }
        tags["File size"] = track.fileSizeLabel
        tags["Full path"] = track.filePath.ifBlank { track.uri.toString() }
        tags["Declared Hi-Res"] = if (sampleRate >= 88200 || bitDepth > 16) "Yes" else "No"

        val capture = runCatching { PcmDecoder.capture(context, track.uri, track.durationMs) }.getOrNull()
        if (capture == null) {
            return AudioAnalysis(
                uri = track.uri,
                lyrics = snapshot?.lyrics,
                tags = tags.toList(),
                nyquistHz = if (sampleRate > 0) sampleRate / 2 else 0,
                verdict = AuthenticityVerdict.INCONCLUSIVE,
                verdictTitle = "No PCM signal captured",
                verdictDetail = "File metadata was read, but the decoder delivered no PCM samples for spectral analysis.",
                technical = tags.toList(),
            )
        }

        val usedRate = capture.sampleRate.takeIf { it > 0 } ?: sampleRate
        val nyquist = usedRate / 2
        val (spectrumDb, cutoff, slope, hfRatio, peakDb, rmsDb) = measureSpectrum(capture, usedRate)
        val bars = downsample(spectrumDb, SPECTRUM_BANDS, usedRate)
        val dr = (peakDb - rmsDb).coerceAtLeast(0f)
        val padding = capture.lsbPaddingRatio
        val (verdict, title, detail) = classify(
            codec = track.codecLabel,
            sampleRate = usedRate,
            bitDepth = bitDepth,
            cutoffHz = cutoff,
            slope = slope,
            nyquistHz = nyquist,
            hfRatio = hfRatio,
            padding = padding,
            bitrate = bitrate,
        )

        val drRating = when {
            dr >= 14f -> "Excellent (DR${dr.roundToInt()})"
            dr >= 10f -> "Good (DR${dr.roundToInt()})"
            dr >= 7f -> "Compressed (DR${dr.roundToInt()})"
            else -> "Loudness War / Very compressed (DR${dr.roundToInt()})"
        }

        val technical = buildList {
            add("Final verdict" to title)
            add("Technical diagnosis" to detail)
            add("Sample rate (PCM)" to "$usedRate Hz")
            add("Nyquist frequency" to "$nyquist Hz")
            add("Measured spectral cutoff" to "$cutoff Hz (${"%.1f".format(cutoff / 1000.0)} kHz)")
            add("Roll-off slope" to "%.1f dB/kHz (${if (slope > 20f) "Lossy brickwall filter" else "Gentle acoustic roll-off"})".format(slope))
            add("Bandwidth usage" to "${"%.1f".format((cutoff.toFloat() / nyquist.coerceAtLeast(1)) * 100)} % of Nyquist")
            add("Energy in harmonics >20 kHz" to if (hfRatio > 0.0001f) "%.4f %% (active ultrasonic harmonics)".format(hfRatio * 100) else "None / filtered")
            add("True Peak volume" to "%.1f dBFS".format(peakDb))
            add("RMS volume" to "%.1f dBFS".format(rmsDb))
            add("Dynamic Range (DR)" to "%.1f dB — $drRating".format(dr))
            add("PCM decoding bits" to "${capture.pcmEncodingBits}-bit")
            if (padding != null && bitDepth >= 24) {
                val paddingPct = padding * 100f
                val paddedText = if (paddingPct > 88f) "FALSE 24-BIT (%.1f%% zero LSBs)".format(paddingPct) else "True 24-bit (%.1f%% zero LSBs)".format(paddingPct)
                add("24-bit LSB check" to paddedText)
            }
            add("Multi-point sampling" to "${capture.monoWindows.size} FFT windows of 4096 samples")
            addAll(tags.toList())
        }

        return AudioAnalysis(
            uri = track.uri,
            lyrics = snapshot?.lyrics,
            tags = tags.toList(),
            spectrumDb = bars.first,
            spectrumHz = bars.second,
            cutoffHz = cutoff,
            nyquistHz = nyquist,
            peakDb = peakDb,
            rmsDb = rmsDb,
            dynamicRangeDb = dr,
            hfEnergyRatio = hfRatio,
            lsbPaddingRatio = padding,
            verdict = verdict,
            verdictTitle = title,
            verdictDetail = detail,
            technical = technical,
        )
    }

    private fun measureSpectrum(
        capture: PcmCapture,
        sampleRate: Int,
    ): PreciseStats {
        val n = 4096
        val mag = DoubleArray(n / 2)
        var peak = 1e-9
        var sumSq = 0.0
        var count = 0
        capture.monoWindows.forEach { window ->
            if (window.size < n) return@forEach
            val real = DoubleArray(n)
            val imag = DoubleArray(n)
            for (i in 0 until n) {
                val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
                val sample = window[i].toDouble()
                real[i] = sample * hann
                peak = max(peak, abs(sample))
                sumSq += sample * sample
                count++
            }
            Fft.transform(real, imag)
            for (i in mag.indices) {
                mag[i] += sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
        }
        val frames = capture.monoWindows.size.coerceAtLeast(1)
        val db = List(mag.size) { index ->
            val value = (mag[index] / frames).coerceAtLeast(1e-12)
            (20.0 * log10(value)).toFloat()
        }
        val binHz = sampleRate.toFloat() / n
        val (cutoffBin, cutoff) = detectCutoffWithBin(db, binHz, sampleRate / 2)
        val slope = calculateRollOffSlope(db, binHz, cutoffBin)

        val hz20 = (20000f / binHz).toInt().coerceIn(0, db.lastIndex)
        val energyAll = db.sumOf { 10.0.pow(it / 10.0) }
        val energyHf = db.drop(hz20).sumOf { 10.0.pow(it / 10.0) }
        val rms = if (count > 0) sqrt(sumSq / count) else 1e-9
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-9))).toFloat()
        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-9))).toFloat()
        return PreciseStats(db.toList(), cutoff, slope, (energyHf / energyAll.coerceAtLeast(1e-12)).toFloat(), peakDb, rmsDb)
    }

    private fun detectCutoffWithBin(db: List<Float>, binHz: Float, nyquist: Int): Pair<Int, Int> {
        if (db.isEmpty() || binHz <= 0f) return 0 to 0
        val from = (2000f / binHz).toInt().coerceIn(0, db.lastIndex)
        val to = (10000f / binHz).toInt().coerceIn(from + 1, db.lastIndex)
        val floorRef = if (to > from) db.subList(from, to).average().toFloat() else -40f
        val threshold = (floorRef - 34f).coerceAtMost(-68f)
        var lastAliveBin = from
        val windowBins = (400f / binHz).toInt().coerceAtLeast(4)
        var i = from
        while (i < db.size) {
            val hz = i * binHz
            if (hz >= nyquist) break
            val end = (i + windowBins).coerceAtMost(db.size)
            val avg = db.subList(i, end).average().toFloat()
            if (avg > threshold) {
                lastAliveBin = i + windowBins / 2
            }
            i += windowBins / 2
        }
        val cutoffHz = (lastAliveBin * binHz).toInt().coerceIn(0, nyquist)
        return lastAliveBin to cutoffHz
    }

    private fun calculateRollOffSlope(db: List<Float>, binHz: Float, cutoffBin: Int): Float {
        if (cutoffBin <= 0 || cutoffBin >= db.size - 4 || binHz <= 0f) return 0f
        val spanBins = (1500f / binHz).toInt().coerceAtLeast(4)
        val endBin = (cutoffBin + spanBins).coerceAtMost(db.lastIndex)
        val dropDb = db[cutoffBin] - db[endBin]
        val distanceKhz = (endBin - cutoffBin) * binHz / 1000f
        return if (distanceKhz > 0f) (dropDb / distanceKhz).coerceAtLeast(0f) else 0f
    }

    /**
     * Reduce el espectro lineal de la FFT a [bars] bandas espaciadas logarítmicamente
     * entre 20 Hz y Nyquist. Es lo que hace que el gráfico se lea como un espectro
     * real: los graves ocupan su espacio y el corte del codec se ve nítido.
     */
    private fun downsample(db: List<Float>, bars: Int, sampleRate: Int): Pair<List<Float>, List<Float>> {
        if (db.isEmpty()) return emptyList<Float>() to emptyList()
        val nyquist = sampleRate / 2f
        val minHz = 20f
        if (nyquist <= minHz * 2f) return emptyList<Float>() to emptyList()
        val binHz = sampleRate.toFloat() / 4096f
        val logLo = log10(minHz)
        val logHi = log10(nyquist)
        val values = ArrayList<Float>(bars)
        val hz = ArrayList<Float>(bars)
        for (i in 0 until bars) {
            val f0 = 10f.pow(logLo + (logHi - logLo) * i / bars)
            val f1 = 10f.pow(logLo + (logHi - logLo) * (i + 1) / bars)
            val start = (f0 / binHz).toInt().coerceIn(0, db.lastIndex)
            val end = (f1 / binHz).toInt().coerceIn(start + 1, db.size)
            values += db.subList(start, end).max()
            // Centro geométrico de la banda.
            hz += kotlin.math.sqrt(f0 * f1)
        }
        return values to hz
    }

    private fun classify(
        codec: String,
        sampleRate: Int,
        bitDepth: Int,
        cutoffHz: Int,
        slope: Float,
        nyquistHz: Int,
        hfRatio: Float,
        padding: Float?,
        bitrate: Int,
    ): Triple<AuthenticityVerdict, String, String> {
        val lossyCodec = codec in listOf("MP3", "AAC", "OGG", "Opus", "AC3", "E-AC3")
        val padded = padding != null && padding > 0.88f && bitDepth >= 24

        return when {
            padded -> Triple(
                AuthenticityVerdict.UPSAMPLED,
                "Fake 24-bit (LSB zero padding)",
                "${(padding * 100).roundToInt()}% of the least significant bits (LSB) are zero. It is a 16-bit file packed inside a $bitDepth-bit container.",
            )
            !lossyCodec && cutoffHz < 19_800 && slope > 18f -> Triple(
                AuthenticityVerdict.LOSSY_TRANSCODE,
                "Fake Lossless (MP3/AAC → $codec transcode)",
                "Claims $codec $bitDepth-bit, but has a sharp cutoff at ${cutoffHz} Hz (${"%.1f".format(cutoffHz / 1000.0)} kHz) with a brickwall slope of ${"%.1f".format(slope)} dB/kHz, the unmistakable signature of lossy audio re-encoded.",
            )
            sampleRate >= 88_200 && cutoffHz < 24_000 -> Triple(
                AuthenticityVerdict.UPSAMPLED,
                "Fake Hi-Res (Upsampled / Resampled)",
                "Claims a ${sampleRate / 1000} kHz sample rate (Nyquist ${nyquistHz / 1000} kHz), but the signal drops at ${cutoffHz} Hz (${"%.1f".format(cutoffHz / 1000.0)} kHz). This is CD/48k material resampled to high resolution.",
            )
            sampleRate >= 88_200 && (cutoffHz >= 28_000 || hfRatio > 0.0003f) -> Triple(
                AuthenticityVerdict.AUTHENTIC_HIRES,
                "100% Authentic Hi-Res",
                "Continuous analog spectrum confirmed with active ultrasonic harmonics up to ${cutoffHz} Hz (${"%.1f".format(cutoffHz / 1000.0)} kHz) and real bit depth.",
            )
            sampleRate in 44_000..48_100 && cutoffHz >= 19_000 -> Triple(
                AuthenticityVerdict.AUTHENTIC_CD,
                "Authentic CD / Lossless Quality",
                "Bandwidth reaches ${cutoffHz} Hz, matching the $nyquistHz Hz Nyquist limit for 44.1/48 kHz lossless audio.",
            )
            lossyCodec -> Triple(
                AuthenticityVerdict.AUTHENTIC_LOSSY,
                "Authentic Compressed Audio ($codec)",
                "Spectral cutoff at ${cutoffHz} Hz with the typical $codec slope at ${if (bitrate > 0) "${bitrate / 1000} kbps" else "variable rate"}.",
            )
            else -> Triple(
                AuthenticityVerdict.INCONCLUSIVE,
                "Inconclusive Spectral Analysis",
                "Spectral cutoff at ${cutoffHz} Hz versus a Nyquist of $nyquistHz Hz. It may be a historical recording or a studio low-pass master.",
            )
        }
    }

    private data class PreciseStats(
        val db: List<Float>,
        val cutoff: Int,
        val slope: Float,
        val hfRatio: Float,
        val peakDb: Float,
        val rmsDb: Float,
    )
}
