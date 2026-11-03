package com.kukosin.app.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.net.toUri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension

/**
 * Carátula a resolución completa.
 *
 * Antes cada canción usaba `content://media/external/audio/albumart/<albumId>`, que es la
 * miniatura que cachea MediaStore: 300–500 px como mucho, y a veces menos. En la carátula
 * grande del reproductor eso se ve borroso.
 *
 * Acá se lee la imagen **embebida en el archivo** (el APIC de MP3 / picture de FLAC original,
 * que suele venir entre 1000 y 3000 px) y se decodifica con el tamaño justo para la pantalla,
 * así no se gastan 36 MB de RAM por una portada de 3000×3000.
 *
 * El modelo que ve Coil es una URI con esquema propio (`kukosin-cover://audio?u=…`), así que
 * cualquier `AsyncImage(model = track.artworkUri)` funciona sin tocar la UI.
 */
object EmbeddedArtwork {

    private const val SCHEME = "kukosin-cover"
    private const val QUERY_AUDIO = "u"
    private const val QUERY_ALBUM = "a"

    /** Tope de píxeles al decodificar: más que eso no se nota y sí pesa en RAM. */
    private const val MAX_PX = 1600

    /** URI de modelo para Coil que representa la carátula de [audioUri]. */
    fun uriFor(audioUri: Uri, albumId: Long = 0L): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("audio")
        .appendQueryParameter(QUERY_AUDIO, audioUri.toString())
        .appendQueryParameter(QUERY_ALBUM, albumId.toString())
        .build()

    private fun audioUriOf(model: Uri): Uri? = model.getQueryParameter(QUERY_AUDIO)?.toUri()

    private fun albumIdOf(model: Uri): Long =
        model.getQueryParameter(QUERY_ALBUM)?.toLongOrNull() ?: 0L

    /** Fábrica para registrar en un [coil.ImageLoader]. */
    class FetcherFactory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == SCHEME) ArtworkFetcher(context, data, options) else null
    }

    private class ArtworkFetcher(
        context: Context,
        private val model: Uri,
        options: Options,
    ) : Fetcher {

        private val appContext = context.applicationContext

        private val targetPx = run {
            val width = (options.size.width as? Dimension.Pixels)?.px
            val height = (options.size.height as? Dimension.Pixels)?.px
            maxOf(width ?: height ?: MAX_PX, height ?: width ?: MAX_PX).coerceIn(256, MAX_PX)
        }

        override suspend fun fetch(): FetchResult? {
            val audioUri = audioUriOf(model) ?: return null

            // 1. La imagen embebida en el archivo: la de mejor calidad.
            readEmbeddedPicture(appContext, audioUri)?.let { bytes ->
                decodeSampled(bytes, targetPx)?.let { bitmap ->
                    return result(bitmap)
                }
            }

            // 2. Miniatura que el sistema genera a partir del propio archivo (API 29+).
            loadSystemThumbnail(appContext, audioUri)?.let { bitmap -> return result(bitmap) }

            // 3. Último recurso: el proveedor de álbumes clásico (lo que se usaba antes).
            loadLegacyAlbumArt(appContext, albumIdOf(model))?.let { bitmap -> return result(bitmap) }

            return null
        }

        private fun result(bitmap: Bitmap): DrawableResult =
            DrawableResult(
                BitmapDrawable(appContext.resources, bitmap),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
    }

    //region Lectura

    private fun readEmbeddedPicture(context: Context, audioUri: Uri): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, audioUri)
            retriever.embeddedPicture
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun loadSystemThumbnail(context: Context, audioUri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.loadThumbnail(audioUri, Size(1024, 1024), null)
        }.getOrNull()
    }

    private fun loadLegacyAlbumArt(context: Context, albumId: Long): Bitmap? {
        if (albumId <= 0L) return null
        val uri = "content://media/external/audio/albumart/$albumId".toUri()
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Potencia de dos más chica que deje la imagen dentro de [maxPx]. */
    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxPx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    //endregion
}
