package ru.iskra.core.agent

import ru.iskra.core.i18n.tr
import ru.iskra.core.skills.SkillNames
import ru.iskra.core.tools.ToolNames

/**
 * A model on the phone saying it did something it didn't: «Я поставлю напоминание… Через полчаса ты получишь
 * уведомление» with no tool called (Gemma 4 E4B in the 0.14.0 evaluation). Gemma 4 gets one more try, told to call the
 * tool; if nothing is done after all, she says so — a reminder that never comes is worse than none.
 */
object ActionPromise {
    private val ru = Regex("""(?iu)(?<!\p{L})(?:постав(?:лю|ила|ил)|ставлю|завед[уё]|завела|зав[её]л|завожу|запиш[уе]|записала|записываю|""" +
        """включ(?:у|ила)|включаю|открою|открыла|открываю)(?!\p{L})[^.!?\n]{0,40}?(?<!\p{L})(?:таймер|будильник|напоминани|заметк|фонарик|приложени)\p{L}*|""" +
        """(?<!\p{L})напомню\s+(?:тебе|вам)(?!\p{L})|(?<!\p{L})(?:получишь|придёт|придет)\s+(?:\p{L}+\s+)?(?:уведомлени|напоминани|сигнал)\p{L}*""")
    private val en = Regex("""(?i)\b(?:i(?:'ll| will)\s+(?:set|start|add|remind|turn on|open|write)|i(?:'ve| have)\s+(?:set|started|added|turned on|opened|written)|""" +
        """setting (?:a|the|your) (?:timer|alarm|reminder)|you'll get a (?:reminder|notification))\b""")

    /**
     * An offer of the very thing that was asked: on «Сварю яйца всмятку — засеки, сколько нужно» E4B answered «Я могу
     * поставить таймер на 3 минуты, чтобы яйца всмятку сварились.» and called nothing (ARM evaluation, 0.15.0).
     */
    private val offerRu = Regex("""(?iu)(?<!\p{L})(?:могу|можно|давай)\s+(?:тебе\s+)?(?:(?:поставить|завести|включить|записать|открыть)(?!\p{L})""" +
        """[^.!?\n]{0,40}?(?<!\p{L})(?:таймер|будильник|напоминани|заметк|фонарик|приложени)\p{L}*|засечь(?!\p{L}))""")
    private val offerEn = Regex("""(?i)\b(?:i can|i could|shall i|let me)\s+(?:set|start|add|turn on|open|write)\b[^.!?\n]{0,40}?\b(?:timer|alarm|reminder|note|flashlight|app)""")
    /** The person asked for an action: «засеки», «поставь», «напомни», "set", "remind me"… */
    private val askedRu = Regex("""(?iu)(?<!\p{L})(?:засеки|поставь|поставьте|заведи|включи|запиши|напомни|открой|разбуди)(?!\p{L})""")
    private val askedEn = Regex("""(?i)\b(?:set|start|remind me|turn on|open|write down|note down|wake me)\b""")

    /**
     * A timer asked for in words the phrase parser can't read for a time: «Сварю яйца всмятку — засеки, сколько нужно».
     * E2B answered «Ты хочешь, чтобы я поставила таймер?» in three evaluations in a row; with the call begun for it, the
     * model only chooses the minutes — the usual time for the thing, as its instructions say.
     */
    fun wantsTimer(text: String): Boolean = timerRu.containsMatchIn(text) || timerEn.containsMatchIn(text)

    private val timerRu = Regex("""(?iu)(?<!\p{L})засеки(?:те)?(?!\p{L})|(?<!\p{L})(?:поставь|заведи|запусти|включи)(?:те)?(?!\p{L})[^.!?\n]{0,30}?(?<!\p{L})таймер""")
    private val timerEn = Regex("""(?i)\b(?:set|start|put\s+on)\b[^.!?\n]{0,30}?\btimer\b""")

