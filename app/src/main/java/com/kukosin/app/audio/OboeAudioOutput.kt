package com.kukosin.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.kukosin.app.playback.OutputMode
import com.kukosin.app.playback.UsbDacManager
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * [AudioOutput] de Media3 servido por el motor nativo (Oboe/AAudio).
 *
 * Todo lo que entra por [write] es PCM ya decodificado (y, si el usuario lo pidió, ya
 * ecualizado). Acá no se convierte nada: el motor nativo es el único que decide, y sólo
 * convierte cuando el HAL no acepta el formato exacto (y lo informa).
 *
 * Reglas del contrato de Media3 que este adaptador respeta:
 *
 *  1. [stop] = "deja sonar lo que queda". El motor drena y recién ahí se detiene;
 *     devolver el control con audio pendiente cortaba muestras y rompía el gapless.
 *  2. [getPositionUs] es la posición de **playout** (lo que ya sonó), no los frames que
 *     el callback copió. Si se informara de menos, Media3 alimentaría más despacio de lo
 *     que el DAC consume y el buffer se vaciaría: cortes periódicos.
 *  3. [write] devuelve `false` cuando no entra todo: Media3 reenvía exactamente el tramo
 *     pendiente del mismo buffer.
 *  4. El volumen SIEMPRE hace algo: si el DAC no expone su atenuador por USB, se atenúa
 *     en el motor; nunca se queda fijo en 100 % como antes.
 */
