package com.kukosin.app.playback

import kotlin.math.pow

/**
 * Estado de ganancia de la cadena de salida: **fundidos** y **ReplayGain**.
 *
 * Vive fuera del procesador porque lo escriben dos hilos distintos: el de audio (que lee
 * la ganancia buffer a buffer) y el de la reproducción (que decide cuándo empieza un
 * fundido). Todo es `@Volatile` y sin locks: perder una escritura de un fundido no rompe
 * nada, y bloquear el hilo de audio sí.
 *
 * El fundido se calcula por **reloj**, no por muestras: así el mismo valor sirve para
 * buffers de 10 ms y de 200 ms, y un cambio de buffer en Ajustes no deja el volumen
 * colgado a mitad de rampa.
 */
object PlaybackGain {

    /** Techo de la corrección positiva: más que esto es adivinar y saturar. */
    const val MAX_BOOST_DB = 12f

    /** Piso de la corrección negativa (una pista mal etiquetada no debe desaparecer). */
    const val MIN_GAIN_DB = -24f

    @Volatile private var rampFrom = 1f
    @Volatile private var rampTo = 1f
    @Volatile private var rampStartNs = 0L
    @Volatile private var rampDurationNs = 0L

    @Volatile private var replayGainFactor = 1f
    @Volatile private var replayGainDb = 0f

    /** Ganancia total en este instante (fundido × ReplayGain). */
    fun current(nowNs: Long = System.nanoTime()): Float = fadeAt(nowNs) * replayGainFactor

    /** ¿La cadena puede copiar bytes tal cual? */
    fun isTransparent(): Boolean =
        rampDurationNs == 0L && rampTo > 0.999f && replayGainFactor > 0.999f && replayGainFactor < 1.001f

    /** Arranca en silencio y sube hasta 1 en [durationMs]. */
    fun fadeIn(durationMs: Int) {
        if (durationMs <= 0) {
            snapTo(1f)
            return
        }
        startRamp(target = 1f, durationMs = durationMs)
    }

    /** Baja desde donde esté hasta 0 en [durationMs]. */
    fun fadeOut(durationMs: Int) {
        if (durationMs <= 0) {
            snapTo(0f)
            return
        }
        startRamp(target = 0f, durationMs = durationMs)
    }

    /** Corta cualquier rampa y deja la ganancia de fundido en [value]. */
    fun snapTo(value: Float) {
        val safe = value.coerceIn(0f, 1f)
        rampFrom = safe
        rampTo = safe
        rampStartNs = System.nanoTime()
        rampDurationNs = 0L
    }

    /**
     * Aplica la etiqueta ReplayGain. [db] se recorta a un rango razonable: las etiquetas
     * mal escritas (valores de +40 dB, o NaN) existen y no pueden terminar en un buffer
     * saturado ni en silencio absoluto.
     */
    fun setReplayGainDb(db: Float) {
        val safe = if (db.isNaN() || db.isInfinite()) 0f else db.coerceIn(MIN_GAIN_DB, MAX_BOOST_DB)
        replayGainDb = safe
        replayGainFactor = 10f.pow(safe / 20f)
    }

    fun replayGainDb(): Float = replayGainDb

    /** Deja todo neutro (ajuste apagado o reproductor recién construido). */
    fun reset() {
        snapTo(1f)
        setReplayGainDb(0f)
    }

    private fun fadeAt(nowNs: Long): Float {
        val duration = rampDurationNs
        if (duration <= 0L) return rampTo
        val elapsed = nowNs - rampStartNs
        if (elapsed <= 0L) return rampFrom
        val t = (elapsed.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        // smoothstep: entra y sale sin el codo brusco de una rampa lineal
        val eased = (t * t * (3.0 - 2.0 * t)).toFloat()
        return rampFrom + (rampTo - rampFrom) * eased
    }

    private fun startRamp(target: Float, durationMs: Int) {
        val now = System.nanoTime()
        rampFrom = fadeAt(now)
        rampTo = target.coerceIn(0f, 1f)
        rampStartNs = now
        rampDurationNs = durationMs.coerceAtLeast(1).toLong() * 1_000_000L
    }
}
