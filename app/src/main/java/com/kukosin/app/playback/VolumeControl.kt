package com.kukosin.app.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El volumen del sistema, **sin adueñarse de él**.
 *
 * Antes los modos "con volumen en hardware" empujaban el deslizador del teléfono con
 * `setStreamVolume`, y el resultado era exactamente el síntoma reportado: al empezar una
 * pista el volumen "subía solo" porque la app volvía a escribir el índice del stream.
 *
 * Ahora el volumen de `STREAM_MUSIC` es la única fuente de verdad:
 *
 *  - Los botones del teléfono, el control del DAC por HID y la pantalla de volumen del
 *    sistema lo cambian; [observe] lo refleja en la app (y en la UI).
 *  - La app **nunca** escribe el índice del sistema: sólo lo lee.
 *  - En los modos con DAC USB el atenuador del DAC se abre aparte (Feature Unit de USB
 *    Audio Class) y sigue siendo el camino preferido cuando el DAC lo expone.
 *
 * Con esto, mover el volumen siempre hace algo: mueve el stream del sistema (que en un
 * DAC USB termina en el convertidor) o, si el equipo lo permite, el atenuador del DAC.
 */
object VolumeControl {

    private val _fraction = MutableStateFlow(1f)
    /** Volumen actual de `STREAM_MUSIC` como fracción 0..1 (incluye el 0 del silencio). */
    val fraction: StateFlow<Float> = _fraction.asStateFlow()

    @Volatile
    private var audioManager: AudioManager? = null

    private var observer: ContentObserver? = null

    /** Empieza a observar el volumen del sistema. Idempotente. */
    fun observe(context: Context) {
        if (observer != null) return
        val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        _fraction.value = readFraction(manager)
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _fraction.value = readFraction(manager)
            }
        }
        // El observador va sobre el ContentProvider de ajustes, no sobre AudioManager.
        runCatching {
            context.applicationContext.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                contentObserver,
            )
        }
        observer = contentObserver
    }

    /** Reléelo ahora (p. ej. al volver del segundo plano). */
    fun refresh() {
        val manager = audioManager ?: return
        _fraction.value = readFraction(manager)
    }

    private fun readFraction(manager: AudioManager): Float {
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = minIndex(manager)
        if (max <= min) return 1f
        val index = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((index - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    private fun minIndex(manager: AudioManager): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }

    /**
     * Aplica un volumen **en el camino que corresponda**, sin tocar el índice del sistema:
     *
     *  - DAC USB con atenuador propio → atenuador del DAC.
     *  - Resto → atenuación digital del motor (a 100 % es bit-exacta).
     *
     * Se usa sólo para el control de volumen de la propia app (modo exclusivo sin DAC),
     * porque el volumen del sistema ya lo aplica Android por su cuenta.
     */
    fun applyToEngine(onFraction: (Float) -> Unit) {
        onFraction(_fraction.value)
    }
}
