package com.kukosin.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

/**
 * Asks the Android mixer (API 34+) for MIXER_BEHAVIOR_BIT_PERFECT on the
 * preferred device. Exclusive AAudio is the real bypass; this is an extra
 * hint so the HAL does not insert SRC or effects if the stream is shared.
 */
@UnstableApi
object BitPerfectMixer {

    fun apply(
        output: OutputDevice,
        sampleRate: Int,
        encoding: Int,
        channelMask: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val device = AudioRoute.preferredAudioDevice ?: return
        val context = AudioRoute.appContext ?: return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val androidEncoding = when (encoding) {
            C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val format = AudioFormat.Builder()
            .setEncoding(androidEncoding)
            .setSampleRate(sampleRate.coerceAtLeast(44100))
            .setChannelMask(if (channelMask != 0) channelMask else AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val mixer = AudioMixerAttributes.Builder(format)
            .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            .build()

        runCatching {
            if (output.isBitPerfectSupported || output.isUsb || output.isDac) {
                audioManager.setPreferredMixerAttributes(attrs, device, mixer)
            }
        }
    }
}
