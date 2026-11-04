package ru.iskra.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.iskra.core.skills.ReminderParser
import ru.iskra.core.skills.SkillNames
import java.time.LocalDateTime

/**
 * The English half of [CommandParser]: the same commands, the same tool calls. Russian and English are
 * both understood whatever the UI language is (different alphabets, so they never clash).
 */
internal object EnglishCommands {
    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, "couple" to 2,
    )

    /** Lower case, plain apostrophes, no polite/address words around the command. */
    fun normalize(input: String): String = input.trim().lowercase()
        .replace('’', '\'').replace('‘', '\'')
        .trimEnd('.', '!', '?', ' ')
        .replace(Regex("^(?:hey |ok |okay )?(?:iskra|spark)[,!]?\\s+"), "")
        .replace(Regex("^(?:please|can you|could you|would you)\\s+"), "")
        .replace(Regex("^please\\s+"), "")
        .replace(Regex("[,\\s]+please$"), "")
        .trim()

    private const val NUM = """(\d{1,4}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|sixty|ninety|a couple of|couple of)"""
    private const val UNIT = """(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)"""
    private val durationPart = Regex("""$NUM\s*-?\s*$UNIT\b""")

    /** "5 minutes", "an hour and a half", "1 hour 30 minutes", "half an hour" → seconds. */
    fun durationSeconds(text: String): Int? {
        val t = text.trim().removePrefix("for ").trim()
        when (t) {
            "half an hour", "half hour", "a half hour" -> return 1800
            "an hour and a half", "one and a half hours", "1.5 hours" -> return 5400
            "a minute and a half" -> return 90
        }
        var rest = t
        var total = 0
        var any = false
        while (rest.isNotEmpty()) {
            val m = durationPart.matchAt(rest, 0) ?: return null
            val n = number(m.groupValues[1]) ?: return null
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            any = true
            rest = rest.substring(m.range.last + 1).trim().removePrefix(",").trim().removePrefix("and ").trim()
        }
        return if (any) total else null
    }

    fun number(word: String): Int? = word.toIntOrNull() ?: numberWords[word.removeSuffix(" of").removePrefix("a ").trim()]

    fun parse(raw: String, now: LocalDateTime): CommandParser.Parsed? {
        val t = normalize(raw)
        if (t.isEmpty() || !t.any { it in 'a'..'z' }) return null

        // Memory: "remember: my sister's birthday is May 5", "remember that I like green tea".
        Regex("""^remember(?: that|:|,)?\s+(.+)$""", RegexOption.DOT_MATCHES_ALL).find(t)?.let { m ->
            if (m.groupValues[1].startsWith("to ")) return@let
            val at = raw.lowercase().indexOf(m.groupValues[1].take(12))
            val text = (if (at >= 0) raw.substring(at) else m.groupValues[1]).trim().trimEnd('.', '!', ' ')
            return if (text.isNotEmpty()) CommandParser.Parsed.Remember(text) else null
        }
        if (t in setOf("what do you remember", "what do you remember about me", "what do you know about me", "show memory",
                "show your memory", "your memory")) return CommandParser.Parsed.ListMemory

        skills(raw, t, now)?.let { return it }

        // Alarm: "set an alarm for 7:30", "wake me up at 6 am", "alarm at 7 pm", "set alarm for 6:45 in the morning".
        Regex("""^(?:set (?:an |the |my )?alarm|alarm|wake me(?: up)?)(?: for| at| to)? (\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?: o'?clock)?(?: in the (morning|afternoon|evening)| at night)?$""")
            .find(t)?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ampm = m.groupValues[3].replace(".", "")
                val part = m.groupValues[4]
                if ((ampm == "pm" || part == "afternoon" || part == "evening" || (t.endsWith("at night") && h in 7..11)) && h in 1..11) h += 12
                if (ampm == "am" && h == 12) h = 0
                if (h !in 0..23 || min !in 0..59) return null
                return CommandParser.Parsed.Tool(ToolNames.SET_ALARM, args { put("hour", h); put("minute", min) })
            }

        // Timer: "set a timer for 5 minutes", "timer 30 seconds", "a 10 minute timer", "timer for half an hour".
        val timer = Regex("""^(?:set |start )?(?:a |the )?timer (?:for )?(.+)$""").find(t)?.groupValues?.get(1)
            ?: Regex("""^(?:set |start )?(?:a |an )?(.+?) timer$""").find(t)?.groupValues?.get(1)
        if (timer != null) {
            val seconds = durationSeconds(timer.replace(Regex("""(\d)\s*-\s*(minute|second|hour)"""), "$1 $2"))
            if (seconds != null && seconds in 1..86_400) return CommandParser.Parsed.Tool(ToolNames.SET_TIMER, args { put("seconds", seconds) })
        }

        when (t) {
            "open the camera", "open camera", "camera", "take a photo", "take a picture", "start the camera" ->
                return CommandParser.Parsed.Tool(ToolNames.OPEN_CAMERA, args {})
            "turn on the flashlight", "turn the flashlight on", "flashlight on", "flashlight", "torch", "torch on",
            "turn on the torch", "turn on flashlight", "light" -> return CommandParser.Parsed.Tool(ToolNames.FLASHLIGHT, args { put("on", true) })
            "turn off the flashlight", "turn the flashlight off", "flashlight off", "torch off", "turn off the torch",
            "turn off flashlight" -> return CommandParser.Parsed.Tool(ToolNames.FLASHLIGHT, args { put("on", false) })
            "louder", "volume up", "turn it up", "turn up the volume", "turn the volume up" ->
                return CommandParser.Parsed.Tool(ToolNames.VOLUME, args { put("direction", "up") })
            "quieter", "volume down", "turn it down", "turn down the volume", "turn the volume down" ->
                return CommandParser.Parsed.Tool(ToolNames.VOLUME, args { put("direction", "down") })
            "mute", "silence", "sound off", "mute the sound" -> return CommandParser.Parsed.Tool(ToolNames.VOLUME, args { put("direction", "mute") })
            "unmute", "sound on", "turn on the sound" -> return CommandParser.Parsed.Tool(ToolNames.VOLUME, args { put("direction", "unmute") })
            "go home", "home", "home screen", "go to the home screen" -> return global("home")
            "back", "go back" -> return global("back")
            "recents", "recent apps", "show recent apps" -> return global("recents")
            "notifications", "show notifications", "open notifications", "open the notification shade" -> return global("notifications")
            "quick settings", "open quick settings" -> return global("quick_settings")
            "lock the screen", "lock screen", "lock the phone", "lock phone" -> return global("lock_screen")
            "take a screenshot", "screenshot", "take screenshot" -> return global("screenshot")
            "open settings", "settings", "open the settings" -> return settings("main")
        }

        Regex("""^(?:open )?(?:the )?(wi-?fi|bluetooth|display|screen|battery|sound|apps?|notification|notifications|internet|network|accessibility) settings$""")
            .find(t)?.let {
                val section = when (it.groupValues[1]) {
                    "wifi", "wi-fi" -> "wifi"
                    "bluetooth" -> "bluetooth"
                    "display", "screen" -> "display"
                    "battery" -> "battery"
                    "sound" -> "sound"
                    "app", "apps" -> "apps"
                    "notification", "notifications" -> "notifications"
                    "accessibility" -> "accessibility"
                    else -> "internet"
                }
                return settings(section)
            }

        Regex("""^(?:search(?: the web| the internet| online)?(?: for)?|google|look up) (.{2,200})$""").find(t)?.let {
            return CommandParser.Parsed.Tool(ToolNames.WEB_SEARCH, args { put("query", it.groupValues[1]) })
        }
        Regex("""^(?:call|dial)(?: the number)? (\+?[\d\s()-]{3,20})$""").find(t)?.let {
            return CommandParser.Parsed.Tool(ToolNames.DIAL, args { put("number", it.groupValues[1].filter { c -> c.isDigit() || c == '+' }) })
        }
        Regex("""^(?:open|launch|start)(?: the)?(?: app)? (.{2,60})$""").find(t)?.let {
            val name = it.groupValues[1].trim().removeSuffix(" app").trim()
            if (name !in setOf("settings", "camera", "flashlight", "torch", "timer")) {
                return CommandParser.Parsed.Tool(ToolNames.OPEN_APP, args { put("name", name) })
            }
        }
        return null
    }

    private fun skills(raw: String, t: String, now: LocalDateTime): CommandParser.Parsed? {
        // Notes: "note: buy milk", "take a note buy milk", "make a note that ...", "write down ...".
        Regex("""^(?:(?:take|make|add|write)\s+(?:a\s+)?note(?:\s+that)?|note|write down)[:,]?\s+(.+)$""").find(t)?.let { m ->
            val g = m.groupValues[1]
            val at = raw.lowercase().replace('’', '\'').lastIndexOf(g)
            val text = (if (at >= 0) raw.substring(at, at + g.length) else g).trim().trimEnd('.', '!', ' ')
            if (text.length >= 2) return CommandParser.Parsed.Tool(SkillNames.ADD_NOTE, args { put("text", text) })
        }
        when (t) {
            "what's in my notes", "what is in my notes", "my notes", "show notes", "show my notes", "read my notes",
            "read notes", "notes", "what notes do i have" -> return CommandParser.Parsed.Tool(SkillNames.LIST_NOTES, args {})
            "delete all notes", "clear notes", "clear my notes", "delete my notes", "delete notes" ->
                return CommandParser.Parsed.Tool(SkillNames.DELETE_NOTES, args { put("which", "all") })
            "delete the last note", "delete last note", "delete the latest note", "delete note", "delete that note" ->
                return CommandParser.Parsed.Tool(SkillNames.DELETE_NOTES, args { put("which", "last") })
            "my reminders", "what reminders do i have", "show reminders", "show my reminders", "reminders", "list reminders" ->
                return CommandParser.Parsed.Tool(SkillNames.LIST_REMINDERS, args {})
            "cancel all reminders", "delete all reminders", "clear reminders" ->
                return CommandParser.Parsed.Tool(SkillNames.CANCEL_REMINDERS, args { put("which", "all") })
            "cancel the reminder", "cancel reminder", "cancel the next reminder", "delete the reminder", "delete reminder" ->
                return CommandParser.Parsed.Tool(SkillNames.CANCEL_REMINDERS, args { put("which", "next") })
        }
        if (t.startsWith("remind me") || t.startsWith("set a reminder")) {
            val r = ReminderParser.parse(raw, now) ?: return null
            return CommandParser.Parsed.Tool(SkillNames.SET_REMINDER, args {
                put("text", r.text)
                if (r.inMinutes != null) put("in_minutes", r.inMinutes.toInt()) else put("at", r.at.toString().take(16))
            })
        }
        // Weather: "what's the weather", "weather tomorrow", "what's the weather like in london today", "how's the weather".
        Regex("""^(?:what'?s|what is|how'?s|how is|tell me)?\s*(?:the )?weather(?: like)?(?: (today|tomorrow))?(?: in ([a-z][a-z .'-]{1,40}?))?(?: (today|tomorrow))?$""")
            .find(t)?.let { m -> return weather(m.groupValues[1].ifEmpty { m.groupValues[3] }, m.groupValues[2]) }
        Regex("""^(?:do i need an umbrella|should i take an umbrella|will it rain|is it going to rain)(?: (today|tomorrow))?$""")
            .find(t)?.let { return weather(it.groupValues[1], "") }
        Regex("""^(?:how cold is it|how warm is it|how hot is it|what'?s the temperature|what is the temperature|is it cold|is it warm)(?: outside| out| today)?$""")
            .find(t)?.let { return weather("", "") }
        return null
    }

    private fun weather(day: String, city: String) = CommandParser.Parsed.Tool(SkillNames.WEATHER, args {
        put("day", if (day == "tomorrow") "tomorrow" else "today")
        if (city.isNotBlank()) put("city", city.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    })

    private fun global(action: String) = CommandParser.Parsed.Tool(ToolNames.GLOBAL_ACTION, buildJsonObject { put("action", action) })
    private fun settings(section: String) = CommandParser.Parsed.Tool(ToolNames.OPEN_SETTINGS, buildJsonObject { put("section", section) })
}

