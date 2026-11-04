package com.kukosin.app.data.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import com.kukosin.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extrae la forma de onda REAL de la canción (no random).
 *
 * - Una canción silenciosa → barras planas (RMS ≈ 0)
 * - Una con dinámica → picos reales donde hay energía
 * - Cada barra = RMS de un segmento del archivo (no 3 puntos como en PcmDecoder.capture)
 *
 * Cache en memoria para no re-decodificar la misma canción.
 * Si falla (YTM sin archivo, permiso, codec), devuelve null y la UI usa fallback.
 */
object WaveformExtractor {

    private const val BARS_DEFAULT = 180
    private const val TIMEOUT_US = 10_000L

    /**
     * Versión del formato de onda. Al subirla cambia la clave de la caché en disco, así
     * las ondas incompletas que dejó la versión anterior (media canción + cola plana) no
     * se siguen mostrando: se vuelven a decodificar una vez con el extractor corregido.
     *
     * v3: además del extractor nuevo, invalida las ondas que quedaron escritas con
     * separador decimal de coma (`String.format` con locale español), que al releerse
     * se partían en dos números y recortaban la onda.
     */
    private const val CACHE_VERSION = 3

    /** Tope absoluto de decodificación por pista (un archivo de 10 min sobra). */
    private const val HARD_TIMEOUT_NANOS = 45_000_000_000L

    /** Si el codec deja de entregar audio este tiempo, se corta y se usa lo medido. */
    private const val STALL_TIMEOUT_NANOS = 6_000_000_000L

    // Cache simple: uri string -> waveform normalizada 0..1
    private val cache = linkedMapOf<String, List<Float>>()
    private const val MAX_CACHE = 40

    /**
     * Un solo decodificador de ondas a la vez. Si el usuario recorre diez pistas
     * rápido, antes se lanzaban diez decodificaciones completas en paralelo (cada una
     * con su MediaCodec): CPU saturada, audio con glitches y ondas que llegaban
     * fuera de orden. Con el mutex se hace una por vez y las anteriores se cancelan
     * en el próximo buffer.
     */
    private val decodeLock = Mutex()

    /**
     * Caché en disco. Decodificar el archivo completo tarda entre 1 y 3 s en un teléfono
     * (media pista de 4 min); por eso la onda es lo último que aparecía al abrir una
     * canción. Se guarda una vez y a partir de ahí se lee en ~1 ms.
     *
     * Formato: texto plano con valores separados por coma (180 números cortos → pocos kB),
     * escrito de forma atómica (tmp + rename).
     */
    private fun diskFile(context: Context, key: String): java.io.File {
        val name = key.hashCode().toUInt().toString(16) + "-" + bars0(key)
        return java.io.File(java.io.File(context.cacheDir, "waveforms"), "$name.wave")
    }

    private fun bars0(key: String): String = key.length.toString(16)

