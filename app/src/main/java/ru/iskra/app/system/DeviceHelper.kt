package ru.iskra.app.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Opening the right system screens, including Xiaomi/HyperOS-specific ones (with fallbacks). */
object DeviceHelper {
    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true) || Build.BRAND.equals("POCO", true)

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

    /**
     * MIUI/HyperOS "Autostart" and "other permissions" screens. These are private components that
     * can change between firmware versions, hence the fallback chain ending at app details.
     */
    fun openXiaomiAutostart(ctx: Context) = start(ctx,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName),
        appDetails(ctx))

    fun openXiaomiOtherPermissions(ctx: Context) = start(ctx,
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName),
        appDetails(ctx))

    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    private fun start(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
