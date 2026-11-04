package ru.iskra.app.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ru.iskra.app.IskraApp
import ru.iskra.core.i18n.tr

/** Quick Settings tile «Искра»: one tap shows or hides her over other apps. */
class IskraTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, ToggleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = IskraApp.instance.settings.load().overlayEnabled && DeviceHelper.canDrawOverlays(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = tr("Искра", "Iskra")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) tr("на экране", "on screen") else tr("скрыта", "hidden")
        tile.contentDescription = tr("Искра на экране", "Iskra on screen")
        tile.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile after a change made elsewhere. */
        fun refresh(ctx: Context) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, IskraTileService::class.java)) }
        }
    }
}