    /**
     * A time and nothing else, as the answer to «Сколько минут засечь?»: «7 минут», «7», «минут 10», «полчаса», «1.5 часа»,
     * "10 minutes". Number words are digits already ([ru.iskra.core.brain.NumberWords.digitize]). Seconds, or null.
     */
    fun duration(text: String): Int? {
        val t = text.lowercase().replace('ё', 'е').trim().trimEnd('.', '!', ' ').removePrefix("на ").removePrefix("for ").trim()
        when (t) {
            "полчаса", "half an hour" -> return 1800
            "час", "1 час", "an hour", "one hour", "1 hour" -> return 3600
        }
        val m = Regex("""^(\d+(?:[.,]\d+)?)\s*(минут\p{L}*|мин|секунд\p{L}*|сек|час\p{L}*|minutes?|mins?|seconds?|secs?|hours?)?$""").find(t)
            ?: Regex("""^(?:минут\p{L}*|minutes?)\s+(\d+)()$""").find(t) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val s = when {
            unit.startsWith("сек") || unit.startsWith("sec") -> n
            unit.startsWith("час") || unit.startsWith("hour") -> n * 3600
            else -> n * 60
        }
        return Math.round(s).toInt().takeIf { it in 1..86_400 }
    }

    /**
     * A sentence of a phone model's reply that offers one of its actions when the person's message ([asked]) gave no reason
     * for it: «Может, включим фонарик?» to a sad person, «Может, тебе нужно что-то записать о подготовке?» to «волнуюсь»,
     * "maybe set a timer if you want to practice" to "I'm learning the guitar" (E2B, ARM evaluations 0.14.0–0.16.1). Its
     * instructions already say not to; the sentence is neither shown nor spoken.
     */
    fun unaskedOffer(sentence: String, asked: String): Boolean {
        // Asked what she can do: telling it is the answer (E2B's "I can do lots of things, like setting timers…").
        if (asks(asked) || capabilities.containsMatchIn(asked)) return false
        val offer = (offerAny.find(sentence) ?: return false).value
        val tool = toolWords.entries.firstOrNull { (_, re) -> re.containsMatchIn(offer) }?.key ?: return false
        return topics[tool]?.containsMatchIn(asked) != true
    }

    /** [text] without its [unaskedOffer] sentences — unless that would leave nothing. */
    fun withoutUnaskedOffers(text: String, asked: String): String {
        val parts = text.split(Regex("""(?<=[.!?…])\s+"""))
        val kept = parts.filterNot { unaskedOffer(it, asked) }
        return if (kept.isEmpty() || kept.size == parts.size) text else kept.joinToString(" ")
    }

