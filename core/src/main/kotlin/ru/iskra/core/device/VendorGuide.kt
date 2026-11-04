package ru.iskra.core.device

import ru.iskra.core.i18n.tr

/**
 * Phone makers whose firmware stops background apps in its own way (dontkillmyapp.com). Detection
 * uses only [android.os.Build.MANUFACTURER] / BRAND, no permissions.
 */
enum class Vendor(val title: String) {
    XIAOMI("Xiaomi / Redmi / POCO"),
    SAMSUNG("Samsung"),
    HUAWEI("Huawei"),
    HONOR("Honor"),
    OPPO("OPPO / realme"),
    ONEPLUS("OnePlus"),
    VIVO("vivo / iQOO"),
    TRANSSION("Tecno / Infinix / itel"),
    ASUS("ASUS"),
    MEIZU("Meizu"),
    NEAR_STOCK("Android"),
}

/**
 * A system screen to try. Explicit components are private to the firmware and change between
 * versions, so the app tries each ref in order and ends at the app's own details page.
 * [packageExtra]: name of an extra that must carry the app's package name.
 */
data class ScreenRef(
    val pkg: String? = null,
    val cls: String? = null,
    val action: String? = null,
    val packageExtra: String? = null,
)

/** One thing the owner does by hand. [button] opens [screens] (the app adds the details-page fallback). [autostart]: the step
 * switches on autostart / app launch (the app offers it again when the firmware turned the service off). */
data class GuideStep(val text: String, val button: String? = null, val screens: List<ScreenRef> = emptyList(), val autostart: Boolean = false)

/** What to switch on so the firmware does not unload Iskra, per phone maker. Pure data, tested. */
object VendorGuide {
    fun detect(manufacturer: String, brand: String): Vendor {
        val m = "${manufacturer.lowercase()} ${brand.lowercase()}"
        fun has(vararg names: String) = names.any { m.contains(it) }
        return when {
            has("xiaomi", "redmi", "poco") -> Vendor.XIAOMI
            has("samsung") -> Vendor.SAMSUNG
            has("honor") -> Vendor.HONOR
            has("huawei") -> Vendor.HUAWEI
            has("oneplus") -> Vendor.ONEPLUS
            has("oppo", "realme") -> Vendor.OPPO
            has("vivo", "iqoo") -> Vendor.VIVO
            has("tecno", "infinix", "itel", "transsion") -> Vendor.TRANSSION
            has("asus") -> Vendor.ASUS
            has("meizu") -> Vendor.MEIZU
            else -> Vendor.NEAR_STOCK
        }
    }

    private val miuiPermEditor = ScreenRef("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity",
        action = "miui.intent.action.APP_PERM_EDITOR", packageExtra = "extra_pkgname")
    private val recentsLock get() = GuideStep(tr("В недавних приложениях задержи палец на карточке Искры (или потяни её вниз) и закрепи замком.", "In recent apps, long-press Iskra's card (or pull it down) and lock it."))

