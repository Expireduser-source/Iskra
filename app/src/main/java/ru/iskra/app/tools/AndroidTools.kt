package ru.iskra.app.tools

import ru.iskra.core.i18n.tr
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import ru.iskra.app.system.CompanionAccessibilityService
import ru.iskra.core.llm.ToolSpec
import ru.iskra.core.tools.AppMatcher
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolSchemas
import ru.iskra.core.tools.bool
import ru.iskra.core.tools.int
import ru.iskra.core.tools.str

/** Base for tools that start an activity. All run on the main thread. */
private abstract class IntentTool(protected val ctx: Context) : Tool {
    protected suspend fun launch(intent: Intent, okMessage: String): ToolOutcome = withContext(Dispatchers.Main) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolOutcome(true, okMessage)
        } catch (e: ActivityNotFoundException) {
            ToolOutcome(false, tr("на телефоне нет приложения для этого", "the phone has no app for this"))
        } catch (e: SecurityException) {
            ToolOutcome(false, tr("Android запретил это действие", "Android didn't allow this action"))
        }
    }
}

private class OpenAppTool(ctx: Context) : IntentTool(ctx) {
    override val spec: ToolSpec get() = ToolSchemas.openApp
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("открыть приложение «${args.str("name").orEmpty()}»", "open the app “${args.str("name").orEmpty()}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val query = args.str("name")?.take(60) ?: return ToolOutcome(false, tr("не указано приложение", "no app given"))
        val pm = ctx.packageManager
        val apps = withContext(Dispatchers.IO) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
        }
        val labels = apps.map { it.loadLabel(pm).toString() }
        val idx = AppMatcher.best(query, labels) ?: return ToolOutcome(false, tr("не нашла приложение «$query»", "couldn't find the app “$query”"))
        val info = apps[idx].activityInfo
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return launch(intent, tr("открыла ${labels[idx]}", "opened ${labels[idx]}"))
    }
}

private class AlarmTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.setAlarm
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("будильник на %02d:%02d", "alarm for %02d:%02d").format(args.int("hour") ?: 0, args.int("minute") ?: 0)
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val h = args.int("hour")?.takeIf { it in 0..23 } ?: return ToolOutcome(false, tr("неверный час", "wrong hour"))
        val m = args.int("minute")?.takeIf { it in 0..59 } ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label")?.take(60) ?: tr("Искра", "Iskra"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, tr("будильник на %02d:%02d", "alarm for %02d:%02d").format(h, m))
    }
}

private class TimerTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.setTimer
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("таймер на ${human(args.int("seconds") ?: 0)}", "timer for ${human(args.int("seconds") ?: 0)}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val s = args.int("seconds")?.takeIf { it in 1..86_400 } ?: return ToolOutcome(false, tr("неверная длительность", "wrong duration"))
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label")?.take(60) ?: tr("Искра", "Iskra"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, tr("таймер на ${human(s)}", "timer for ${human(s)}"))
    }

    private fun human(s: Int) = when {
        s % 3600 == 0 -> tr("${s / 3600} ч", "${s / 3600} h")
        s % 60 == 0 -> tr("${s / 60} мин", "${s / 60} min")
        s > 60 -> tr("${s / 60} мин ${s % 60} с", "${s / 60} min ${s % 60} s")
        else -> tr("$s с", "$s s")
    }
}

private class CameraTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.openCamera
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("открыть камеру", "open the camera")
    override suspend fun execute(args: JsonObject) = launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), tr("открыла камеру", "opened the camera"))
}

private class FlashlightTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.flashlight
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = if (args.bool("on") != false) tr("включить фонарик", "turn on the flashlight") else tr("выключить фонарик", "turn off the flashlight")
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val on = args.bool("on") ?: true
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@withContext ToolOutcome(false, tr("у телефона нет вспышки", "the phone has no flash"))
        try {
            // setTorchMode does not require the CAMERA permission.
            cm.setTorchMode(id, on)
            ToolOutcome(true, if (on) tr("фонарик включён", "flashlight on") else tr("фонарик выключен", "flashlight off"))
        } catch (e: Exception) {
            ToolOutcome(false, tr("камера занята другим приложением", "the camera is busy with another app"))
        }
    }
}

