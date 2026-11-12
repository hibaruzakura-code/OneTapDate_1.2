package com.example.datebutton

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class DateTileService : TileService() {

    private val prefs get() = getSharedPreferences(DateFormats.PREFS, MODE_PRIVATE)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val service = DateButtonService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "ユーザー補助の設定で「ワンタップデイト」をオンにしてください",
                Toast.LENGTH_LONG
            ).show()
            refreshTile()
            return
        }
        val newVisible = !prefs.getBoolean(DateFormats.KEY_VISIBLE, true)
        prefs.edit().putBoolean(DateFormats.KEY_VISIBLE, newVisible).apply()
        service.setButtonVisible(newVisible)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val serviceOn = DateButtonService.instance != null
        val visible = serviceOn && prefs.getBoolean(DateFormats.KEY_VISIBLE, true)
        tile.state = if (visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ワンタップデイト"
        tile.icon = Icon.createWithResource(
            this,
            if (visible) R.drawable.ic_tile_on else R.drawable.ic_tile_off
        )
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !serviceOn -> "サービス停止中"
                visible -> "ON"
                else -> "OFF"
            }
        }
        tile.updateTile()
    }
}
