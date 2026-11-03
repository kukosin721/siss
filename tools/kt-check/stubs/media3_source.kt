@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.exoplayer.source

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/** Stub de `androidx.media3.exoplayer.source.MediaSource`. */
interface MediaSource {
    interface Factory {
        fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
        ): Factory
        fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
        ): Factory
        fun getSupportedTypes(): IntArray
        fun createMediaSource(mediaItem: MediaItem): MediaSource
    }
}

class ProgressiveMediaSource : MediaSource {
    class Factory(
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
        ): MediaSource.Factory = this
        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
        ): MediaSource.Factory = this
        override fun getSupportedTypes(): IntArray = intArrayOf(0)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = ProgressiveMediaSource()
    }
}
