package ru.iskra.app.tools

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
            ToolOutcome(false, "на телефоне нет приложения для этого")
        } catch (e: SecurityException) {
            ToolOutcome(false, "Android запретил это действие")
        }
    }
}

private class OpenAppTool(ctx: Context) : IntentTool(ctx) {
    override val spec: ToolSpec get() = ToolSchemas.openApp
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "открыть приложение «${args.str("name").orEmpty()}»"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val query = args.str("name")?.take(60) ?: return ToolOutcome(false, "не указано приложение")
        val pm = ctx.packageManager
        val apps = withContext(Dispatchers.IO) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo != null && it.activityInfo.packageName != ctx.packageName }
        }
        val labels = apps.map { it.loadLabel(pm).toString() }
        val idx = AppMatcher.best(query, labels) ?: return ToolOutcome(false, "не нашла приложение «$query»")
        val info = apps[idx].activityInfo
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return launch(intent, "открыла ${labels[idx]}")
    }
}

private class AlarmTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.setAlarm
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "будильник на %02d:%02d".format(args.int("hour") ?: 0, args.int("minute") ?: 0)
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val h = args.int("hour")?.takeIf { it in 0..23 } ?: return ToolOutcome(false, "неверный час")
        val m = args.int("minute")?.takeIf { it in 0..59 } ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label")?.take(60) ?: "Искра")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, "будильник на %02d:%02d".format(h, m))
    }
}

private class TimerTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.setTimer
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "таймер на ${human(args.int("seconds") ?: 0)}"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val s = args.int("seconds")?.takeIf { it in 1..86_400 } ?: return ToolOutcome(false, "неверная длительность")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label")?.take(60) ?: "Искра")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, "таймер на ${human(s)}")
    }

    private fun human(s: Int) = when {
        s % 3600 == 0 -> "${s / 3600} ч"
        s % 60 == 0 -> "${s / 60} мин"
        s > 60 -> "${s / 60} мин ${s % 60} с"
        else -> "$s с"
    }
}

private class CameraTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.openCamera
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "открыть камеру"
    override suspend fun execute(args: JsonObject) = launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "открыла камеру")
}

private class FlashlightTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.flashlight
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = if (args.bool("on") != false) "включить фонарик" else "выключить фонарик"
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val on = args.bool("on") ?: true
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@withContext ToolOutcome(false, "у телефона нет вспышки")
        try {
            // setTorchMode does not require the CAMERA permission.
            cm.setTorchMode(id, on)
            ToolOutcome(true, if (on) "фонарик включён" else "фонарик выключен")
        } catch (e: Exception) {
            ToolOutcome(false, "камера занята другим приложением")
        }
    }
}

private class VolumeTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.volume
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = when (args.str("direction")) {
        "up" -> "сделать громче"; "down" -> "сделать тише"; "mute" -> "выключить звук"; else -> "включить звук"
    }
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (args.str("direction")) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> return@withContext ToolOutcome(false, "неизвестное направление")
        }
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            ToolOutcome(true, describe(args))
        } catch (e: SecurityException) {
            // Muting can be blocked in Do-Not-Disturb mode.
            ToolOutcome(false, "система не дала изменить громкость (режим «Не беспокоить»?)")
        }
    }
}

private class SettingsTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.openSettings
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "открыть настройки (${args.str("section") ?: "main"})"
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
        val r = launch(Intent(action), "открыла настройки")
        return if (r.ok) r else launch(Intent(Settings.ACTION_SETTINGS), "открыла общие настройки")
    }
}

private class GlobalActionTool : Tool {
    override val spec get() = ToolSchemas.globalAction
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = when (args.str("action")) {
        "home" -> "на главный экран"; "back" -> "назад"; "recents" -> "недавние приложения"
        "notifications" -> "открыть уведомления"; "quick_settings" -> "быстрые настройки"
        "lock_screen" -> "заблокировать экран"; "screenshot" -> "сделать скриншот"; else -> "системное действие"
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
        if (code < 0) return@withContext ToolOutcome(false, "это действие недоступно на этой версии Android")
        if (!CompanionAccessibilityService.isConnected()) {
            return@withContext ToolOutcome(false, "нужно включить «Искра · управление» в специальных возможностях (раздел «Разрешения» в приложении)")
        }
        if (CompanionAccessibilityService.perform(code)) ToolOutcome(true, describe(args))
        else ToolOutcome(false, "Android не выполнил действие")
    }
}

private class WebSearchTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.webSearch
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "поиск в браузере: «${args.str("query").orEmpty()}»"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val q = args.str("query")?.take(200) ?: return ToolOutcome(false, "пустой запрос")
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        return launch(Intent(Intent.ACTION_VIEW, uri), "ищу «$q»")
    }
}

/** Opens the dialer only — the owner presses "call" themselves. Still CONFIRM: wrong numbers are annoying. */
private class DialTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.dial
    override val risk = Risk.CONFIRM
    override fun describe(args: JsonObject) = "открыть набор номера ${args.str("number").orEmpty()}"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val n = args.str("number")?.filter { it.isDigit() || it == '+' }?.take(20)
        if (n.isNullOrEmpty() || n.length < 3) return ToolOutcome(false, "неверный номер")
        return launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")), "открыла набор номера $n")
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
