package com.kukosin.app.widget

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.kukosin.app.R

/**
 * Botón de ajustes rápidos.
 *
 * Si ya hay algo sonando (o pausado) el toque alterna play/pausa sin abrir la app; si no
 * hay nada cargado, abre la app para elegir música. El icono y el estado salen del mismo
 * lugar que el widget, así que nunca se contradicen.
 */
@RequiresApi(Build.VERSION_CODES.N)
class PlayerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (WidgetBridge.hasTrack(this)) {
            WidgetBridge.dispatchKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            // Media3 publica el nuevo estado enseguida; con un respiro alcanza para que el
            // tile muestre el icono correcto sin tener que esperar al siguiente `onStartListening`.
            updateTile()
        } else {
            openApp()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val playing = WidgetBridge.isPlaying(this)
        tile.label = getString(R.string.tile_label)
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val icon = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            tile.icon = Icon.createWithResource(this, icon)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = WidgetBridge.title(this) ?: getString(R.string.widget_nothing_playing)
        }
        runCatching { tile.updateTile() }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivityAndCollapse(launch) }
    }
}
