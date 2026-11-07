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
