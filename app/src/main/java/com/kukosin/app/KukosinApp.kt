package com.kukosin.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kukosin.app.data.LibraryInsights
import com.kukosin.app.data.artwork.EmbeddedArtwork
import com.kukosin.app.playback.AudioRoute
import com.kukosin.app.ui.UiSettingsManager
import com.kukosin.app.ui.theme.AccentTheme

/**
 * Cargador de imágenes propio.
 *
 * Solo agrega un [coil.fetch.Fetcher] para el esquema `kukosin-cover://`, que devuelve la
 * carátula embebida en el archivo de audio a resolución completa. El resto de las URLs
 * (miniaturas de YouTube Music, etc.) sigue usando los fetchers por defecto de Coil.
 */
class KukosinApp : Application(), ImageLoaderFactory {

    private companion object {
        /** Cuántas carátulas precargar (cada una ~256 KB en memoria). */
        const val PREWARM_LIMIT = 24
    }

    override fun onCreate() {
        super.onCreate()
        // Color de acento guardado por el usuario, antes de que se dibuje cualquier pantalla.
        AccentTheme.init(this)
        LibraryInsights.init(this)
        UiSettingsManager.init(this)
        // Los ajustes de audio también son sólo lectura de SharedPreferences: si faltan acá,
        // el primer `PlaybackService` los carga igual, pero el arranque paga el costo.
        com.kukosin.app.playback.AudioSettingsManager.init(this)
        // Detecta la salida (y si hay DAC) antes de que exista un stream, para que
        // Ajustes pueda bloquear los modos exclusivos desde el primer frame.
        AudioRoute.init(this)
        com.kukosin.app.eq.EqManager.init(this)
        prewarmArtwork()
    }

    /**
     * Precarga en la caché de **memoria** de Coil las carátulas más probables (las de
     * reproducción reciente y favoritos) apenas arranca el proceso.
     *
     * Sin esto, la primera vez que se abre una fila de la biblioteca o el reproductor, Coil
     * tiene que ir a disco (o peor: abrir el archivo y extraer la imagen embebida) antes de
     * dibujar la carátula, y eso se ve como un hueco que se rellena. Va en `Dispatchers.IO`
     * y por detrás de todo: no bloquea ni compite con el arranque de la UI.
     */
    private fun prewarmArtwork() {
        val app = this
        Thread({
            runCatching {
                val candidates = LibraryInsights.recentAndFavorites(limit = PREWARM_LIMIT)
                if (candidates.isEmpty()) return@runCatching
                val loader = coil.Coil.imageLoader(app)
                for (audioUri in candidates) {
                    val uri = com.kukosin.app.data.artwork.EmbeddedArtwork.uriFor(
                        android.net.Uri.parse(audioUri),
                    )
                    loader.enqueue(
                        coil.request.ImageRequest.Builder(app)
                            .data(uri)
                            .memoryCacheKey(uri.toString())
                            // Tamaño chico: alcanza para las miniaturas de la lista y así
                            // entran muchas más en la caché de memoria.
                            .size(256, 256)
                            .build(),
                    )
                }
            }
        }, "artwork-prewarm").apply { isDaemon = true }.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(EmbeddedArtwork.FetcherFactory(this@KukosinApp)) }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}
