package ru.iskra.core.tools

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import ru.iskra.core.llm.ToolSpec

/**
 * Names + JSON schemas of all built-in tools. Implementations live in the platform layer
 * (Android) — the core only knows their contracts, so it can parse commands and test policy.
 */
object ToolNames {
    const val OPEN_APP = "open_app"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val OPEN_CAMERA = "open_camera"
    const val FLASHLIGHT = "flashlight"
    const val VOLUME = "volume"
    const val OPEN_SETTINGS = "open_settings"
    const val GLOBAL_ACTION = "global_action"
    const val WEB_SEARCH = "web_search"
    const val DIAL = "dial"
    const val REMEMBER = "remember"
    const val DEVICE_STATUS = "device_status"
    const val CALENDAR_EVENT = "add_calendar_event"
    const val NAVIGATE = "navigate"
    const val MEDIA = "media_control"
    const val PLAY_MUSIC = "play_music"
    const val MESSAGE = "compose_message"
    const val OPEN_URL = "open_url"
}

object ToolSchemas {
    internal fun obj(required: List<String>, props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    internal fun str(desc: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }
    internal fun int(desc: String, min: Int, max: Int) = buildJsonObject {
        put("type", "integer"); put("description", desc); put("minimum", min); put("maximum", max)
    }
    internal fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
    internal fun num(desc: String) = buildJsonObject { put("type", "number"); put("description", desc) }

    val SETTINGS_SECTIONS = listOf("main", "wifi", "bluetooth", "display", "battery", "sound", "apps", "notifications", "accessibility", "internet")
    val GLOBAL_ACTIONS = listOf("home", "back", "recents", "notifications", "quick_settings", "lock_screen", "screenshot")

    val openApp get() = ToolSpec(ToolNames.OPEN_APP, tr("Открыть установленное приложение по названию.", "Open an installed app by name."),
        obj(listOf("name"), mapOf("name" to str(tr("Название приложения, как на рабочем столе", "App name as shown on the home screen")))))
    val setAlarm get() = ToolSpec(ToolNames.SET_ALARM, tr("Поставить будильник в системных часах.", "Set an alarm in the system clock."),
        obj(listOf("hour", "minute"), mapOf("hour" to int(tr("Час 0-23", "Hour 0-23"), 0, 23), "minute" to int(tr("Минуты 0-59", "Minutes 0-59"), 0, 59), "label" to str(tr("Подпись", "Label")))))
    // Minutes as well as seconds: Gemma 4 E2B, given only seconds, asked the person «Скажи мне секунды» (ARM evaluation, 0.14.0).
    val setTimer get() = ToolSpec(ToolNames.SET_TIMER, tr("Запустить таймер в системных часах: на minutes минут или seconds секунд.",
        "Start a timer in the system clock: for minutes or seconds."),
        obj(emptyList(), mapOf(
            "minutes" to num(tr("Минуты, можно дробные: 1.5", "Minutes, may be fractional: 1.5")),
            "seconds" to int(tr("Секунды — для коротких таймеров или вдобавок к минутам", "Seconds — for short timers, or added to the minutes"), 1, 86_400),
            "label" to str(tr("Подпись", "Label")))))
    val openCamera get() = ToolSpec(ToolNames.OPEN_CAMERA, tr("Открыть камеру для фото.", "Open the camera to take a photo."), obj(emptyList(), emptyMap()))
    val flashlight get() = ToolSpec(ToolNames.FLASHLIGHT, tr("Включить или выключить фонарик.", "Turn the flashlight on or off."),
        obj(listOf("on"), mapOf("on" to bool(tr("true — включить, false — выключить", "true — on, false — off")))))
    val volume get() = ToolSpec(ToolNames.VOLUME, tr("Изменить громкость медиа.", "Change the media volume."),
        obj(listOf("direction"), mapOf("direction" to str(tr("Направление", "Direction"), listOf("up", "down", "mute", "unmute")))))
    val openSettings get() = ToolSpec(ToolNames.OPEN_SETTINGS, tr("Открыть раздел системных настроек.", "Open a system settings section."),
        obj(listOf("section"), mapOf("section" to str(tr("Раздел", "Section"), SETTINGS_SECTIONS))))
    val globalAction get() = ToolSpec(ToolNames.GLOBAL_ACTION,
        tr("Системная навигация через службу специальных возможностей (если владелец её включил).", "System navigation through the accessibility service (if the owner turned it on)."),
        obj(listOf("action"), mapOf("action" to str(tr("Действие", "Action"), GLOBAL_ACTIONS))))
    val webSearch get() = ToolSpec(ToolNames.WEB_SEARCH, tr("Открыть поиск в браузере по запросу.", "Open a browser search for the query."),
        obj(listOf("query"), mapOf("query" to str(tr("Поисковый запрос", "Search query")))))
    val dial get() = ToolSpec(ToolNames.DIAL, tr("Открыть номеронабиратель с номером (звонок совершает сам владелец).", "Open the dialer with the number (the owner places the call)."),
        obj(listOf("number"), mapOf("number" to str(tr("Телефонный номер", "Phone number")))))
    val deviceStatus get() = ToolSpec(ToolNames.DEVICE_STATUS,
        tr("Состояние телефона: заряд и зарядка, сеть (Wi-Fi/мобильная), свободная память, режим звука.", "Phone status: battery and charging, network (Wi-Fi/mobile), free storage, sound mode."),
        obj(emptyList(), emptyMap()))
    val calendarEvent get() = ToolSpec(ToolNames.CALENDAR_EVENT,
        tr("Создать событие в календаре: откроется календарь с заполненными полями, владелец сохранит его сам.", "Create a calendar event: the calendar opens with the fields filled in, the owner saves it."),
        obj(listOf("title", "start"), mapOf(
            "title" to str(tr("Название", "Title")),
            "start" to str(tr("Начало, местное время ISO: 2026-11-07T15:00 (или дата 2026-11-07 для события на весь день)", "Start, local ISO time: 2026-11-07T15:00 (or a date 2026-11-07 for an all-day event)")),
            "end" to str(tr("Конец, местное время ISO (по умолчанию через час)", "End, local ISO time (default: an hour later)")),
            "location" to str(tr("Место", "Location")),
        )))
    val navigate get() = ToolSpec(ToolNames.NAVIGATE,
        tr("Открыть карты с поиском места или маршрутом до него.", "Open maps searching for a place or with a route to it."),
        obj(listOf("destination"), mapOf("destination" to str(tr("Адрес или название места", "Address or place name")))))
    val media get() = ToolSpec(ToolNames.MEDIA,
        tr("Управлять музыкой или видео, которые уже играют: пауза, продолжить, следующий, предыдущий трек.", "Control music or video that is already playing: pause, resume, next, previous track."),
        obj(listOf("action"), mapOf("action" to str(tr("Действие", "Action"), listOf("play_pause", "play", "pause", "next", "previous")))))
    val playMusic get() = ToolSpec(ToolNames.PLAY_MUSIC,
        tr("Включить музыку по запросу (исполнитель, песня, жанр) в музыкальном приложении телефона.", "Play music by a query (artist, song, genre) in the phone's music app."),
        obj(listOf("query"), mapOf("query" to str(tr("Что включить", "What to play")))))
    val message get() = ToolSpec(ToolNames.MESSAGE,
        tr("Подготовить сообщение: откроется SMS с текстом (если дан номер) или выбор приложения. Отправляет владелец сам.", "Prepare a message: opens an SMS with the text (if a number is given) or an app picker. The owner sends it."),
        obj(listOf("text"), mapOf("text" to str(tr("Текст сообщения", "Message text")), "phone" to str(tr("Номер телефона, если известен", "Phone number, if known")))))
    val openUrl get() = ToolSpec(ToolNames.OPEN_URL,
        tr("Открыть веб-страницу в браузере (только https).", "Open a web page in the browser (https only)."),
        obj(listOf("url"), mapOf("url" to str("https://…"))))
    val remember get() = ToolSpec(ToolNames.REMEMBER,
        tr("Сохранить в долговременную память важный устойчивый факт о владельце или его предпочтение. " +
            "Не сохраняй пароли, номера карт, мимолётные детали и чужие секреты.", "Save an important lasting fact about the owner or their preference to long-term memory. Don't save passwords, card numbers, passing details or other people's secrets."),
        obj(listOf("text", "kind"), mapOf(
            "text" to str(tr("Короткая формулировка от третьего лица, например «Владельца зовут Аня»", "A short third-person statement, e.g. “The owner's name is Anna”")),
            "kind" to str(tr("Тип", "Type"), listOf("fact", "preference")),
            "importance" to int(tr("Важность 1-5", "Importance 1-5"), 1, 5),
        )))
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A timer's length in seconds: minutes (may be fractional) and/or seconds; null when neither is a usable number. */
fun timerSeconds(args: JsonObject): Int? {
    val minutes = (args["minutes"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.trim()?.toDoubleOrNull() }
    val seconds = args.int("seconds")
    if (minutes == null && seconds == null) return null
    return (Math.round((minutes ?: 0.0) * 60).toInt() + (seconds ?: 0)).takeIf { it in 1..86_400 }
}
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
