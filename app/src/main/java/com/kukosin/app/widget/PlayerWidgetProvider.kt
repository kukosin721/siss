package com.kukosin.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Widget de reproducción: carátula, título, artista y tres botones.
 *
 * No dibuja nada por su cuenta: [WidgetBridge] arma las vistas con el último estado
 * publicado por [com.kukosin.app.playback.PlaybackService], y los botones despachan
 * eventos de media a la sesión activa.
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // `refresh` reescribe todos los widgets colocados; es lo mismo que hace el servicio
        // cuando cambia la pista, así que no hay dos caminos distintos de dibujo.
        WidgetBridge.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetBridge.ACTION_TOGGLE -> {
                WidgetBridge.dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                // El estado real llega por el servicio un instante después; este refresco
                // es para que el ícono responda al toque aunque el binder tarde.
                WidgetBridge.refresh(context)
            }
            WidgetBridge.ACTION_NEXT -> WidgetBridge.dispatchKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            WidgetBridge.ACTION_PREVIOUS -> WidgetBridge.dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            WidgetBridge.ACTION_OPEN -> {
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(launch) }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}