/** "remind me in 10 minutes to call mom", "remind me at 6:30 pm to buy bread", "remind me tomorrow morning about the meeting". */
internal object EnglishReminders {
    private val prefix = Regex("""^(?:(?:hey |ok )?(?:iskra|spark)[, ]+)?(?:please\s+)?(?:remind me|set a reminder)(?:\s+please)?[,:]?\s*""")
    private val relative = Regex("""(?:^|\s)in\s+(half an hour|an hour and a half|a minute and a half|(?:\d{1,4}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|fifty|sixty|ninety|a couple of)\s*-?\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|h|days?|weeks?)(?:\s+(?:and\s+)?(?:\d{1,4}|[a-z-]+)\s*(?:minutes?|mins?))?)(?=\s|$|[,.!])""")
    private val dayWord = Regex("""(?:^|\s)(today|tomorrow|the day after tomorrow|tonight)(?=\s|$|[,.!])""")
    private val clock = Regex("""(?:^|\s)(?:at|by)\s+(noon|midnight|(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?:\s*o'?clock)?)(?:\s+(?:in the (morning|afternoon|evening)|(at night)))?(?=\s|$|[,.!])""")
    private val partOfDay = Regex("""(?:^|\s)(?:in the |this )?(morning|afternoon|evening)(?=\s|$|[,.!])""")
    private val leadingJunk = Regex("""^(?:[,:\s]|to\b|about\b|that\b|of\b|please\b)+\s*""")

