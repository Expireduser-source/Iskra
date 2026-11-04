package ru.iskra.app.system

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.app.overlay.OverlayService
import ru.iskra.app.ui.MainActivity
import ru.iskra.core.i18n.tr

/**
 * Shows or hides Iskra over other apps, then closes (no UI). Started by the Quick Settings tile and the
 * «На экране» launcher shortcut: an activity in front is what lets Android start her foreground service
 * (and the microphone for hearing) from there.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = IskraApp.instance
        if (!DeviceHelper.canDrawOverlays(this)) {
            Toast.makeText(this, tr("Сначала разреши «Поверх других приложений»", "First allow “Display over other apps”"), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_PERMISSIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val on = !app.settings.load().overlayEnabled
            app.settings.save(app.settings.load().copy(overlayEnabled = on))
            if (on) OverlayService.start(this) else OverlayService.stop(this)
            Diag.i("overlay", (if (on) tr("Показана", "Shown") else tr("Убрана", "Hidden")) + tr(" плиткой или ярлыком", " from the tile or shortcut"))
            IskraTileService.refresh(this)
        }
        finish()
    }
}
