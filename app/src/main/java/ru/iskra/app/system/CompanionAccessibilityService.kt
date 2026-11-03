package ru.iskra.app.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import ru.iskra.app.diag.Diag

/**
 * Minimal accessibility service: only global actions (home/back/recents/...). It is configured with
 * canRetrieveWindowContent=false, so it cannot read what is on screen — by design for the MVP.
 *
 * Its connect/disconnect moments go to the diagnostics journal: on HyperOS the switch in settings can
 * stay on while the system no longer runs the service (after an app update or a background kill).
 */
class CompanionAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Diag.i("access", "Служба спецвозможностей подключена системой")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.w("access", "Система отключила службу спецвозможностей (unbind)")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    enum class State {
        /** Switched off in system settings. */
        OFF,
        /** On, and the system runs it: global actions work. */
        CONNECTED,
        /** On in settings, but the system does not run it now. Toggling it off and on fixes it. */
        ENABLED_NOT_CONNECTED,
    }

    companion object {
        @Volatile private var instance: CompanionAccessibilityService? = null
        fun isConnected() = instance != null
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        /** Whether the owner enabled the service in system settings (even if not yet bound). */
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        /** Whether the accessibility manager itself lists the service as running. */
        fun isActiveInSystem(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
        }

        fun state(ctx: Context): State = when {
            isConnected() -> State.CONNECTED
            isEnabled(ctx) -> State.ENABLED_NOT_CONNECTED
            else -> State.OFF
        }

        /** One line for diagnostics: the three independent signals. */
        fun describe(ctx: Context): String =
            "в настройках: ${if (isEnabled(ctx)) "вкл" else "выкл"}, " +
                "у системы: ${if (isActiveInSystem(ctx)) "активна" else "не активна"}, " +
                "в приложении: ${if (isConnected()) "подключена" else "не подключена"}"
    }
}
