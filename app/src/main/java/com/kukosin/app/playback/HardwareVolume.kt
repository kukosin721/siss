package com.kukosin.app.playback

import android.content.Context
import android.media.AudioManager

/**
 * Volumen de `STREAM_MUSIC`, **de sólo lectura**.
 *
 * Este objeto movía el índice del stream del sistema y era una de las causas del
 * "el volumen sube solo": la app volvía a escribir el índice cada vez que se abría una
 * pista o se reconstruía el reproductor, pisando lo que el usuario (o el DAC) había
 * puesto. Desde la reescritura del motor de Oboe la app no escribe el volumen del
 * sistema: lo lee y lo refleja. Los botones del teléfono y el control del DAC son los
 * que mandan.
 *
 * Se conserva la API de lectura porque la UI y el diagnóstico la usan.
 */
object HardwareVolume {

    /** Nivel actual del sistema (0..1). */
    fun current(context: Context?): Float {
        val audioManager = context?.getSystemService(AudioManager::class.java) ?: return 1f
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        if (max <= min) return 1f
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((index - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }
}