    /**
     * An offer: «может / хочешь / могу…», soon after it a verb of doing (включим, поставлю, запишу…), and a tool's name
     * near that — not any tool word in the sentence: «…но попробуй делать заметки от руки» is advice, not an offer.
     */
    private val offerAny = Regex("""(?iu)(?<!\p{L})(?:может|хочешь|хотите|давай|могу|можно|если\s+хочешь|shall\s+i|want\s+me\s+to|i\s+can|i\s+could|maybe|how\s+about)(?!\p{L})""" +
        """[^.!?\n]{0,30}?(?<!\p{L})(?:(?:включ|постав|завед|засеч|засек|напомн|откр|узна|посмотр|найд|найти|set|start|turn(?:ing)?\s+on|add|remind|check|look(?:ing)?\s+up|find|play)\p{L}*""" +
        """[^.!?\n]{0,40}?(?<!\p{L})(?:фонарик\p{L}*|таймер\p{L}*|будильник\p{L}*|напоминани\p{L}*|заметк\p{L}*|погод\p{L}*|музык\p{L}*|""" +
        """flashlight|torch|timers?|alarms?|reminders?|notes?|weather|music)|(?:записать|запишу|write\s+(?:it\s+)?down|make\s+a\s+note))(?!\p{L})""")
    private val capabilities = Regex("""(?iu)(?:что\s+ты\s+(?:умеешь|можешь)|что\s+умеешь|чем\s+(?:ты\s+)?(?:можешь\s+)?(?:мне\s+)?помочь|твои\s+(?:возможности|функции|умения)|""" +
        """what\s+can\s+you\s+do|what\s+do\s+you\s+do|how\s+can\s+you\s+help|what\s+are\s+you\s+able|your\s+(?:features|abilities|skills))""")
    private val toolWords = linkedMapOf(
        "light" to Regex("""(?iu)фонарик|flashlight|torch"""),
        "timer" to Regex("""(?iu)таймер|timer"""),
        "alarm" to Regex("""(?iu)будильник|alarm"""),
        "remind" to Regex("""(?iu)напоминани|reminder"""),
        "note" to Regex("""(?iu)заметк|записать|запишу|(?<!\p{L})note"""),
        "weather" to Regex("""(?iu)погод|weather"""),
        "music" to Regex("""(?iu)музык|music"""),
    )
    /** What in the person's words makes the offer fit: «темно» for the flashlight, «пирог» for a timer… */
    private val topics = mapOf(
        "light" to Regex("""(?iu)фонар|темн|свет|не\s+вижу|dark|light|can'?t\s+see"""),
        "timer" to Regex("""(?iu)таймер|засек|минут|час(?:а|ов)?(?!\p{L})|время|варю|варить|жарю|печ[её]|пирог|духовк|timer|minute|hour|time|cook|bake|boil"""),
        "alarm" to Regex("""(?iu)будиль|разбуд|просну|встать|утром|alarm|wake|morning"""),
        "remind" to Regex("""(?iu)напомн|не\s+забыть|забуд|забыва|завтра|послезавтра|встреч|экзамен|врач|remind|forget|tomorrow|meeting|exam"""),
        "note" to Regex("""(?iu)запиш|записа|заметк|список|купить|note|write|list|buy"""),
        "weather" to Regex("""(?iu)погод|дожд|снег|холод|жар|тепл|зонт|weather|rain|snow|cold|hot|umbrella"""),
        "music" to Regex("""(?iu)музык|песн|music|song"""),
    )

    /** The reply speaks of an action as done or about to be done. */
    fun made(text: String): Boolean = ru.containsMatchIn(text) || en.containsMatchIn(text)

    /** The reply offers what the person [asked] for («засеки…» → «Я могу поставить таймер на 3 минуты»): as good as a promise. */
    fun offered(text: String, asked: String): Boolean = (offerRu.containsMatchIn(text) || offerEn.containsMatchIn(text)) && asks(asked)

    /** The person's message asks for an action: «засеки», «поставь», «напомни», "set", "remind me"… */
    fun asks(text: String): Boolean = askedRu.containsMatchIn(text) || askedEn.containsMatchIn(text)

    /**
     * The tool the promise speaks of, if it can be told: «Я поставлю таймер на полчаса» — set_timer, «Я поставлю тебе
     * напоминание!», «Я напомню тебе» — set_reminder. null for anything else.
     */
    fun tool(text: String): String? {
        val m = ru.find(text) ?: en.find(text) ?: offerRu.find(text) ?: offerEn.find(text) ?: return null
        val s = text.substring(m.range.first, minOf(text.length, m.range.last + 1 + 30)).lowercase()
        return when {
            "таймер" in s || "timer" in s || "засечь" in s -> ToolNames.SET_TIMER
            "будильник" in s || "alarm" in s -> ToolNames.SET_ALARM
            "напомин" in s || "напомн" in s || "уведомлен" in s || "remind" in s -> SkillNames.SET_REMINDER
            "заметк" in s || "запиш" in s || "записал" in s || "note" in s -> SkillNames.ADD_NOTE
            "фонарик" in s || "flashlight" in s || "torch" in s -> ToolNames.FLASHLIGHT
            else -> null
        }
    }

    /** For the second try. */
    fun nudge(): String = tr("Вызови инструмент — словами на телефоне ничего не ставится.", "Call the tool — words alone set nothing on the phone.")

    /** Said after a promise nothing came of. */
    fun note(): String = tr("Ой, это я только сказала — на деле ничего не поставила. Скажи, например: «поставь таймер на 30 минут».",
        "Oops, I only said it — nothing is actually set. Say, for example: “set a timer for 30 minutes”.")
}