    fun parse(input: String, now: LocalDateTime): ReminderParser.Result? {
        val raw = input.trim().trimEnd('.', '!').replace('’', '\'')
        val t = raw.lowercase()
        val p = prefix.find(t) ?: return null
        var body = t.substring(p.range.last + 1)
        var original = raw.substring(p.range.last + 1)
        fun cut(range: IntRange) {
            body = body.removeRange(range); original = original.removeRange(range)
        }

        var at: LocalDateTime? = null
        var inMinutes: Long? = null
        relative.find(body)?.let { m ->
            val seconds = durationWithDays(m.groupValues[1]) ?: return null
            val minutes = maxOf(1L, seconds / 60)
            if (minutes > 525_600) return null
            at = now.plusSeconds(minutes * 60L); inMinutes = minutes
            cut(m.range)
        }
        if (at == null) {
            val day = dayWord.find(body)
            val time = clock.find(body)
            val part = partOfDay.find(body)
            if (day == null && time == null && part == null) return null
            val dayName = day?.groupValues?.get(1)
            val date = when (dayName) {
                "tomorrow" -> now.toLocalDate().plusDays(1)
                "the day after tomorrow" -> now.toLocalDate().plusDays(2)
                else -> now.toLocalDate()
            }
            var hour: Int
            var minute = 0
            var explicit = false
            if (time != null) {
                when (time.groupValues[1]) {
                    "noon" -> { hour = 12; explicit = true }
                    "midnight" -> { hour = 0; explicit = true }
                    else -> {
                        hour = time.groupValues[2].toInt(); minute = time.groupValues[3].ifEmpty { "0" }.toInt()
                        val ampm = time.groupValues[4].replace(".", "")
                        val dayPart = time.groupValues[5].ifEmpty { part?.groupValues?.get(1).orEmpty() }
                        val night = time.groupValues[6].isNotEmpty() || dayName == "tonight"
                        when {
                            ampm == "pm" -> { if (hour in 1..11) hour += 12; explicit = true }
                            ampm == "am" -> { if (hour == 12) hour = 0; explicit = true }
                            dayPart == "afternoon" || dayPart == "evening" -> { if (hour in 1..11) hour += 12; explicit = true }
                            dayPart == "morning" -> explicit = true
                            night -> { if (hour in 6..11) hour += 12; if (hour == 12) hour = 0; explicit = true }
                        }
                    }
                }
            } else {
                hour = when (part?.groupValues?.get(1)) {
                    "afternoon" -> 13
                    "evening" -> 19
                    else -> if (dayName == "tonight") 20 else 9
                }
                explicit = true
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            var candidate = date.atTime(hour, minute)
            if (day == null && !candidate.isAfter(now)) {
                // "at 7" said at 10:00 means 19:00 today; at 20:00 it means 7:00 tomorrow.
                candidate = if (!explicit && hour in 1..11 && date.atTime(hour + 12, minute).isAfter(now)) date.atTime(hour + 12, minute)
                else candidate.plusDays(1)
            }
            at = candidate
            // Remove the matched parts (the longest wins where they overlap), rightmost first so ranges stay valid.
            listOfNotNull(day?.range, time?.range, part?.range).sortedByDescending { it.last - it.first }
                .fold(emptyList<IntRange>()) { acc, r -> if (acc.any { a -> r.first <= a.last && a.first <= r.last }) acc else acc + listOf(r) }
                .sortedByDescending { it.first }.forEach(::cut)
        }
        val result = at ?: return null
        if (!result.isAfter(now) || result.isAfter(now.plusDays(366))) return null
        val text = original.replace(leadingJunk, "").trim().trimEnd(',', ' ').ifBlank { "reminder" }
        return ReminderParser.Result(text, result, inMinutes)
    }

    private fun durationWithDays(d: String): Long? {
        Regex("""^(\d{1,4}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty)\s*(days?|weeks?)$""").find(d)?.let {
            val n = EnglishCommands.number(it.groupValues[1]) ?: return null
            return n.toLong() * (if (it.groupValues[2].startsWith("week")) 7 * 86_400 else 86_400)
        }
        return EnglishCommands.durationSeconds(d.replace(Regex("""(\d)\s*-\s*(minute|second|hour)"""), "$1 $2"))?.toLong()
    }
}
