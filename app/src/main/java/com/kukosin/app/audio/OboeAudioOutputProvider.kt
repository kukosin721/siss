package com.kukosin.app.audio

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import com.kukosin.app.playback.AudioRoute
import com.kukosin.app.playback.AudioSettingsManager
import com.kukosin.app.playback.BitPerfectMixer
import com.kukosin.app.playback.OutputMode
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Salida de audio servida por el motor de Oboe, según el [OutputMode] elegido.
 *
 * `DISABLED` no pasa por acá: en ese modo el sink usa AudioTrack (mezclador de Android).
 *
 * Para los modos con motor, el motor intenta en orden:
 * 1. el formato exacto de la pista, exclusivo, sin conversión  → bit-perfect;
 * 2. el formato exacto, compartido (sólo si el modo lo permite);
 * 3. otro rate con la conversión polifásica propia (sólo si el modo no es estricto);
 * 4. otro formato (sólo si el modo no es estricto).
 *
 * Nunca "adivina": lo que devolvió el HAL se publica en [AudioEngineStatus] y la UI
 * muestra eso.
 */
@UnstableApi
class OboeAudioOutputProvider(
    private val mode: OutputMode,
) : AudioOutputProvider {

    private val listeners = CopyOnWriteArraySet<AudioOutputProvider.Listener>()

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val encoding = format.pcmEncoding
        val supported = encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT
        // El motor abre el stream con EL MISMO número de canales de la pista
        // (`setChannelConversionAllowed(false)`), y el HAL de Android sólo expone
        // configuraciones de altavoz conocidas. Sólo se promete lo que se puede cumplir
        // de verdad (>2 canales lo resuelve el mezclador de Media3, no este motor).
        val channelCount = format.channelCount
        if (!supported || channelCount !in 1..MAX_CHANNELS) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        val channels = format.channelCount.coerceIn(1, MAX_CHANNELS)
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
            -> format.pcmEncoding
            else -> C.ENCODING_PCM_16BIT
        }
        // La frecuencia es SIEMPRE la del archivo: así no hay resample en el camino.
        val sampleRate = if (format.sampleRate > 0) format.sampleRate else 48_000
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val settings = AudioSettingsManager.configState.value
        val target = AudioRoute.deviceFor(mode)

        // Fail-closed: si el modo apunta a un DAC USB y no hay DAC, NO se abre el
        // altavoz interno a escala completa. La UI ya bloquea estos modos sin DAC; esto
        // cierra la última puerta, que estaba en el camino nativo.
        if (mode.requiresUsb && target == null) {
            val message = "No USB DAC available for ${mode.label}. " +
                "Connect it and try again, or pick the built-in output."
            AudioEngineStatus.error(message)
            throw AudioOutputProvider.InitializationException(IllegalStateException(message))
        }

        val channels = Integer.bitCount(config.channelMask).coerceIn(1, MAX_CHANNELS)
        val sourceFormat = PcmFormat.fromMedia3Encoding(config.encoding)
        val requireDevice = mode.targetsUsb && target != null

        fun open(deviceId: Int): AudioEngine {
            val engine = AudioEngine()
            val result = engine.configure(
                AudioEngineConfig(
                    sourceFormat = sourceFormat,
                    sourceSampleRate = config.sampleRate,
                    sourceChannels = channels,
                    deviceId = deviceId,
                    strictBitPerfect = settings.strictBitPerfect,
                    requireExclusive = mode.requiresExclusive,
                    // Sin DAC no se cae a otro dispositivo en modo USB: mejor fallar.
                    requireDevice = requireDevice,
                    bufferMs = settings.bufferMs,
                    fades = settings.fades,
                    prerollMs = settings.prerollMs,
                    volumeCurve = settings.volumeCurve,
                ),
            )
            if (!result.isOk) {
                engine.close()
                throw AudioOutputProvider.InitializationException(
                    IllegalStateException(
                        "Oboe could not open ${sourceFormat.bits}-bit @ ${config.sampleRate} Hz: " +
                            result.description,
                    ),
                )
            }
            // `configure()` sólo acepta la configuración: la apertura real ocurre en un
            // hilo nativo propio (para no bloquear el hilo de audio al negociar
            // exclusividad). Acá se espera el resultado —este método corre en el hilo de
            // reproducción de Media3, nunca en el principal— para poder informar el error
            // en la UI y reintentar con otro dispositivo si corresponde.
            val opened = engine.awaitOpen(OPEN_TIMEOUT_MS)
            if (!opened.isOk) {
                engine.close()
                throw AudioOutputProvider.InitializationException(
                    IllegalStateException(
                        "Oboe could not open ${sourceFormat.bits}-bit @ ${config.sampleRate} Hz: " +
                            opened.description,
                    ),
                )
            }
            engine.setVolumeCurve(settings.volumeCurve)
            return engine
        }

        // El id puede quedar viejo si el DAC se reconectó: sólo en ese caso tiene sentido
        // reintentar por defecto, y nunca cuando la ruta pedida es USB.
        val engine = try {
            try {
                open(target?.id ?: 0)
            } catch (first: AudioOutputProvider.InitializationException) {
                if (target == null || requireDevice) {
                    AudioEngineStatus.error(
                        "Could not open the output (${sourceFormat.bits}-bit @ " +
                            "${config.sampleRate} Hz). " +
                            if (mode.requiresExclusive) {
                                "Try another output mode or disable strict mode."
                            } else {
                                "Try another output mode."
                            },
                    )
                    throw first
                }
                try {
                    open(0)
                } catch (second: AudioOutputProvider.InitializationException) {
                    AudioEngineStatus.error(
                        "Could not open ${deviceLabel()} (${sourceFormat.bits}-bit @ " +
                            "${config.sampleRate} Hz): ${second.message}. " +
                            "This mode needs an exclusive stream the device is not providing.",
                    )
                    throw second
                }
            }
        } catch (failure: AudioOutputProvider.InitializationException) {
            // Red de seguridad del modo automático: Oboe se intenta primero (es el camino
            // de mayor fidelidad), pero si este equipo no puede abrir el stream —HAL
            // viejo, formato no soportado, salida ocupada— la música sigue por AudioTrack
            // en vez de cortarse. Los modos exclusivos NO caen acá a propósito: ahí el
            // fallo se informa.
            if (mode != OutputMode.AUTO) throw failure
            AudioEngineStatus.error(
                "Oboe could not open this output (${failure.message ?: "unknown error"}). " +
                    "Using the Android mixer for this track.",
            )
            return mixerFallback(config) ?: throw failure
        }

        // Si el HAL no dio exclusividad y el stream quedó compartido, se le pide al
        // mezclador de Android 14+ que no inserte SRC ni efectos en ESTA salida. Es un
        // hint: si el equipo lo ignora, el motor ya informa que hubo conversión.
        if (engine.info().let { it.opened && !it.exclusive }) {
            runCatching {
                BitPerfectMixer.apply(
                    AudioRoute.current.value,
                    config.sampleRate,
                    config.encoding,
                    config.channelMask,
                )
            }
        }

        val output = OboeAudioOutput(
            engine = engine,
            sampleRate = config.sampleRate,
            mode = mode,
            appContext = AudioRoute.appContext,
            fastTransitions = settings.fastTransitions,
            // Formato que el motor abrió de verdad. Media3 lo necesita para decidir si
            // puede reutilizar el stream entre pistas o si tiene que reabrir.
            openedConfig = config,
        )
        // El altavoz interno nunca puede ser bit-perfect (tiene su propio DSP y su
        // amplificador). Sólo se marca cuando el destino resuelto es, literalmente, el
        // altavoz del teléfono.
        val builtinSpeaker = target == null &&
            AudioRoute.current.value.isBuiltin &&
            !mode.targetsUsb
        AudioEngineStatus.attach(engine, deviceLabel(), mode, builtinSpeaker)
        return output
    }

    /**
     * Proveedor de AudioTrack que se usa sólo si el modo automático no pudo abrir Oboe.
     * Se crea una vez y se libera con el provider.
     */
    private var mixerFallbackProvider: AudioTrackAudioOutputProvider? = null

    private fun mixerFallback(config: AudioOutputProvider.OutputConfig): AudioOutput? {
        val context = AudioRoute.appContext ?: return null
        val provider = mixerFallbackProvider
            ?: AudioTrackAudioOutputProvider.Builder(context).build()
                .also { mixerFallbackProvider = it }
        return try {
            provider.getAudioOutput(config)
        } catch (failure: AudioOutputProvider.InitializationException) {
            AudioEngineStatus.error(
                "AudioTrack also failed to open this output: " +
                    (failure.message ?: "unknown error"),
            )
            null
        }
    }

    private fun deviceLabel(): String {
        // Nombre del dispositivo que el motor va a abrir de verdad. `AudioRoute` ya lo
        // resuelve por modo (USB / con cable / altavoz); si viene vacío se usa el modo.
        val name = AudioRoute.current.value.name.trim()
        return if (name.isNotEmpty()) name else mode.shortLabel
    }

override fun addListener(listener: AudioOutputProvider.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners.remove(listener)
    }

    override fun release() {
        listeners.clear()
        AudioEngineStatus.detach()
        mixerFallbackProvider?.release()
        mixerFallbackProvider = null
    }

    private companion object {
        /** El motor sólo promete hasta 2 canales directamente (el resto, mixer de Media3). */
        const val MAX_CHANNELS = 2

        /**
         * Espera máxima de la apertura. Un HAL que niega exclusivo contesta en pocos ms;
         * esto sólo evita que un driver colgado bloquee el hilo de audio de Media3.
         */
        const val OPEN_TIMEOUT_MS = 2000
    }
}
