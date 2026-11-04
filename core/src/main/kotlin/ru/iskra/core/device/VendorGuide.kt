package ru.iskra.core.device

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

/** One thing the owner does by hand. [button] opens [screens] (the app adds the details-page fallback). */
data class GuideStep(val text: String, val button: String? = null, val screens: List<ScreenRef> = emptyList())

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
    private val recentsLock = GuideStep("В недавних приложениях задержи палец на карточке Искры (или потяни её вниз) и закрепи замком.")

    fun steps(v: Vendor): List<GuideStep> = when (v) {
        Vendor.XIAOMI -> listOf(
            GuideStep("Автозапуск: включи для Искры.", "Автозапуск", listOf(
                ScreenRef("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), miuiPermEditor)),
            GuideStep("Контроль активности (экономия батареи) → «Нет ограничений».", "Батарея", listOf(
                ScreenRef("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", packageExtra = "package_name"))),
            GuideStep("Другие разрешения → «Всплывающие окна в фоне» и «Отображать на экране блокировки».", "Другие разрешения", listOf(miuiPermEditor)),
            recentsLock,
        )
        Vendor.SAMSUNG -> listOf(
            GuideStep("Батарея → «Ограничения фона» → «Приложения, которые никогда не переходят в спящий режим» → добавь Искру.", "Батарея", listOf(
                ScreenRef("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ScreenRef("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"))),
            GuideStep("Там же выключи «Переводить неиспользуемые приложения в спящий режим» — или оставь, если Искра уже в списке исключений."),
            GuideStep("Приложения → Искра → Батарея → «Без ограничений»."),
        )
        Vendor.HUAWEI -> listOf(
            GuideStep("Батарея → «Запуск приложений» → Искра: выключи «Управлять автоматически» и включи автозапуск, косвенный запуск и работу в фоне.", "Запуск приложений", listOf(
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))),
            recentsLock,
            GuideStep("Без сервисов Google голосовой ввод в чате может не работать — включи «Слух» в настройках: он распознаёт речь прямо на телефоне."),
        )
        Vendor.HONOR -> listOf(
            GuideStep("Батарея → «Запуск приложений» → Искра: выключи «Управлять автоматически» и включи все три переключателя.", "Запуск приложений", listOf(
                ScreenRef("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ScreenRef("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))),
            recentsLock,
        )
        Vendor.OPPO -> listOf(
            GuideStep("Автозапуск: Приложения → «Автозапуск» → включи для Искры.", "Автозапуск", listOf(
                ScreenRef("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ScreenRef("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ScreenRef("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))),
            GuideStep("Приложения → Искра → Батарея → «Разрешить фоновую активность» (на новых версиях — «Без ограничений»)."),
            recentsLock,
        )
        Vendor.ONEPLUS -> listOf(
            GuideStep("Батарея → «Оптимизация батареи» → Искра → «Не оптимизировать».", "Запуск в фоне", listOf(
                ScreenRef("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))),
            GuideStep("Приложения → Искра → Батарея → «Разрешить фоновую активность»."),
            recentsLock,
        )
        Vendor.VIVO -> listOf(
            GuideStep("Автозапуск (i Manager → Приложения → Автозапуск) → включи для Искры.", "Автозапуск", listOf(
                ScreenRef("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ScreenRef("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ScreenRef("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))),
            GuideStep("Батарея → «Высокое энергопотребление в фоне» → разреши Искре."),
            recentsLock,
        )
        Vendor.TRANSSION -> listOf(
            GuideStep("Phone Master → «Управление автозапуском» → включи для Искры.", "Автозапуск", listOf(
                ScreenRef("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"))),
            GuideStep("Приложения → Искра → Батарея → «Без ограничений»."),
            recentsLock,
        )
        Vendor.ASUS -> listOf(
            GuideStep("Mobile Manager → «Автозапуск» → разреши Искре.", "Автозапуск", listOf(
                ScreenRef("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                ScreenRef("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))),
            GuideStep("Приложения → Искра → Батарея → «Без ограничений»."),
        )
        Vendor.MEIZU -> listOf(
            GuideStep("Безопасность → Разрешения → Искра → «Работа в фоне».", "Разрешения", listOf(
                ScreenRef(action = "com.meizu.safe.security.SHOW_APPSEC", packageExtra = "packageName"))),
        )
        Vendor.NEAR_STOCK -> listOf(
            GuideStep("Приложения → Искра → Батарея → «Без ограничений» (если такой пункт есть)."),
        )
    }
}