    fun steps(v: Vendor): List<GuideStep> = when (v) {
        Vendor.XIAOMI -> listOf(
            GuideStep(tr("Автозапуск: включи для Искры.", "Autostart: turn it on for Iskra."), tr("Автозапуск", "Autostart"), autostart = true, screens = listOf(
                ScreenRef("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), miuiPermEditor)),
            GuideStep(tr("Контроль активности (экономия батареи) → «Нет ограничений».", "Battery saver (activity control) → “No restrictions”."), tr("Батарея", "Battery"), listOf(
                ScreenRef("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", packageExtra = "package_name"))),
            GuideStep(tr("Другие разрешения → «Всплывающие окна в фоне» и «Отображать на экране блокировки».", "Other permissions → “Open new windows while running in the background” and “Show on Lock screen”."), tr("Другие разрешения", "Other permissions"), listOf(miuiPermEditor)),
            recentsLock,
        )
        Vendor.SAMSUNG -> listOf(
            GuideStep(tr("Батарея → «Ограничения фона» → «Приложения, которые никогда не переходят в спящий режим» → добавь Искру.", "Battery → “Background usage limits” → “Never sleeping apps” → add Iskra."), tr("Батарея", "Battery"), listOf(
                ScreenRef("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ScreenRef("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"))),
            GuideStep(tr("Приложения → Искра → Батарея → «Без ограничений».", "Apps → Iskra → Battery → “Unrestricted”.")),
        )
        Vendor.HUAWEI -> listOf(
            GuideStep(tr("Батарея → «Запуск приложений» → Искра: выключи «Управлять автоматически» и включи автозапуск, косвенный запуск и работу в фоне.", "Battery → “App launch” → Iskra: turn off “Manage automatically” and turn on auto-launch, secondary launch and run in background."), tr("Запуск приложений", "App launch"), autostart = true, screens = listOf(
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))),
            recentsLock,
            GuideStep(tr("Без сервисов Google голосовой ввод в чате может не работать — включи «Слух» в настройках: он распознаёт речь прямо на телефоне.", "Without Google services, voice input in the chat may not work — turn on “Hearing” in Settings: it recognizes speech right on the phone.")),
        )
        Vendor.HONOR -> listOf(
            GuideStep(tr("Батарея → «Запуск приложений» → Искра: выключи «Управлять автоматически» и включи все три переключателя.", "Battery → “App launch” → Iskra: turn off “Manage automatically” and turn on all three switches."), tr("Запуск приложений", "App launch"), autostart = true, screens = listOf(
                ScreenRef("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))),
            recentsLock,
        )
        Vendor.OPPO -> listOf(
            GuideStep(tr("Автозапуск: Приложения → «Автозапуск» → включи для Искры.", "Autostart: Apps → “Auto launch” → turn it on for Iskra."), tr("Автозапуск", "Autostart"), autostart = true, screens = listOf(
                ScreenRef("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ScreenRef("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ScreenRef("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))),
            GuideStep(tr("Приложения → Искра → Батарея → «Разрешить фоновую активность» (на новых версиях — «Без ограничений»).", "Apps → Iskra → Battery → “Allow background activity” (on newer versions — “Unrestricted”).")),
            recentsLock,
        )
        Vendor.ONEPLUS -> listOf(
            GuideStep(tr("Батарея → «Оптимизация батареи» → Искра → «Не оптимизировать».", "Battery → “Battery optimization” → Iskra → “Don't optimize”."), tr("Запуск в фоне", "Background launch"), listOf(
                ScreenRef("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))),
            GuideStep(tr("Приложения → Искра → Батарея → «Разрешить фоновую активность».", "Apps → Iskra → Battery → “Allow background activity”.")),
            recentsLock,
        )
        Vendor.VIVO -> listOf(
            GuideStep(tr("Автозапуск (i Manager → Приложения → Автозапуск) → включи для Искры.", "Autostart (i Manager → Apps → Autostart) → turn it on for Iskra."), tr("Автозапуск", "Autostart"), autostart = true, screens = listOf(
                ScreenRef("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ScreenRef("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ScreenRef("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))),
            GuideStep(tr("Батарея → «Высокое энергопотребление в фоне» → разреши Искре.", "Battery → “High background power consumption” → allow Iskra.")),
            recentsLock,
        )
        Vendor.TRANSSION -> listOf(
            GuideStep(tr("Phone Master → «Управление автозапуском» → включи для Искры.", "Phone Master → “Auto-start management” → turn it on for Iskra."), tr("Автозапуск", "Autostart"), autostart = true, screens = listOf(
                ScreenRef("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"))),
            GuideStep(tr("Приложения → Искра → Батарея → «Без ограничений».", "Apps → Iskra → Battery → “Unrestricted”.")),
            recentsLock,
        )
        Vendor.ASUS -> listOf(
            GuideStep(tr("Mobile Manager → «Автозапуск» → разреши Искре.", "Mobile Manager → “Auto-start” → allow Iskra."), tr("Автозапуск", "Autostart"), autostart = true, screens = listOf(
                ScreenRef("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                ScreenRef("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))),
            GuideStep(tr("Приложения → Искра → Батарея → «Без ограничений».", "Apps → Iskra → Battery → “Unrestricted”.")),
        )
        Vendor.MEIZU -> listOf(
            GuideStep(tr("Безопасность → Разрешения → Искра → «Работа в фоне».", "Security → Permissions → Iskra → “Run in background”."), tr("Разрешения", "Permissions"), listOf(
                ScreenRef(action = "com.meizu.safe.security.SHOW_APPSEC", packageExtra = "packageName"))),
        )
        Vendor.NEAR_STOCK -> listOf(
            GuideStep(tr("Приложения → Искра → Батарея → «Без ограничений» (если такой пункт есть).", "Apps → Iskra → Battery → “Unrestricted” (if there is such an item).")),
        )
    }
}
