package ru.iskra.app.system

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import ru.iskra.core.device.GuideStep
import ru.iskra.core.device.ScreenRef
import ru.iskra.core.device.Vendor
import ru.iskra.core.device.VendorGuide
import ru.iskra.core.power.DeviceClass
import ru.iskra.core.power.DeviceClassifier
import ru.iskra.core.power.PowerState

/** Opening the right system screens on phones of every maker (with fallbacks), and what the phone is. */
object DeviceHelper {
    val vendor: Vendor by lazy { VendorGuide.detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }
    val isXiaomi: Boolean get() = vendor == Vendor.XIAOMI

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openOverlaySettings(ctx: Context) = start(ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        appDetails(ctx))

    fun openAccessibilitySettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppDetails(ctx: Context) = start(ctx, appDetails(ctx))

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Opens the battery-optimisation list (not the direct-exemption dialog, which Play restricts). */
    fun openBatterySettings(ctx: Context) = start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(ctx))

    /** The system voice settings, where a Russian voice can be downloaded. */
    fun openTtsSettings(ctx: Context) = start(ctx, Intent("com.android.settings.TTS_SETTINGS"),
        Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))

    /** Steps for this phone's maker (docs: dontkillmyapp.com), see [VendorGuide]. */
    val guide: List<GuideStep> get() = VendorGuide.steps(vendor)

    /** Opens the step's screen; private firmware screens change, so the chain ends at the app's details. */
    fun open(ctx: Context, step: GuideStep): Boolean = start(ctx, *(step.screens.map { intent(ctx, it) } + appDetails(ctx)).toTypedArray())

    /** The maker's autostart / app-launch screen, if this phone has one. */
    fun openAutostart(ctx: Context): Boolean {
        val step = guide.firstOrNull { it.autostart } ?: return false
        return open(ctx, step)
    }

    private fun intent(ctx: Context, r: ScreenRef): Intent {
        val i = if (r.action != null) Intent(r.action) else Intent()
        if (r.pkg != null && r.cls != null) i.component = ComponentName(r.pkg!!, r.cls!!)
        r.packageExtra?.let { i.putExtra(it, ctx.packageName) }
        return i
    }

    // ------------------------------------------------------------------ what this phone is

    fun totalRamMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
    }

    @Volatile private var cachedClass: DeviceClass? = null

    fun deviceClass(ctx: Context): DeviceClass = cachedClass ?: run {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        DeviceClassifier.classify(totalRamMb(ctx), Runtime.getRuntime().availableProcessors(), am.isLowRamDevice)
            .also { cachedClass = it }
    }

    /** Battery level, charging and Android's battery saver, from the sticky broadcast (no receiver, no permission). */
    fun powerState(ctx: Context): PowerState {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val b = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return PowerState(pct, plugged, pm.isPowerSaveMode)
    }

    /** Android's "remove animations" (accessibility) — then she moves calmly. */
    fun animationsOff(ctx: Context): Boolean =
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    private fun start(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return false
    }
}
