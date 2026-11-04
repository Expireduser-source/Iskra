package ru.iskra.app.diag

import ru.iskra.core.i18n.tr
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import ru.iskra.core.diag.PowerSample
import ru.iskra.core.diag.PowerStats

/**
 * Battery level and Iskra's CPU time every 5 min while the overlay runs, plus at screen on/off.
 * Reads only the sticky battery broadcast (no permission). The diagnostics screen shows the drain per hour
 * for the last stretch without charging; the journal gets one line every 15 min.
 */
@SuppressLint("StaticFieldLeak") // only the application context is kept
object PowerWatch {
    val stats = PowerStats()
    private const val PERIOD_MS = 5 * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var look: () -> String = { "?" }
    private var count = 0
    private val tick = object : Runnable {
        override fun run() { sample(null); handler.postDelayed(this, PERIOD_MS) }
    }

    fun start(ctx: Context, look: () -> String) {
        app = ctx.applicationContext; this.look = look
        handler.removeCallbacks(tick)
        sample(tr("старт", "start"))
        handler.postDelayed(tick, PERIOD_MS)
    }

    fun stop() { sample(tr("стоп", "stop")); handler.removeCallbacks(tick); app = null }

    /** [event]: a reason to also write this reading to the journal (screen on/off, start, stop). */
    fun sample(event: String?) {
        val ctx = app ?: return
        val s = runCatching {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
                (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val screen = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            PowerSample(SystemClock.elapsedRealtime(), level * 100f / scale, charging, screen, look(), Process.getElapsedCpuTime())
        }.getOrNull() ?: return
        if (s.levelPct < 0f) return
        stats.add(s)
        count++
        if (event != null || count % 3 == 0) {
            Diag.i("power", buildString {
                if (event != null) append(event).append(": ")
                append(tr("батарея ${s.levelPct.toInt()} %", "battery ${s.levelPct.toInt()} %")).append(if (s.charging) tr(" (заряжается)", " (charging)") else "")
                append(tr(", экран ", ", screen ")).append(if (s.screenOn) tr("вкл", "on") else tr("выкл", "off"))
                append(tr(", CPU Искры ${s.cpuMs / 1000} с, вид ${s.look}", ", Iskra CPU ${s.cpuMs / 1000} s, look ${s.look}"))
                summary()?.let { append(" · ").append(it) }
            })
        }
    }

    fun summary(): String? = stats.summary()?.describe()
}
