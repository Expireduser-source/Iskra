package ru.iskra.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.iskra.core.brain.BrainNames
import ru.iskra.core.brain.CurrencyWords
import ru.iskra.core.skills.ReminderParser
import ru.iskra.core.skills.SkillNames

/**
 * Deterministic on-device understanding of frequent commands, in Russian and English ([EnglishCommands]). Works without network/LLM, is
 * instant, and costs nothing. Anything it doesn't recognise goes to the LLM.
 */
object CommandParser {

    sealed interface Parsed {
        data class Tool(val name: String, val args: JsonObject) : Parsed
        data class Remember(val text: String) : Parsed
        data object ListMemory : Parsed
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    internal val numberWords = mapOf(
        "одну" to 1, "один" to 1, "одна" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "пятнадцать" to 15,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "полчаса" to 30, "пару" to 2,
    )

    /** «семь», «двенадцать» as the hour of an alarm. */
    private val hourWords = mapOf("час" to 1, "один" to 1, "два" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6,
        "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12)
    private val minuteWords = mapOf("ноль ноль" to 0, "десять" to 10, "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "сорок пять" to 45, "пятьдесят" to 50)
    private val alarm = Regex("""^(?:(?:поставь|заведи|установи|включи)(?:те)? )?(?:мне )?(?:будильник|разбуди(?:те)?(?: меня)?|разбудишь(?: меня)?)""" +
        """(?: на завтра| завтра)?(?: утром)?(?: на| в| к)? (\d{1,2}|""" + "один|два|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|час" +
        """)(?:(?:[:. ]| и )(\d{2}|ноль ноль|десять|пятнадцать|двадцать|тридцать|сорок пять|сорок|пятьдесят))?""" +
        """(?: (утра|вечера|дня|ночи)| часов| час| часа)?(?: завтра)?(?: утром)?$""")

    fun parse(input: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Parsed? {
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

        skills(raw, t, now)?.let { return it }
        CurrencyWords.parse(raw)?.let { return Parsed.Tool(BrainNames.CURRENCY, it) }

        // Alarm: "поставь будильник на 7:30", "разбуди меня в 6", "будильник на 07 15", «разбуди меня завтра в семь»,
        // «поставь будильник на завтра на 6:30», «разбудишь меня в 7 утра?» — the next such time on the clock.
        alarm.find(t)?.let { m ->
            var h = m.groupValues[1].let { it.toIntOrNull() ?: hourWords[it] } ?: return null
            val min = m.groupValues[2].let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: minuteWords[it] } ?: return null
            when (m.groupValues[3]) {
                "вечера" -> if (h in 1..11) h += 12
                "дня" -> if (h in 1..6) h += 12
                "ночи" -> if (h == 12) h = 0
            }
            if (h !in 0..23 || min !in 0..59) return null
            return Parsed.Tool(ToolNames.SET_ALARM, args { put("hour", h); put("minute", min) })
        }

        // Timer: "таймер на 5 минут", "поставь таймер на 30 секунд", "таймер на полчаса", "таймер 2 часа", «засеки 5 минут»
        Regex("""^(?:(?:поставь |заведи |запусти )?таймер|засеки(?: мне)?(?: время)?)(?: на)? (\d{1,4}|[а-я]+)(?: (секунд[уы]?|сек|минут[уы]?|мин|час(?:а|ов)?))?$""")
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
        return EnglishCommands.parse(raw, now)
    }

    /** Notes, reminders, weather (stage 3). [raw] keeps the owner's casing for the note text. */
    private fun skills(raw: String, t: String, now: java.time.LocalDateTime): Parsed? {
        val lowerRaw = raw.lowercase().replace('ё', 'е')
        Regex("""^(?:(?:запиши|добавь|сделай|создай)\s+)?заметк[уа]?[:,]?\s+(.+)$|^запиши[:,]?\s+(.+)$""").find(t)?.let { m ->
            val g = (m.groups[1] ?: m.groups[2])!!.value
            val at = lowerRaw.indexOf(g)
            val text = (if (at >= 0) raw.substring(at, at + g.length) else g).trim().trimEnd('.', '!', ' ')
            if (text.length >= 2) return Parsed.Tool(SkillNames.ADD_NOTE, args { put("text", text) })
        }
        when (t) {
            "что в заметках", "мои заметки", "покажи заметки", "прочитай заметки", "заметки", "какие заметки" ->
                return Parsed.Tool(SkillNames.LIST_NOTES, args {})
            "удали все заметки", "очисти заметки", "удали заметки" -> return Parsed.Tool(SkillNames.DELETE_NOTES, args { put("which", "all") })
            "удали последнюю заметку", "удали заметку" -> return Parsed.Tool(SkillNames.DELETE_NOTES, args { put("which", "last") })
            "какие напоминания", "мои напоминания", "покажи напоминания", "напоминания", "какие есть напоминания" ->
                return Parsed.Tool(SkillNames.LIST_REMINDERS, args {})
            "отмени все напоминания", "удали все напоминания", "удали напоминания" ->
                return Parsed.Tool(SkillNames.CANCEL_REMINDERS, args { put("which", "all") })
            "отмени напоминание", "удали напоминание", "отмени ближайшее напоминание" ->
                return Parsed.Tool(SkillNames.CANCEL_REMINDERS, args { put("which", "next") })
        }
        // «Напомни…», and the other ways to ask: «Напомнишь мне через час…?», «Можешь напомнить…», «Через полчаса надо
        // вынуть пирог, не дай мне забыть». Without a time it goes to the model.
        ReminderParser.canonical(raw)?.let { asked ->
            val r = ReminderParser.parse(asked, now) ?: return null
            return Parsed.Tool(SkillNames.SET_REMINDER, args {
                put("text", r.text)
                if (r.inMinutes != null) put("in_minutes", r.inMinutes.toInt()) else put("at", r.at.toString().take(16))
            })
        }
        // «какая погода завтра», «какая сегодня погода», «погода в Казани на завтра»
        Regex("""^(?:какая |что с |как там |как с |скажи )?(?:(сегодня|завтра) )?(?:будет )?погод[аеуы]?(?: на)?(?: (сегодня|завтра))?(?: в (?:городе )?([а-я][а-я -]{1,40}?))?(?: (?:на )?(сегодня|завтра))?$""").find(t)?.let { m ->
            return weather(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[4] }, m.groupValues[3])
        }
        Regex("""^(?:нужен ли|брать ли|взять ли) зонт(?:ик)?(?: (сегодня|завтра))?$""").find(t)?.let { return weather(it.groupValues[1], "") }
        Regex("""^(?:сколько (?:сейчас )?градусов|холодно ли|тепло ли)(?: на улице| сегодня)?$""").find(t)?.let { return weather("", "") }
        return null
    }

    private fun weather(day: String, city: String) = Parsed.Tool(SkillNames.WEATHER, args {
        put("day", if (day == "завтра") "tomorrow" else "today")
        if (city.isNotBlank()) put("city", city.trim())
    })

    private fun global(action: String) = Parsed.Tool(ToolNames.GLOBAL_ACTION, buildJsonObject { put("action", action) })
    private fun settings(section: String) = Parsed.Tool(ToolNames.OPEN_SETTINGS, buildJsonObject { put("section", section) })
}
