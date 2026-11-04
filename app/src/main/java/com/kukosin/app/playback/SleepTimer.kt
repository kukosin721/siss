package com.kukosin.app.playback

import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Temporizador de sueño.
 *
 * No toca el motor nativo: baja el volumen del reproductor (el mismo `setVolume` que ya
 * usa la app) en los últimos segundos y pausa. Así funciona igual en AudioTrack, en Oboe
 * y con DAC USB, sin depender de cómo esté abierta la salida.
 *
 * La rampa existe por la misma razón que los fades del motor: cortar en seco en medio de
 * un tema se escucha como un golpe, y con auriculares a la noche se nota muchísimo.
 */
object SleepTimer {

    /** Cuánto dura la bajada de volumen antes de pausar. */
    private const val FADE_MS = 8_000L
    private const val TICK_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var controllerRef: MediaController? = null

    /** Milisegundos restantes; 0 = apagado. */
    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    fun start(controller: MediaController, minutes: Int) {
        cancel()
        controllerRef = controller
        val total = minutes.coerceIn(1, 480) * 60_000L
        _remainingMs.value = total
        job = scope.launch {
            var left = total
            while (left > 0) {
                delay(TICK_MS)
                left = (left - TICK_MS).coerceAtLeast(0L)
                _remainingMs.value = left
                if (left in 1..FADE_MS) {
                    runCatching { controller.setVolume((left.toFloat() / FADE_MS).coerceIn(0f, 1f)) }
                }
            }
            runCatching { controller.pause() }
            runCatching { controller.setVolume(1f) }
            _remainingMs.value = 0L
            job = null
        }
    }

    /** Apaga el temporizador y devuelve el volumen al 100 % si se había bajado. */
    fun cancel() {
        job?.cancel()
        job = null
        runCatching { controllerRef?.setVolume(1f) }
        _remainingMs.value = 0L
    }
}

/** "1 h 05 min" / "23 min" / "45 s" para el chip del temporizador. */
fun sleepTimerLabel(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(0L)
    val seconds = ((remainingMs % 60_000L) / 1000L).coerceAtLeast(0L)
    return when {
        remainingMs <= 0L -> ""
        totalMinutes >= 60 -> "%d h %02d min".format(totalMinutes / 60, totalMinutes % 60)
        totalMinutes >= 1 -> "${totalMinutes + if (seconds > 0) 1 else 0} min"
        else -> "${seconds.coerceAtLeast(1)} s"
    }
}
