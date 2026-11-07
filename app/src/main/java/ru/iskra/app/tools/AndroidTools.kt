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
import android.app.SearchManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.view.KeyEvent
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
import ru.iskra.core.tools.timerSeconds

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
    override fun describe(args: JsonObject) = tr("таймер на ${human(timerSeconds(args) ?: 0)}", "timer for ${human(timerSeconds(args) ?: 0)}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val s = timerSeconds(args) ?: return ToolOutcome(false, tr("неверная длительность", "wrong duration"))
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

/** Battery, network, storage and sound mode — so "сколько заряда?" or "я на Wi-Fi?" get a real answer. */
private class DeviceStatusTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.deviceStatus
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = tr("состояние телефона", "phone status")
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        val loc = java.util.Locale.getDefault()
        val parts = ArrayList<String>()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) parts += tr("заряд $level%", "battery $level%") + if (bm.isCharging) tr(", заряжается", ", charging") else ""
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        parts += when {
            caps == null -> tr("нет сети", "no network")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> tr("сеть: Wi-Fi", "network: Wi-Fi")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> tr("сеть: мобильный интернет", "network: mobile data")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> tr("сеть: Ethernet", "network: Ethernet")
            else -> tr("сеть подключена", "network connected")
        }
        runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            parts += String.format(loc, tr("память: свободно %.1f из %.0f ГБ", "storage: %.1f of %.0f GB free"), st.availableBytes / 1e9, st.totalBytes / 1e9)
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        parts += when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> tr("звонок: без звука", "ringer: silent")
            AudioManager.RINGER_MODE_VIBRATE -> tr("звонок: вибрация", "ringer: vibrate")
            else -> tr("звонок: со звуком", "ringer: sound on")
        }
        parts += "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
        ToolOutcome(true, parts.joinToString("; "))
    }
}

/** Opens the calendar with the event filled in; the owner saves it (no calendar permission needed). */
private class CalendarEventTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.calendarEvent
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("событие: «${args.str("title").orEmpty().take(50)}» ${args.str("start").orEmpty()}", "event: “${args.str("title").orEmpty().take(50)}” ${args.str("start").orEmpty()}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val title = args.str("title")?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: return ToolOutcome(false, tr("нет названия", "no title"))
        val raw = args.str("start")?.trim().orEmpty()
        val allDay = raw.length == 10
        val start = runCatching { if (allDay) java.time.LocalDate.parse(raw).atStartOfDay() else java.time.LocalDateTime.parse(raw.take(16)) }.getOrNull()
            ?: return ToolOutcome(false, tr("непонятное время начала", "unclear start time"))
        val end = args.str("end")?.let { runCatching { java.time.LocalDateTime.parse(it.trim().take(16)) }.getOrNull() }
            ?.takeIf { it.isAfter(start) } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val zone = java.time.ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        args.str("location")?.trim()?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it.take(100)) }
        return launch(intent, tr("открыла календарь с событием «$title» — проверь и сохрани", "opened the calendar with “$title” — check and save it"))
    }
}

/** Any maps app via geo:, or the map website if none is installed. */
private class NavigateTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.navigate
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("карты: ${args.str("destination").orEmpty().take(60)}", "maps: ${args.str("destination").orEmpty().take(60)}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val dest = args.str("destination")?.trim()?.take(150)?.takeIf { it.length >= 2 } ?: return ToolOutcome(false, tr("куда — не сказано", "no destination"))
        val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), tr("открыла карты: $dest", "opened maps: $dest"))
        if (r.ok) return r
        val web = tr("https://yandex.ru/maps/?text=", "https://www.google.com/maps/search/?api=1&query=") + Uri.encode(dest)
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(web)), tr("открыла карту в браузере: $dest", "opened the map in the browser: $dest"))
    }
}

/** Media keys for whatever is playing (no permission needed). */
private class MediaTool(private val ctx: Context) : Tool {
    override val spec get() = ToolSchemas.media
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = when (args.str("action")) {
        "pause" -> tr("пауза", "pause"); "play" -> tr("продолжить", "play"); "next" -> tr("следующий трек", "next track")
        "previous" -> tr("предыдущий трек", "previous track"); else -> tr("пауза/продолжить", "play/pause")
    }
    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.Main) {
        val code = when (args.str("action")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        ToolOutcome(true, describe(args))
    }
}

/** "Включи Queen": the system's play-from-search, or the music service's website. */
private class PlayMusicTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.playMusic
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("музыка: ${args.str("query").orEmpty().take(60)}", "music: ${args.str("query").orEmpty().take(60)}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val q = args.str("query")?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: return ToolOutcome(false, tr("что включить — не сказано", "nothing to play"))
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
        val r = launch(search, tr("включаю «$q»", "playing “$q”"))
        if (r.ok) return r
        val web = tr("https://music.yandex.ru/search?text=", "https://music.youtube.com/search?q=") + Uri.encode(q)
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(web)), tr("открыла поиск музыки: $q", "opened a music search: $q"))
    }
}

/** A prepared SMS (with a number) or the share sheet; the owner presses "send" themselves. */
private class MessageTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.message
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("сообщение: «${args.str("text").orEmpty().take(50)}»", "message: “${args.str("text").orEmpty().take(50)}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text")?.take(1000)?.takeIf { it.isNotBlank() } ?: return ToolOutcome(false, tr("пустое сообщение", "empty message"))
        val phone = args.str("phone")?.filter { it.isDigit() || it == '+' }?.take(20)?.takeIf { it.length >= 3 }
        val intent = if (phone != null) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text)
        else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Отправить через", "Send with"))
        return launch(intent, tr("подготовила сообщение — осталось нажать «Отправить»", "prepared the message — just press “Send”"))
    }
}

private class OpenUrlTool(ctx: Context) : IntentTool(ctx) {
    override val spec get() = ToolSchemas.openUrl
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("открыть ${args.str("url").orEmpty().take(80)}", "open ${args.str("url").orEmpty().take(80)}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val url = args.str("url")?.trim()?.take(500).orEmpty()
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return ToolOutcome(false, tr("открываю только https-ссылки", "I only open https links"))
        return launch(Intent(Intent.ACTION_VIEW, uri), tr("открыла ${uri.host}", "opened ${uri.host}"))
    }
}

object AndroidTools {
    fun all(ctx: Context): List<Tool> {
        val app = ctx.applicationContext
        return listOf(
            OpenAppTool(app), AlarmTool(app), TimerTool(app), CameraTool(app), FlashlightTool(app),
            VolumeTool(app), SettingsTool(app), GlobalActionTool(), WebSearchTool(app), DialTool(app),
            DeviceStatusTool(app), CalendarEventTool(app), NavigateTool(app), MediaTool(app), PlayMusicTool(app),
            MessageTool(app), OpenUrlTool(app),
        )
    }
}
