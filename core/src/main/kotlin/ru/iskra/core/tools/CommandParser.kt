package ru.iskra.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic on-device understanding of frequent commands. Works without network/LLM, is
 * instant, and costs nothing. Anything it doesn't recognise goes to the LLM.
 */
object CommandParser {

    sealed interface Parsed {
        data class Tool(val name: String, val args: JsonObject) : Parsed
        data class Remember(val text: String) : Parsed
        data object ListMemory : Parsed
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private val numberWords = mapOf(
        "одну" to 1, "один" to 1, "одна" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "пятнадцать" to 15,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "полчаса" to 30,
    )

    fun parse(input: String): Parsed? {
        val raw = input.trim()
        val t = raw.lowercase().replace('ё', 'е').trimEnd('.', '!', '?', ' ')
            .removePrefix("искра,").removePrefix("искра").trim().removePrefix("пожалуйста").trim()
        if (t.isEmpty()) return null

        Regex("""^запомни[:,]?\s+(.+)$""", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            // Keep original casing of the remembered text.
            val idx = raw.lowercase().indexOf("запомни") + "запомни".length
            val text = raw.substring(idx).trimStart(':', ',', ' ').trim()
            return if (text.isNotEmpty()) Parsed.Remember(text) else null
        }
        if (t in setOf("что ты помнишь", "что ты обо мне помнишь", "твоя память", "покажи память")) return Parsed.ListMemory

        // Alarm: "поставь будильник на 7:30", "разбуди меня в 6", "будильник на 07 15"
        Regex("""^(?:поставь |заведи |установи )?(?:будильник|разбуди меня)(?: на| в)? (\d{1,2})(?:[:. ](\d{2}))?(?: утра| вечера| часов| час| часа)?$""")
            .find(t)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (t.contains("вечера") && h in 1..11) h += 12
                if (h !in 0..23 || min !in 0..59) return null
                return Parsed.Tool(ToolNames.SET_ALARM, args { put("hour", h); put("minute", min) })
            }

        // Timer: "таймер на 5 минут", "поставь таймер на 30 секунд", "таймер на полчаса", "таймер 2 часа"
        Regex("""^(?:поставь |заведи |запусти )?таймер(?: на)? (\d{1,4}|[а-я]+)(?: (секунд[уы]?|сек|минут[уы]?|мин|час(?:а|ов)?))?$""")
            .find(t)?.let { m ->
                val numText = m.groupValues[1]
                val n = numText.toIntOrNull() ?: numberWords[numText] ?: return null
                val unit = m.groupValues[2]
                val seconds = when {
                    numText == "полчаса" -> 1800
                    unit.startsWith("сек") -> n
                    unit.startsWith("час") -> n * 3600
                    else -> n * 60
                }
                if (seconds !in 1..86_400) return null
                return Parsed.Tool(ToolNames.SET_TIMER, args { put("seconds", seconds) })
            }

        when (t) {
            "открой камеру", "камера", "сфотографируй", "включи камеру" -> return Parsed.Tool(ToolNames.OPEN_CAMERA, args {})
            "включи фонарик", "фонарик", "посвети" -> return Parsed.Tool(ToolNames.FLASHLIGHT, args { put("on", true) })
            "выключи фонарик" -> return Parsed.Tool(ToolNames.FLASHLIGHT, args { put("on", false) })
            "громче", "сделай громче", "прибавь громкость" -> return Parsed.Tool(ToolNames.VOLUME, args { put("direction", "up") })
            "тише", "сделай тише", "убавь громкость" -> return Parsed.Tool(ToolNames.VOLUME, args { put("direction", "down") })
            "выключи звук", "без звука" -> return Parsed.Tool(ToolNames.VOLUME, args { put("direction", "mute") })
            "включи звук" -> return Parsed.Tool(ToolNames.VOLUME, args { put("direction", "unmute") })
            "домой", "на главный экран", "рабочий стол" -> return global("home")
            "назад" -> return global("back")
            "недавние", "недавние приложения" -> return global("recents")
            "уведомления", "покажи уведомления", "открой шторку" -> return global("notifications")
            "быстрые настройки" -> return global("quick_settings")
            "заблокируй экран", "заблокируй телефон" -> return global("lock_screen")
            "сделай скриншот", "скриншот", "снимок экрана" -> return global("screenshot")
            "открой настройки", "настройки" -> return settings("main")
        }

        Regex("""^(?:открой )?настройки (wi-?fi|вай-?фай|блютуз|bluetooth|экрана|батареи|звука|приложений|уведомлений|интернета)$""").find(t)?.let {
            val section = when (it.groupValues[1]) {
                "wi-fi", "wifi", "вай-фай", "вайфай" -> "wifi"
                "блютуз", "bluetooth" -> "bluetooth"
                "экрана" -> "display"
                "батареи" -> "battery"
                "звука" -> "sound"
                "приложений" -> "apps"
                "уведомлений" -> "notifications"
                else -> "internet"
            }
            return settings(section)
        }

        Regex("""^(?:найди|загугли|поищи)(?: в интернете)? (.{2,200})$""").find(t)?.let {
            return Parsed.Tool(ToolNames.WEB_SEARCH, args { put("query", it.groupValues[1]) })
        }
        Regex("""^(?:позвони|набери)(?: на номер| номер)? (\+?[\d\s()-]{3,20})$""").find(t)?.let {
            return Parsed.Tool(ToolNames.DIAL, args { put("number", it.groupValues[1].filter { c -> c.isDigit() || c == '+' }) })
        }
        Regex("""^(?:открой|запусти|включи) (?:приложение )?(.{2,60})$""").find(t)?.let {
            val name = it.groupValues[1].trim()
            if (name !in setOf("настройки", "камеру", "фонарик", "звук")) {
                return Parsed.Tool(ToolNames.OPEN_APP, args { put("name", name) })
            }
        }
        return null
    }

    private fun global(action: String) = Parsed.Tool(ToolNames.GLOBAL_ACTION, buildJsonObject { put("action", action) })
    private fun settings(section: String) = Parsed.Tool(ToolNames.OPEN_SETTINGS, buildJsonObject { put("section", section) })
}
