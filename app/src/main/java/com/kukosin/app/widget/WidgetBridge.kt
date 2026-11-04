package com.kukosin.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import com.kukosin.app.R
import java.io.File
import kotlin.concurrent.thread

/**
 * Puente entre la reproducción y lo que vive **fuera** de la app: el widget del launcher y
 * el botón de ajustes rápidos.
 *
 * Los dos leen el mismo estado, guardado en preferencias: así el widget se puede dibujar
 * aunque el servicio esté muerto y el sistema lo actualice cuando quiera (por ejemplo
 * después de reiniciar el teléfono).
 *
 * Los botones no hablan con ExoPlayer directamente: despachan **eventos de media**. La
 * sesión de Media3 ya está escuchando esos eventos, así que el control funciona aunque el
 * proceso de la app se haya recreado y sin mantener un `MediaController` conectado.
 */
object WidgetBridge {

    private const val PREFS = "widget_state"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ART_URI = "art_uri"
    private const val KEY_PLAYING = "playing"
    private const val COVER_FILE = "widget_cover.png"

    const val ACTION_TOGGLE = "com.kukosin.app.widget.TOGGLE"
    const val ACTION_NEXT = "com.kukosin.app.widget.NEXT"
    const val ACTION_PREVIOUS = "com.kukosin.app.widget.PREVIOUS"
    const val ACTION_OPEN = "com.kukosin.app.widget.OPEN"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun title(context: Context): String? =
        prefs(context).getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() }

    fun artist(context: Context): String =
        prefs(context).getString(KEY_ARTIST, "").orEmpty()

    fun isPlaying(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAYING, false)

    /** ¿Hay algo cargado? Sirve para decidir si el tile abre la app o pausa. */
    fun hasTrack(context: Context): Boolean = title(context) != null

    /**
     * Publica el estado actual. Se llama en cada cambio de pista y de reproducción; es
     * barato a propósito (preferencias + un `updateAppWidget` por widget en pantalla).
     */
    fun update(
        context: Context,
        title: String?,
        artist: String?,
        artUri: Uri?,
        isPlaying: Boolean,
    ) {
        val app = context.applicationContext
        prefs(app).edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_ARTIST, artist)
            .putString(KEY_ART_URI, artUri?.toString())
            .putBoolean(KEY_PLAYING, isPlaying)
            .apply()
        refresh(app)
        if (artUri != null) loadCover(app, artUri)
    }

    /** Redibuja todos los widgets colocados. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        for (id in ids) {
            runCatching { manager.updateAppWidget(id, build(context)) }
        }
    }

    fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_player)
        val title = title(context)
        val playing = isPlaying(context)
        views.setTextViewText(
            R.id.widget_title,
            title ?: context.getString(R.string.widget_nothing_playing),
        )
        views.setTextViewText(R.id.widget_artist, artist(context))
        views.setImageViewResource(
            R.id.widget_toggle,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        val cover = coverFile(context)
        val bitmap = if (cover.exists()) {
            runCatching { BitmapFactory.decodeFile(cover.absolutePath) }.getOrNull()
        } else {
            null
        }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_cover, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        }

        views.setOnClickPendingIntent(R.id.widget_toggle, broadcast(context, ACTION_TOGGLE, 11))
        views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 12))
        views.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREVIOUS, 13))
        views.setOnClickPendingIntent(R.id.widget_cover, openIntent(context, 14))
        views.setOnClickPendingIntent(R.id.widget_title, openIntent(context, 15))
        views.setOnClickPendingIntent(R.id.widget_artist, openIntent(context, 16))
        return views
    }

    private fun coverFile(context: Context) = File(context.filesDir, COVER_FILE)

    /**
     * Extrae la carátula embebida en un hilo aparte.
     *
     * `MediaMetadataRetriever` y no `loadThumbnail`: el primero existe desde API 10 y lee
     * la imagen embebida tal cual, mientras que `loadThumbnail` necesita API 29 y depende
     * del proveedor de contenido. Si el archivo no tiene arte o la URI es remota, queda el
     * ícono de la app: mejor eso que un hueco.
     */
    private fun loadCover(context: Context, artUri: Uri) {
        val cached = prefs(context).getString(KEY_ART_URI, null)
        val file = coverFile(context)
        if (cached == artUri.toString() && file.exists()) return
        thread(name = "widget-cover", isDaemon = true) {
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, artUri)
                    val bytes = retriever.embeddedPicture
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
            runCatching {
                if (bitmap == null) {
                    file.delete()
                } else {
                    val scaled = scale(bitmap)
                    file.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                }
                refresh(context)
            }
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val max = 320
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= max || largest == 0) return bitmap
        val ratio = max.toFloat() / largest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun dispatchKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerWidgetProvider::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun openIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