@UnstableApi
class OboeAudioOutput(
    private val engine: AudioEngine,
    private val sampleRate: Int,
    private val mode: OutputMode,
    private val appContext: Context?,
    /** true = vaciar sin esperar las transiciones de estado del HAL (menos corte al saltar). */
    private val fastTransitions: Boolean = true,
    /** Formato con el que se abrió el motor (lo que el HAL aceptó, no lo que se pidió). */
    private val openedConfig: AudioOutputProvider.OutputConfig,
) : AudioOutput {

    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    /**
     * Un solo informante de posición, siempre. Media3 exige que
     * `AudioOutput.Listener.onPositionAdvancing` llegue desde **un único hilo** (el real
     * de AudioTrack lo hace con un hilo propio). Si además lo llamáramos desde `play()` y
     * desde el hilo de escritura, la sincronización de ExoPlayer recibiría dos relojes
     * distintos y el buffer se vaciaría en los huecos: cortes.
     */
    private val ticker = PositionTicker()

    private var stalled = false
    private var released = false
    private var lastPositionUs = 0L
    private var preferredDevice: AudioDeviceInfo? = null
    /** Último volumen pedido por Media3 (0..1), para poder reaplicarlo. */
    @Volatile
    private var playerVolume = 1f
    /**
     * Ruta de volumen realmente disponible. Se decide **una vez**, mirando lo que el
     * stream abrió (no lo que se pidió), y no cambia mientras viva este output: así el
     * volumen nunca "salta" a mitad de una pista.
     */
    @Volatile
    private var dacVolumeAvailable = false

    init {
        openDacVolumeIfNeeded()
        dacVolumeAvailable = mode.usesHardwareVolume && UsbDacManager.hasHardwareVolume()
    }

    /**
     * Abre el control de volumen del DAC (Feature Unit de USB Audio Class) si el modo lo
     * va a usar. Es una lectura de dos bytes por control request, no bloquea, y hay que
     * hacerlo ANTES del primer `setVolume`, que llega apenas Media3 conoce el volumen.
     */
    private fun openDacVolumeIfNeeded() {
        val context = appContext ?: return
        if (!mode.usesHardwareVolume) return
        if (UsbDacManager.hasHardwareVolume()) return
        runCatching { UsbDacManager.openHardwareVolume(context) }
    }

    /**
     * ¿Puede Media3 seguir usando este output para otra pista?
     *
     * Sí, mientras el formato no cambie: el motor deja el stream en PAUSED después del
     * flush, así que reutilizarlo evita destruir y volver a abrir el stream en cada
     * cambio de canción (eso era un clic y ~1 s de silencio por pista). Con otro formato
     * habría que resamplear, que es justo lo que este reproductor no quiere.
     */
    override fun canReuseAudioOutput(
        currentConfig: AudioOutputProvider.OutputConfig,
        newFormatConfig: AudioOutputProvider.FormatConfig,
        newConfig: AudioOutputProvider.OutputConfig,
    ): Boolean {
        return openedConfig.encoding == newConfig.encoding &&
            openedConfig.sampleRate == newConfig.sampleRate &&
            openedConfig.channelMask == newConfig.channelMask
    }

    override fun play() {
        engine.start()
        ticker.begin()
        // Si el stream murió (DAC desconectado, HAL que lo retiró), Media3 tiene que
        // enterarse; si no, se queda "reproduciendo" en silencio para siempre.
        reportTerminalError()
    }

    override fun pause() {
        ticker.stopTicker()
        // El motor aplica una rampa corta y se detiene solo; los datos quedan en el
        // anillo para reanudar exactamente donde estaba.
        engine.pause()
    }

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        val remaining = buffer.remaining()
        if (remaining == 0) return true

        // La apertura del stream es diferida: si falló, hay que fallar YA. Devolver
        // `false` sin consumir deja a Media3 reintentando para siempre.
        reportTerminalError()

        val accepted = engine.write(buffer).coerceIn(0, remaining)
        buffer.position(buffer.position() + accepted)

        val complete = accepted == remaining
        stalled = !complete
        if (!complete) {
            // Anillo lleno (normal: se reintenta en la próxima llamada) o stream muerto.
            reportTerminalError()
        }
        return complete
    }

    /**
     * Un fallo del motor no es "no hay espacio": se informa como excepción de escritura
     * para que Media3 recree el sink (o muestre el error) en vez de quedar reintentando
     * contra un stream muerto.
     */
    private fun reportTerminalError() {
        val code = engine.errorCode()
        if (code != 0) throw AudioOutput.WriteException(code, false)
    }

    /**
     * Descarta lo pendiente. El motor deja el stream en PAUSED (no FLUSHED) para que
     * Media3 pueda reutilizar este mismo [AudioOutput] después de un seek.
     */
    override fun flush() {
        ticker.stopTicker()
        val result = engine.flush(waitForHardware = !fastTransitions)
        if (!result.isOk && result != NativeResult.ERROR_INVALID_STATE) {
            AudioEngineStatus.error("Flush fell back to a partial reset (${result.description})")
        }
        stalled = false
        lastPositionUs = 0L
    }

    /**
     * Fin de stream. Media3 llama a [stop] una sola vez, desde `playPendingData()`, y
     * espera que lo encolado suene: el motor drena el anillo y se detiene solo. El
     * drenado está acotado por timeout, así que no puede colgar el hilo de audio.
     */
    override fun stop() {
        ticker.stopTicker()
        engine.stopAndWait(END_OF_STREAM_DRAIN_TIMEOUT_MS)
        stalled = false
    }

    override fun release() {
        if (released) return
        ticker.stopTicker()
        released = true
        // Orden importante:
        //  1. se para el hilo de posición (deja de leer el motor),
        //  2. se cierra el motor (suelta el stream y el HAL),
        //  3. y sólo entonces se informa `onReleased`.
        // Informar antes de cerrar deja a Media3 creyendo que ya soltó el dispositivo
        // cuando el HAL todavía lo tiene: en el peor caso, el stream siguiente se abre
        // mientras el anterior sigue vivo (dos stream a la vez = audio cortado).
        runCatching { ticker.join(TICKER_JOIN_TIMEOUT_MS) }
        engine.close()
        // El atenuador del DAC se restaura al nivel que tenía: si no, queda bajado para
        // Android y para las demás apps.
        if (mode.usesHardwareVolume && appContext != null) {
            runCatching { UsbDacManager.closeHardwareVolume() }
        }
        AudioEngineStatus.detach()
        listeners.forEach { it.onReleased() }
    }

    /**
     * Volumen del reproductor (0..1). **Nunca se queda sin aplicar**:
     *
     *  - DAC USB / directo con Feature Unit → el atenuador del propio DAC (la muestra no
     *    se toca).
     *  - Cualquier otro caso → atenuación digital en el motor, que a 100 % es exactamente
     *    1.0 y no modifica un solo byte.
     *
     * El fallo anterior era dejar el motor en 1.0 confiando en un control de hardware que
     * podía no existir: en un equipo sin ese control, el volumen quedaba clavado al 100 %
     * y el deslizador del teléfono no hacía nada.
     */
    override fun setVolume(volume: Float) {
        playerVolume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        // El atenuador del DAC va aparte del volumen del sistema: es el que mueve el
        // hardware del convertidor. Sólo se usa si existe de verdad.
        if (dacVolumeAvailable) {
            runCatching { UsbDacManager.setHardwareVolume(playerVolume) }
            engine.setVolume(1f)
            return
        }
        // Sin atenuador en el DAC la atenuación va en el motor, con curva perceptual y
        // rampa corta. A 100 % el motor detecta ganancia 1.0 y copia bit a bit.
        engine.setVolume(playerVolume)
    }

    /** Se llama cuando el control del DAC aparece o desaparece (reconexión). */
    fun onDacVolumeAvailabilityChanged() {
        if (released) return
        openDacVolumeIfNeeded()
        val available = mode.usesHardwareVolume && UsbDacManager.hasHardwareVolume()
        if (available != dacVolumeAvailable) {
            dacVolumeAvailable = available
            // Si el atenuador del DAC apareció, el motor vuelve a 1.0 y el volumen pasa
            // al hardware (sin tocar la muestra). Si desapareció, se atenúa en el motor.
            applyVolume()
        }
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate(): Int = sampleRate

    /**
     * Colchón del motor, en frames: el ANILLO, no el buffer del HAL.
     *
     * Media3 usa este número para decidir cuánto audio deja acumulado antes de escribir
     * y con cuánta antelación alimenta. Si se le informa el buffer del HAL (una o dos
     * ráfagas, ~4–10 ms), alimenta de menos y el anillo se queda vacío: es exactamente el
     * patrón de cortes cada ~100 ms.
     */
    override fun getBufferSizeInFrames(): Long {
        val info = engine.info()
        val frames = when {
            info.ringFrames > 0 -> info.ringFrames
            info.bufferFrames > 0 -> info.bufferFrames
            info.framesPerBurst > 0 -> info.framesPerBurst * 4
            else -> 0
        }
        return frames.toLong()
    }

    /**
     * Posición de playout en microsegundos. El motor la calcula con
     * `AAudioStream_getTimestamp` (frames entregados menos el buffer del HAL) y la
     * mantiene monótona. Acá se vuelve a garantizar la monotonía porque Media3 la usa
     * para decidir si hay que alimentar más: un retroceso hace que el buffer se vacíe.
     */
    override fun getPositionUs(): Long {
        if (sampleRate <= 0) return 0L
        val raw = engine.positionMicros()
        if (raw < lastPositionUs) return lastPositionUs
        lastPositionUs = raw
        return raw
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

    override fun setOffloadDelayPadding(delaySamples: Int, paddingSamples: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun attachAuxEffect(auxEffectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
    }

    override fun isStalled(): Boolean = stalled

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    /**
     * Hilo propio que informa la posición de playout a Media3 mientras el stream está
     * sonando.
     *
     * `AudioTrackAudioOutput` de Media3 hace exactamente esto (un `PositionTracker`
     * propio). Sin señales periódicas, ExoPlayer cree que no hay progreso y deja de
     * alimentar el buffer: es la causa mecánica de los cortes periódicos.
     *
     * Usa `SystemClock.elapsedRealtime()` (monótono) y un `sleep` de 50 ms: no toca el
     * hilo de audio ni el candado del motor, así que no puede provocar un xrun.
     */
    private inner class PositionTicker : Thread("oboe-position") {
        @Volatile
        private var running = false

        /** Idempotente: arranca el hilo una vez y lo deja informando. */
        fun begin() {
            if (released) return
            running = true
            if (state == State.NEW) {
                isDaemon = true
                // `Thread` de Java: hay que calificar `start` para no llamarse a sí mismo.
                @Suppress("DEPRECATION")
                super.start()
            }
        }

        fun stopTicker() {
            running = false
        }

        override fun run() {
            while (!released) {
                if (running) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    listeners.forEach { it.onPositionAdvancing(now) }
                }
                try {
                    Thread.sleep(TICK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private companion object {
        /** Cada cuánto se informa progreso. El real de Media3 usa 100 ms. */
        const val TICK_INTERVAL_MS = 50L

        /** Espera máxima al hilo de posición en [release]. */
        const val TICKER_JOIN_TIMEOUT_MS = 200L
        /**
         * Media3 llama a [stop] una vez por stream; si el DAC no consume (p. ej. la app
         * está en segundo plano y el dispositivo se durmió), no se puede colgar el hilo
         * de audio indefinidamente.
         */
        const val END_OF_STREAM_DRAIN_TIMEOUT_MS = 750
    }
}