    private fun loadFromDisk(context: Context, key: String): List<Float>? = runCatching {
        val file = diskFile(context, key)
        if (!file.exists()) return null
        val values = file.readText()
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it.isFinite() }
        values.takeIf { it.size >= 8 }
    }.getOrNull()

    private fun saveToDisk(context: Context, key: String, waveform: List<Float>) {
        runCatching {
            val file = diskFile(context, key)
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            // Float.toString usa punto decimal siempre. `String.format` usaba el
            // locale del teléfono: en español escribía "0,1234" y al leerlo se
            // partía en dos números ("0" y "1234"). Eso corrompía la caché y la
            // onda se veía recortada o plana en la segunda apertura de la pista.
            tmp.writeText(waveform.joinToString(",") { it.toString() })
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun getWaveform(
        context: Context,
        track: Track,
        bars: Int = BARS_DEFAULT
    ): List<Float>? = withContext(Dispatchers.IO) {
        // Solo archivos locales tienen PCM accesible
        if (!track.isLocal) return@withContext null

        val key = "v$CACHE_VERSION|${track.uri}|$bars"
        synchronized(cache) { cache[key]?.let { return@withContext it } }

        // 1) Disco: si ya se calculó antes, no se vuelve a decodificar el archivo.
        loadFromDisk(context, key)?.let { stored ->
            synchronized(cache) { cache[key] = stored }
            return@withContext stored
        }

        // 2) Decodificación real (una sola vez por canción).
        val job = coroutineContext[Job]
        val result = decodeLock.withLock {
            // La extracción es CPU-intensiva y corre mientras suena la canción: se
            // baja la prioridad del hilo para que el hilo de audio y la UI manden.
            val tid = Process.myTid()
            val previousPriority = Process.getThreadPriority(tid)
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                extract(
                    context = context,
                    uri = track.uri,
                    durationMs = track.durationMs,
                    bars = bars,
                    isCancelled = { job?.isActive == false },
                )
            } finally {
                Process.setThreadPriority(previousPriority)
            }
        } ?: return@withContext null
        saveToDisk(context, key, result)

        synchronized(cache) {
            if (cache.size >= MAX_CACHE) cache.remove(cache.keys.first())
            cache[key] = result
        }
        result
    }

    /**
     * Decodifica el archivo completo vía MediaCodec y calcula RMS por barra.
     * No guarda todas las muestras en RAM: acumula sumSq por barra a medida que decodifica.
     */
    private fun extract(
        context: Context,
        uri: Uri,
        durationMs: Long,
        bars: Int,
        isCancelled: () -> Boolean = { false },
    ): List<Float>? {
        if (bars <= 0) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
            val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 2 }
                .coerceIn(1, 8)

            // Duración real, en microsegundos. Se prefiere la que ya conoce la biblioteca;
            // si no está, se lee del propio contenedor. De acá sale cuántas muestras
            // pertenecen a cada barra, así la onda se reparte sobre TODA la canción.
            val durationUs = when {
                durationMs > 0L -> durationMs * 1_000L
                format.containsKey(MediaFormat.KEY_DURATION) ->
                    runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                else -> 0L
            }
            val totalFrames = if (durationUs > 0L && sampleRate > 0) {
                (durationUs * sampleRate / 1_000_000L).coerceAtLeast(bars.toLong())
            } else {
                0L
            }
            // Sin duración no se puede repartir por tiempo: se usa una estimación de
            // tres minutos y, si el archivo termina antes, la cola queda plana (honesto).
            val fallbackFrames = 180L * sampleRate.coerceAtLeast(1)

            val sumSqPerBar = DoubleArray(bars)
            val countPerBar = LongArray(bars)
            var framesDecoded = 0L

            var pcmEncodingBits = 16
            var pcmIsFloat = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var deadlines = 0
            val startedAt = System.nanoTime()
            var lastOutputAt = startedAt

            while (!outputDone) {
                // Si la pista cambió (o la pantalla se cerró), este trabajo ya no le
                // sirve a nadie: se corta acá y no se guarda una onda a medias.
                if (isCancelled()) return null
                val now = System.nanoTime()
                // Antes había un tope de 4 s para todo el archivo: en una canción de
                // 4 minutos eso decodificaba sólo el primer tramo y el resto quedaba
                // como una línea plana. Ahora el tope es de 45 s (de sobra para un
                // archivo largo en un teléfono lento) y, sobre todo, se corta por
                // ESTANCAMIENTO: si el decodificador deja de entregar audio 6 s, no
                // tiene sentido seguir esperando.
                if (now - startedAt > HARD_TIMEOUT_NANOS) break
                if (now - lastOutputAt > STALL_TIMEOUT_NANOS) break

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex) ?: break
                        val size = extractor.readSampleData(buf, 0)
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
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            when (outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    pcmIsFloat = true
                                    pcmEncodingBits = 32
                                }
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                    pcmIsFloat = false
                                    pcmEncodingBits = 24
                                }
                                AudioFormat.ENCODING_PCM_32BIT -> {
                                    pcmIsFloat = false
                                    pcmEncodingBits = 32
                                }
                                else -> {
                                    pcmIsFloat = false
                                    pcmEncodingBits = 16
                                }
                            }
                        }
                    }
                    outIndex >= 0 -> {
                        lastOutputAt = System.nanoTime()
                        deadlines = 0
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            outBuf.order(ByteOrder.nativeOrder())
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)

                            val bytesPerSample = if (pcmIsFloat || pcmEncodingBits == 32) 4 else if (pcmEncodingBits == 24) 3 else 2
                            val frameBytes = bytesPerSample * channelCount
                            var framesInBuffer = 0L

                            while (outBuf.remaining() >= frameBytes) {
                                var monoSum = 0f
                                repeat(channelCount) {
                                    val sample = when {
                                        pcmIsFloat -> outBuf.float
                                        pcmEncodingBits == 32 -> outBuf.int / 2_147_483_648f
                                        pcmEncodingBits == 24 -> {
                                            val b0 = outBuf.get().toInt() and 0xFF
                                            val b1 = outBuf.get().toInt() and 0xFF
                                            val b2 = outBuf.get().toInt()
                                            var v = (b2 shl 16) or (b1 shl 8) or b0
                                            if (v and 0x800000 != 0) v = v or -0x1000000
                                            v / 8_388_608f
                                        }
                                        else -> outBuf.short / 32_768f
                                    }
                                    monoSum += sample
                                }
                                val mono = monoSum / channelCount
                                // La barra se decide por número de frame decodificado
                                // contra el total de frames de la canción. Así la última
                                // barra corresponde al final del archivo aunque el codec
                                // entregue buffers de tamaños distintos.
                                val frameIndex = framesDecoded + framesInBuffer
                                val framesForWhole = if (totalFrames > 0L) totalFrames else fallbackFrames
                                val bar = ((frameIndex * bars) / framesForWhole)
                                    .toInt()
                                    .coerceIn(0, bars - 1)
                                sumSqPerBar[bar] += mono.toDouble() * mono.toDouble()
                                countPerBar[bar]++
                                framesInBuffer++
                            }
                            framesDecoded += framesInBuffer
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++deadlines > 1500) break
                    }
                }
            }

            val covered = countPerBar.indexOfLast { it > 0 } + 1
            val rmsPerBar = DoubleArray(bars) { i ->
                val c = countPerBar[i]
                if (c == 0L) 0.0 else sqrt(sumSqPerBar[i] / c)
            }
            val maxRms = rmsPerBar.maxOrNull()?.takeIf { it > 1e-9 } ?: 1.0

            // Normaliza 0..1 con compresión visual (pow 0.58). Silencio real => barra
            // casi plana en lugar de una onda inventada.
            var normalized = rmsPerBar.map { rms ->
                val n = (rms / maxRms).coerceIn(0.0, 1.0)
                if (maxRms < 0.005) 0.06 else n.pow(0.58)
            }.map { it.toFloat().coerceIn(0f, 1f) }

            // Red de seguridad: si por un codec raro la decodificación se cortó antes
            // del final, se reparte lo que sí se midió a lo ancho de toda la pista en
            // vez de dejar media onda y un palo plano. Es peor mentir con una cola
            // plana que mostrar la dinámica real del tramo decodificado.
            if (covered in 1 until bars && covered >= bars / 3) {
                normalized = stretchBars(normalized, covered, bars)
            } else if (covered in 1 until bars && covered < bars / 3) {
                return null
            }
            normalized
        } catch (_: Exception) {
            null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Reparte [source] (primeras [from] barras) a lo ancho de [to] barras. */
    private fun stretchBars(source: List<Float>, from: Int, to: Int): List<Float> {
        if (from <= 0) return List(to) { 0f }
        if (from >= to) return source.take(to)
        return List(to) { i ->
            val f = i * from.toFloat() / to
            val lo = f.toInt().coerceIn(0, from - 1)
            val hi = (lo + 1).coerceAtMost(from - 1)
            val frac = f - lo
            source[lo] * (1 - frac) + source[hi] * frac
        }
    }
}
