package com.kukosin.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Elige, por pista, entre el camino normal de Media3 y el decodificador FLAC propio.
 *
 * - **FLAC local** (`file://` o `content://` con extensión `.flac`): se decodifica con
 *   [FlacPcmExtractor], que entrega PCM entero en el ancho nativo (16/24/32 bits). Es la
 *   única forma de que un FLAC de 24 bits sea bit-perfect, porque `MediaCodec` sólo entrega
 *   float o 16 bits y el camino float de Media3 reescala la muestra.
 * - **Todo lo demás** (MP3, ALAC, WAV, AAC, YTM por red…): el pipeline habitual
 *   (`DefaultExtractorsFactory` + FFmpeg/MediaCodec). No se toca nada de lo que ya funciona.
 *
 * El ajuste `decoder` de [AudioSettingsManager] sigue decidiendo entre MediaCodec y FFmpeg
 * para ese segundo grupo; elegir "FFmpeg (Lossless)" no degrada el FLAC local porque el FLAC
 * ya no pasa por ninguno de los dos.
 */
@UnstableApi
class LocalFlacMediaSourceFactory(
    private val context: Context,
    private val upstream: DataSource.Factory,
    private val fallback: MediaSource.Factory,
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = fallback.getSupportedTypes()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (!useOwnDecoder(uri)) return fallback.createMediaSource(mediaItem)
        return ProgressiveMediaSource.Factory(upstream, FlacExtractorsFactory(context, uri!!))
            .createMediaSource(mediaItem)
    }

    private fun useOwnDecoder(uri: Uri?): Boolean {
        if (!FlacPcmExtractor.canDecode(uri)) return false
        // Si el usuario eligió explícitamente MediaCodec, se respeta: sirve para comparar y
        // para diagnosticar problemas del decodificador propio sin desinstalar nada.
        return AudioSettingsManager.configState.value.decoder != "MediaCodec"
    }

    /** Fábrica de extractores que siempre devuelve el extractor FLAC propio. */
    private class FlacExtractorsFactory(
        private val context: Context,
        private val uri: Uri,
    ) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            arrayOf(FlacPcmExtractor(context.applicationContext, uri))
    }
}
