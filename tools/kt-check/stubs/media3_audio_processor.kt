@file:Suppress("unused", "UNUSED_PARAMETER")
/** Stub de `androidx.media3.common.audio` (sólo firmas, espejo de Media3 1.11). */
package androidx.media3.common.audio

import java.nio.ByteBuffer

interface AudioProcessor {
    fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat
    fun isActive(): Boolean
    fun queueInput(inputBuffer: ByteBuffer)
    fun getOutput(): ByteBuffer
    fun flush()
    fun reset()

    class AudioFormat(
        val sampleRate: Int = 0,
        val channelCount: Int = 0,
        val encoding: Int = 0,
    ) {
        val bytesPerFrame: Int get() = 0

        companion object {
            val NOT_SET: AudioFormat = AudioFormat()
        }
    }
}

/** Igual que Media3: los subclases implementan `onConfigure`/`onQueueInput`. */
abstract class BaseAudioProcessor : AudioProcessor {
    protected var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat()
    protected var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat()

    protected abstract fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        onQueueInput(inputBuffer)
    }

    protected open fun onQueueInput(inputBuffer: ByteBuffer) {}

    protected open fun onFlush() {}

    protected open fun onReset() {}

    /** Devuelve un buffer de salida con espacio para `size` bytes (el real reutiliza). */
    protected fun replaceOutputBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size)

    final override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = onConfigure(inputAudioFormat)
        return outputAudioFormat
    }

    override fun isActive(): Boolean = true
    override fun getOutput(): ByteBuffer = ByteBuffer.allocateDirect(0)
    final override fun flush() = onFlush()
    final override fun reset() = onReset()
}
