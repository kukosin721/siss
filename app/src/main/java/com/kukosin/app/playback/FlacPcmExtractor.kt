package com.kukosin.app.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.io.IOException

/**
 * Extractor de FLAC que **decodifica** con el decodificador propio
 * ([FlacDecoderBridge] → `app/src/main/cpp/audio/FlacDecoder.cpp`).
 *
 * # Por qué decodificar en el extractor
 *
 * Media3 ya trae un extractor de FLAC, pero no decodifica: separa frames y los entrega al
 * decoder del sistema (`MediaCodec`) o a FFmpeg. Para 24 bits ninguno de los dos sirve:
 * `c2.android.flac.decoder` emite float (o recorta a 16 bits) y el camino float de Media3
 * multiplica por 1/0x7FFFFFFF. Resultado: la muestra de 24 bits deja de ser bit-perfect
 * antes de llegar al motor. Acá lo que sale del extractor ya es **PCM entero en el ancho
 * nativo** (`audio/raw` con `pcmEncoding` de 16/24/32 bits), así que el resto de la cadena
 * (AudioSink de Media3 → [EqAudioProcessor] → [com.kukosin.app.audio.OboeAudioOutput])
 * lo trata como cualquier PCM y `AudioEngine` escribe esas mismas muestras al DAC.
 *
 * # Decisiones
 *
 * - **Un bloque de FLAC = un sample de Media3**, así el seek por `sampleTime` cae siempre al
 *   inicio de un bloque.
 * - **Los bytes se leen del `FileDescriptor` del archivo, no del buffer de Media3.** El
 *   decodificador necesita acceso aleatorio (metadatos, índice de frames). Para que las dos
 *   vías no se peleen, la posición del `DataSource` se mantiene sincronizada saltando
 *   exactamente lo que ocupó el bloque en el archivo: si el reproductor reabre el stream, las
 *   dos posiciones coinciden y se detecta de inmediato.
 * - **SEEKTABLE primero; si no hay, índice de frames** armado recorriendo sólo cabeceras.
 */
