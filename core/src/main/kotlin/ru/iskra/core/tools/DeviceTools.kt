package ru.iskra.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
}

object ToolSchemas {
    private fun obj(required: List<String>, props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    private fun str(desc: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }
    private fun int(desc: String, min: Int, max: Int) = buildJsonObject {
        put("type", "integer"); put("description", desc); put("minimum", min); put("maximum", max)
    }
    private fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }

    val SETTINGS_SECTIONS = listOf("main", "wifi", "bluetooth", "display", "battery", "sound", "apps", "notifications", "accessibility", "internet")
    val GLOBAL_ACTIONS = listOf("home", "back", "recents", "notifications", "quick_settings", "lock_screen", "screenshot")

    val openApp = ToolSpec(ToolNames.OPEN_APP, "Открыть установленное приложение по названию.",
        obj(listOf("name"), mapOf("name" to str("Название приложения, как на рабочем столе"))))
    val setAlarm = ToolSpec(ToolNames.SET_ALARM, "Поставить будильник в системных часах.",
        obj(listOf("hour", "minute"), mapOf("hour" to int("Час 0-23", 0, 23), "minute" to int("Минуты 0-59", 0, 59), "label" to str("Подпись"))))
    val setTimer = ToolSpec(ToolNames.SET_TIMER, "Запустить таймер в системных часах.",
        obj(listOf("seconds"), mapOf("seconds" to int("Длительность в секундах", 1, 86_400), "label" to str("Подпись"))))
    val openCamera = ToolSpec(ToolNames.OPEN_CAMERA, "Открыть камеру для фото.", obj(emptyList(), emptyMap()))
    val flashlight = ToolSpec(ToolNames.FLASHLIGHT, "Включить или выключить фонарик.",
        obj(listOf("on"), mapOf("on" to bool("true — включить, false — выключить"))))
    val volume = ToolSpec(ToolNames.VOLUME, "Изменить громкость медиа.",
        obj(listOf("direction"), mapOf("direction" to str("Направление", listOf("up", "down", "mute", "unmute")))))
    val openSettings = ToolSpec(ToolNames.OPEN_SETTINGS, "Открыть раздел системных настроек.",
        obj(listOf("section"), mapOf("section" to str("Раздел", SETTINGS_SECTIONS))))
    val globalAction = ToolSpec(ToolNames.GLOBAL_ACTION,
        "Системная навигация через службу специальных возможностей (если владелец её включил).",
        obj(listOf("action"), mapOf("action" to str("Действие", GLOBAL_ACTIONS))))
    val webSearch = ToolSpec(ToolNames.WEB_SEARCH, "Открыть поиск в браузере по запросу.",
        obj(listOf("query"), mapOf("query" to str("Поисковый запрос"))))
    val dial = ToolSpec(ToolNames.DIAL, "Открыть номеронабиратель с номером (звонок совершает сам владелец).",
        obj(listOf("number"), mapOf("number" to str("Телефонный номер"))))
    val remember = ToolSpec(ToolNames.REMEMBER,
        "Сохранить в долговременную память важный устойчивый факт о владельце или его предпочтение. " +
            "Не сохраняй пароли, номера карт, мимолётные детали и чужие секреты.",
        obj(listOf("text", "kind"), mapOf(
            "text" to str("Короткая формулировка от третьего лица, например «Владельца зовут Аня»"),
            "kind" to str("Тип", listOf("fact", "preference")),
            "importance" to int("Важность 1-5", 1, 5),
        )))
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
