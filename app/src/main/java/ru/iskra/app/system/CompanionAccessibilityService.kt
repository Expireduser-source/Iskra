package ru.iskra.app.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service: only global actions (home/back/recents/...). It is configured with
 * canRetrieveWindowContent=false, so it cannot read what is on screen — by design for the MVP.
 */
class CompanionAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    companion object {
        @Volatile private var instance: CompanionAccessibilityService? = null
        fun isConnected() = instance != null
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        /** Whether the owner enabled the service in system settings (even if not yet bound). */
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