@UnstableApi
class FlacPcmExtractor(
    private val appContext: android.content.Context,
    private val sourceUri: android.net.Uri,
) : Extractor {

    private class SeekEntry(val byteOffset: Long, val sampleNumber: Long, val frameSamples: Int)

    private class DecodedFrame(val frames: Int)

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var decoder: FlacDecoderBridge? = null
    private var format: FlacDecoderBridge.StreamFormat? = null
    private var streamInfo: IntArray? = null

    private var fileDescriptor = -1
    private var fileLength = -1L

    private var outputStarted = false
    private var finished = false

    /** Timestamp del próximo bloque (µs) y número de sample del próximo frame decodificado. */
    private var nextSampleTimeUs = 0L
    private var nextFrameSample = 0L

    /** Byte del archivo donde empieza el próximo frame que va a entregar el decodificador. */
    private var decoderPosition = 0L

    private var buffer: ByteArray? = null
    private var sampleData: ParsableByteArray? = null
    private var blockBytes = 0L
    /** Muestras a descartar al inicio del próximo bloque (seek que cayó dentro de un frame). */
    private var pendingSkipSamples = 0

    private val seekEntries = ArrayList<SeekEntry>()

    // ---------------------------------------------------------------- ciclo de vida

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    /** Se elige por MIME/URI ([LocalFlacMediaSourceFactory]); si se prueba, mira la firma. */
    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(4)
        return try {
            input.peekFully(header, 0, 4)
            String(header, Charsets.US_ASCII) == "fLaC"
        } catch (eof: java.io.EOFException) {
            false
        }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (finished) return Extractor.RESULT_END_OF_INPUT
        val streamFormat = format ?: openSource(input) ?: return Extractor.RESULT_END_OF_INPUT
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        // Si el reproductor reabrió el stream en otro punto (seek), la posición que informa el
        // `DataSource` no coincide con la del decodificador: se reabre ahí reutilizando el
        // STREAMINFO ya leído y se sigue, sin volver a leer metadatos.
        val reportedPosition = input.getPosition()
        if (reportedPosition != decoderPosition && !reopenAt(reportedPosition)) {
            finished = true
            return Extractor.RESULT_END_OF_INPUT
        }

        val pcm = buffer ?: ByteArray(MAX_BLOCK_FRAMES * streamFormat.frameBytes).also { buffer = it }
        val frame = decodeNextBlock(pcm) ?: run {
            finished = true
            return Extractor.RESULT_END_OF_INPUT
        }

        if (!outputStarted) {
            track.format(buildFormat(streamFormat))
            extractorOutput?.seekMap(buildSeekMap(streamFormat))
            extractorOutput?.endTracks()
            outputStarted = true
        }

        // El bloque ya está decodificado: se avanza el DataSource exactamente los mismos bytes
        // para que su posición quede igual a la del decodificador (y un reopen sea detectable).
        skipBlockBytes(input)

        val bytes = frame.frames * streamFormat.frameBytes
        val container = sampleData ?: ParsableByteArray(pcm).also { sampleData = it }
        container.setPosition(0)
        track.sampleData(container, bytes)
        track.sampleMetadata(nextSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null)

        nextSampleTimeUs += frame.frames * 1_000_000L / streamFormat.sampleRate
        nextFrameSample += frame.frames
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // `position` es el byte del frame al que Media3 quiere ir (viene de nuestro SeekMap);
        // `timeUs` es el tiempo de reproducción pedido, que puede caer en medio de un bloque.
        if (format == null || position < 0) return
        val sampleRate = format?.sampleRate ?: 0
        val entry = seekEntries.lastOrNull { it.byteOffset <= position }
        val entrySample = entry?.sampleNumber ?: 0L
        // Se reabre en el byte indicado; el decodificador sabe qué sample es gracias al índice.
        if (!reopenAt(position)) return
        decoderPosition = position
        if (timeUs != C.TIME_UNSET && timeUs > 0 && sampleRate > 0) {
            val requestedSample = timeUs * sampleRate / 1_000_000L
            // Si el tiempo pedido cae dentro del bloque, se marca para descartar el inicio.
            pendingSkipSamples = if (requestedSample > entrySample) {
                (requestedSample - entrySample).toInt()
            } else {
                0
            }
            nextSampleTimeUs = timeUs
            nextFrameSample = requestedSample
        } else {
            pendingSkipSamples = 0
            nextSampleTimeUs = if (sampleRate > 0) entrySample * 1_000_000L / sampleRate else 0L
            nextFrameSample = entrySample
        }
    }

    override fun release() {
        decoder?.close()
        decoder = null
        if (fileDescriptor >= 0) {
            try {
                android.os.ParcelFileDescriptor.adoptFd(fileDescriptor).close()
            } catch (ignored: Exception) {
                // ya estaba cerrado
            }
            fileDescriptor = -1
        }
        buffer = null
        sampleData = null
        seekEntries.clear()
        outputStarted = false
        finished = true
    }

    // ---------------------------------------------------------------- apertura

    private fun openSource(input: ExtractorInput): FlacDecoderBridge.StreamFormat? {
        val fd = try {
            when (sourceUri.scheme) {
                null, "file" -> {
                    val path = sourceUri.path ?: return null
                    android.os.ParcelFileDescriptor.open(
                        File(path),
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    ).detachFd()
                }

                "content" -> {
                    val descriptor = appContext.contentResolver.openFileDescriptor(sourceUri, "r")
                        ?: return null
                    descriptor.detachFd()
                }

                else -> return null
            }
        } catch (io: IOException) {
            return null
        }
        fileDescriptor = fd
        val length = input.getLength()
        fileLength = if (length != C.LENGTH_UNSET.toLong()) length else -1L

        val opened = FlacDecoderBridge.open(fd, fileLength) ?: return null
        decoder = opened
        val streamFormat = opened.format() ?: return null
        format = streamFormat
        streamInfo = opened.streamInfo()
        decoderPosition = streamFormat.audioOffset
        nextFrameSample = 0L
        nextSampleTimeUs = 0L
        pendingSkipSamples = 0
        loadSeekEntries()
        return streamFormat
    }

    /**
     * Reabre el decodificador en `frameOffset`. Si `input` no es null se reposiciona también
     * el `DataSource` (no puede retroceder por sí solo: hay que pedirle a Media3 que reabra
     * el stream devolviendo [Extractor.RESULT_SEEK] desde `read`, por eso acá sólo se acepta
     * la misma posición que informa el input).
     */
    private fun reopenAt(frameOffset: Long): Boolean {
        val fd = fileDescriptor
        val cached = streamInfo
        if (fd < 0 || cached == null || frameOffset < 0) return false
        val entry = seekEntries.lastOrNull { it.byteOffset <= frameOffset }
        val reopened = FlacDecoderBridge.openAtFrame(
            fd,
            fileLength,
            cached,
            frameOffset,
            entry?.sampleNumber ?: 0L,
        ) ?: return false
        decoder?.close()
        decoder = reopened
        decoderPosition = frameOffset
        return true
    }

    /** SEEKTABLE del archivo o, si no trae, índice de frames armado sin decodificar. */
    private fun loadSeekEntries() {
        seekEntries.clear()
        val fromTable = decoder?.seekTable().orEmpty()
        if (fromTable.isNotEmpty()) {
            for (entry in fromTable) {
                seekEntries.add(SeekEntry(entry[1], entry[0], entry[2].toInt()))
            }
            return
        }
        val fd = fileDescriptor
        val cached = streamInfo
        if (fd < 0 || cached == null) return
        val offsets = LongArray(MAX_INDEXED_FRAMES)
        val samples = LongArray(MAX_INDEXED_FRAMES)
        val frameSamples = IntArray(MAX_INDEXED_FRAMES)
        val count = FlacDecoderBridge.buildFrameIndex(fd, fileLength, cached, offsets, samples, frameSamples)
        for (index in 0 until maxOf(0, count)) {
            seekEntries.add(SeekEntry(offsets[index], samples[index], frameSamples[index]))
        }
    }

    // ---------------------------------------------------------------- decodificación

    private fun decodeNextBlock(pcm: ByteArray): DecodedFrame? {
        val streamFormat = format ?: return null
        val decoder = decoder ?: return null
        var frames = decoder.read(pcm, pcm.size, MAX_BLOCK_FRAMES).frames
        if (frames <= 0) return null
        blockBytes = decoder.lastBlockBytes
        decoderPosition += blockBytes

        // Seek que cayó en medio del bloque: se descartan las muestras previas al objetivo
        // devolviendo el bloque ya recortado (sin tocar los bytes: es un desplazamiento).
        if (pendingSkipSamples > 0) {
            val skip = minOf(pendingSkipSamples, frames)
            val frameBytes = streamFormat.frameBytes
            val keep = frames - skip
            if (keep > 0) {
                System.arraycopy(pcm, skip * frameBytes, pcm, 0, keep * frameBytes)
            }
            pendingSkipSamples -= skip
            frames = keep
            if (frames <= 0) return decodeNextBlock(pcm)
        }
        return DecodedFrame(frames)
    }

    /**
     * Avanza el `DataSource` los bytes que ocupó el bloque recién decodificado. Se usa
     * `skipFully` y no `read` porque los bytes ya están en el decodificador (leerlos otra vez
     * sería trabajo al doble, y en un archivo local es sólo un `lseek`).
     */
    private fun skipBlockBytes(input: ExtractorInput) {
        val toSkip = blockBytes
        if (toSkip <= 0L) return
        try {
            input.skipFully(toSkip.toInt())
        } catch (eof: IOException) {
            // Si el archivo cambió de tamaño, el próximo `read` lo va a terminar de detectar.
            finished = true
        }
    }

    private fun buildFormat(streamFormat: FlacDecoderBridge.StreamFormat): Format {
        val encoding = when (streamFormat.bitsPerSample) {
            16 -> C.ENCODING_PCM_16BIT
            24 -> C.ENCODING_PCM_24BIT
            32 -> C.ENCODING_PCM_32BIT
            // 8 y 12 bits existen en FLAC pero el motor no tiene camino entero para ellos:
            // se piden como 16 bits (AudioEngine los rechaza antes que mentir sobre el
            // bit-perfect, y la UI lo muestra como conversión).
            else -> C.ENCODING_PCM_16BIT
        }
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(streamFormat.sampleRate)
            .setChannelCount(streamFormat.channels)
            .setCodecs("flac")
            .build()
    }

    private fun buildSeekMap(streamFormat: FlacDecoderBridge.StreamFormat): SeekMap {
        val durationUs = streamFormat.durationUs
        if (seekEntries.isEmpty() || durationUs <= 0) {
            // Sin puntos de referencia no se puede buscar: Media3 desactiva el seek en esta
            // pista en vez de saltar a ciegas sobre el archivo.
            return SeekMap.Unseekable(durationUs)
        }
        val entries = seekEntries.toTypedArray()
        val sampleRate = streamFormat.sampleRate
        return object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs

            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val targetSample = if (sampleRate > 0) timeUs * sampleRate / 1_000_000L else 0L
                var low = 0
                var high = entries.size - 1
                var found = 0
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    if (entries[mid].sampleNumber <= targetSample) {
                        found = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                val entry = entries[found]
                val first = SeekPoint(entry.sampleNumber * 1_000_000L / sampleRate, entry.byteOffset)
                // El punto siguiente permite a Media3 acotar la búsqueda entre dos frames.
                val next = entries.getOrNull(found + 1)
                return if (next != null) {
                    SeekMap.SeekPoints(
                        first,
                        SeekPoint(next.sampleNumber * 1_000_000L / sampleRate, next.byteOffset),
                    )
                } else {
                    SeekMap.SeekPoints(first)
                }
            }
        }
    }

    companion object {
        /** Bloque máximo de FLAC (65535) con margen. */
        private const val MAX_BLOCK_FRAMES = 65_536

        /**
         * Tope del índice de frames (4 bytes por entrada en tres arrays). Si el archivo tiene
         * más bloques, el índice toma uno cada N para cubrir todo el archivo.
         */
        private const val MAX_INDEXED_FRAMES = 20_000

        /** true si la URI es un archivo local que el decodificador puede leer por su cuenta. */
        fun canDecode(uri: android.net.Uri?): Boolean {
            if (uri == null) return false
            val path = uri.path ?: return false
            if (!path.endsWith(".flac", ignoreCase = true)) return false
            return uri.scheme == "file" || uri.scheme == "content" || uri.scheme == null
        }
    }
}
