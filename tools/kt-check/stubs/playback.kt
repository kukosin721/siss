@file:Suppress("unused", "UNUSED_PARAMETER")
package com.kukosin.app.playback
import android.content.Context
import android.media.AudioDeviceInfo
import com.kukosin.app.audio.AudioEngine

class FakeAudioConfig(
    val strictBitPerfect: Boolean = false,
    val bufferMs: Int = 250,
    val fades: Boolean = true,
)

class SimpleFlow<T>(private val initial: T) { val value: T get() = initial }

class FakeDecoderConfig(
    val decoder: String = "FFmpeg (Lossless)",
    val outputMode: OutputMode = OutputMode.AUTO,
    val strictBitPerfect: Boolean = false,
    val bufferMs: Int = 100,
    val fades: Boolean = true,
    val prerollMs: Int = 6,
    val volumeCurve: Int = 1,
    val fastTransitions: Boolean = true,
)

object AudioSettingsManager {
    val configState = SimpleFlow(FakeDecoderConfig())
}

class AudioRouteInfo {
    val isBuiltin: Boolean = true
    val name: String = "System output"
    val isDac: Boolean = false
    val typeLabel: String = "Default"
}

object AudioRoute {
    val current = SimpleFlow(AudioRouteInfo())
    var appContext: Context? = null
    fun deviceFor(mode: OutputMode): AudioDeviceInfo? = null
}


object BitPerfectMixer { fun apply(route: Any?, sampleRate: Int, encoding: Int, channelMask: Int) {} }