private class VolumeTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.volume
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = when (args.str("direction")) {
        "up" -> tr("сделать громче", "turn it up"); "down" -> tr("сделать тише", "turn it down"); "mute" -> tr("выключить звук", "mute"); else -> tr("включить звук", "unmute")
    }
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (args.str("direction")) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> return@withContext ToolOutcome(false, tr("неизвестное направление", "unknown direction"))
        }
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            ToolOutcome(true, describe(args))
        } catch (e: SecurityException) {
            // Muting can be blocked in Do-Not-Disturb mode.
            ToolOutcome(false, tr("система не дала изменить громкость (режим «Не беспокоить»?)", "the system didn't allow changing the volume (Do Not Disturb?)"))
        }
    }
}

private class SettingsTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.openSettings
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("открыть настройки (${args.str("section") ?: "main"})", "open settings (${args.str("section") ?: "main"})")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val action = when (args.str("section")) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val r = launch(Intent(action), tr("открыла настройки", "opened settings"))
        return if (r.ok) r else launch(Intent(Settings.ACTION_SETTINGS), tr("открыла общие настройки", "opened the main settings"))
    }
}

private class GlobalActionTool : Tool {
    override val spec get() = ToolSchemas.globalAction
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = when (args.str("action")) {
        "home" -> tr("на главный экран", "go to the home screen"); "back" -> tr("назад", "back"); "recents" -> tr("недавние приложения", "recent apps")
        "notifications" -> tr("открыть уведомления", "open notifications"); "quick_settings" -> tr("быстрые настройки", "quick settings")
        "lock_screen" -> tr("заблокировать экран", "lock the screen"); "screenshot" -> tr("сделать скриншот", "take a screenshot"); else -> tr("системное действие", "system action")
    }
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val code = when (args.str("action")) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (code < 0) return@withContext ToolOutcome(false, tr("это действие недоступно на этой версии Android", "this action isn't available on this Android version"))
        if (!CompanionAccessibilityService.isConnected()) {
            return@withContext ToolOutcome(false, tr("нужно включить «Искра · управление» в специальных возможностях (раздел «Разрешения» в приложении)", "you need to turn on “Iskra · control” in Accessibility (the “Permissions” tab in the app)"))
        }
        if (CompanionAccessibilityService.perform(code)) ToolOutcome(true, describe(args))
        else ToolOutcome(false, tr("Android не выполнил действие", "Android didn't perform the action"))
    }
}

private class WebSearchTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.webSearch
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("поиск в браузере: «${args.str("query").orEmpty()}»", "browser search: “${args.str("query").orEmpty()}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val q = args.str("query")?.take(200) ?: return ToolOutcome(false, tr("пустой запрос", "empty query"))
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        return launch(Intent(Intent.ACTION_VIEW, uri), tr("ищу «$q»", "searching for “$q”"))
    }
}

/** Opens the dialer only — the owner presses "call" themselves. Still CONFIRM: wrong numbers are annoying. */
private class DialTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.dial
    override val risk = Risk.CONFIRM
    override fun describe(args: JsonObject) = tr("открыть набор номера ${args.str("number").orEmpty()}", "open the dialer with ${args.str("number").orEmpty()}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val n = args.str("number")?.filter { it.isDigit() || it == '+' }?.take(20)
        if (n.isNullOrEmpty() || n.length < 3) return ToolOutcome(false, tr("неверный номер", "wrong number"))
        return launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")), tr("открыла набор номера $n", "opened the dialer with $n"))
    }
}

object AndroidTools {
    fun all(ctx: Context): List<Tool> {
        val app = ctx.applicationContext
        return listOf(
            OpenAppTool(app), AlarmTool(app), TimerTool(app), CameraTool(app), FlashlightTool(app),
            VolumeTool(app), SettingsTool(app), GlobalActionTool(), WebSearchTool(app), DialTool(app),
        )
    }
}
