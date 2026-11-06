package ru.iskra.core.brain

import ru.iskra.core.agent.Turn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Yesterday the owner said they were sad, ill or had a quarrel — today's first words are «как ты?». Nothing new is kept
 * for it: the check-in comes from yesterday's messages in the chat, only when nothing later that day said it got better,
 * and only in the first exchange of the day (so it is asked once).
 */
object CareTalk {
    enum class Kind { LOW, SICK, QUARREL }

    data class Care(val kind: Kind, val ru: Boolean)

    private val low = Regex("""(?iu)(?<!\p{L})(?<!не\s)(?:грустно|тоскливо|одиноко|печально|паршиво|хреново|плохо\s+на\s+душе|тяжело\s+на\s+душе|на\s+душе\s+кошки\s+скребут|мне\s+(?:(?!не\s)\p{L}+\s+)?плохо|мне\s+(?:(?!не\s)\p{L}+\s+)?тяжело|депресси\p{L}*)(?!\p{L})""")
    private val sick = Regex("""(?iu)(?<!\p{L})(?<!не\s)(?:заболел|заболела|простыл|простыла|простудился|простудилась|болею|приболел|приболела|температур\p{L}*|простуда|грипп|ковид|отравился|отравилась|болит\s+(?:голова|горло|живот|зуб|спина|ухо)|(?:голова|горло|живот|зуб|спина|ухо)\s+болит)(?!\p{L})""")
    private val quarrel = Regex("""(?iu)(?<!\p{L})(?:поругал(?:ся|ась|ись)|поссорил(?:ся|ась|ись)|разругал(?:ся|ась|ись)|расстал(?:ся|ась|ись))(?!\p{L})""")
    private val lowEn = Regex("""(?iu)(?<!\p{L})i(?:'m|\s+am|\s+feel|\s+'m\s+feeling|'m\s+feeling|\s+am\s+feeling)\s+(?:so\s+|really\s+|very\s+|kind\s+of\s+|a\s+bit\s+)?(?:sad|down|lonely|depressed|awful|terrible|miserable|blue|low)(?!\p{L})""")
    private val sickEn = Regex("""(?iu)(?<!\p{L})(?:i(?:'m|\s+am)\s+(?:so\s+|really\s+)?(?:sick|ill|unwell)|i\s+(?:have|got|caught)\s+(?:a\s+|the\s+)?(?:cold|fever|flu|headache|sore\s+throat|covid|stomach\s+bug))(?!\p{L})""")
    private val quarrelEn = Regex("""(?iu)(?<!\p{L})i\s+(?:had\s+a\s+fight|had\s+an\s+argument|fought|argued|broke\s+up)\s+with(?!\p{L})""")
    /** Later that day: it passed. */
    private val better = Regex("""(?iu)(?<!\p{L})(?:уже\s+)?(?:лучше|получше|прошло|полегчало|отпустило|выздоровел\p{L}*|помирил\p{L}*|всё\s+хорошо|все\s+хорошо|всё\s+нормально|все\s+нормально|better|fine\s+now|okay\s+now|ok\s+now|made\s+up|all\s+good)(?!\p{L})""")

    /** What a message says about how the owner is, or null. */
    fun heard(text: String): Kind? = when {
        sick.containsMatchIn(text) || sickEn.containsMatchIn(text) -> Kind.SICK
        quarrel.containsMatchIn(text) || quarrelEn.containsMatchIn(text) -> Kind.QUARREL
        low.containsMatchIn(text) || lowEn.containsMatchIn(text) -> Kind.LOW
        else -> null
    }

    /**
     * The check-in due today: yesterday the owner said they were low, ill or quarrelled, nothing later that day said it
     * passed, and there is no exchange today yet. [turns] are the recent dialogue, oldest first.
     */
    fun pending(turns: List<Turn>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Care? {
        fun day(t: Turn) = Instant.ofEpochMilli(t.atMs).atZone(zone).toLocalDate()
        if (turns.any { day(it) == today }) return null
        val yesterday = turns.filter { it.role == Turn.Role.USER && day(it) == today.minusDays(1) }
        var found: Care? = null
        for (t in yesterday) {
            heard(t.text)?.let { found = Care(it, t.text.any { c -> c in 'а'..'я' || c in 'А'..'Я' }) }
            if (better.containsMatchIn(t.text)) found = null // «было плохо, но уже лучше», or a later «полегчало»
        }
        return found
    }

    /** «Как ты сегодня? Вчера тебе было нелегко — надеюсь, сегодня светлее. Мрр.» */
    fun ask(c: Care): String = if (c.ru) when (c.kind) {
        Kind.LOW -> "Как ты сегодня? Вчера тебе было нелегко — надеюсь, сегодня светлее. Мрр."
        Kind.SICK -> "Как самочувствие? Мрр, выздоравливай скорее — я за тебя переживаю."
        Kind.QUARREL -> "Как ты? Помирились после вчерашнего? Мрр, надеюсь, всё наладилось."
    } else when (c.kind) {
        Kind.LOW -> "How are you today? Yesterday was hard for you — I hope today's brighter. Mrr."
        Kind.SICK -> "How are you feeling? Mrr, get well soon — I've been worrying about you."
        Kind.QUARREL -> "How are you? Did you make up after yesterday? Mrr, I hope it's all okay now."
    }

    /** For the model, in the first exchange of the day. */
    fun note(c: Care, ru: Boolean): String = if (ru) when (c.kind) {
        Kind.LOW -> "вчера владельцу было грустно или тяжело — мягко спроси, как он сегодня"
        Kind.SICK -> "вчера владелец болел — спроси о самочувствии"
        Kind.QUARREL -> "вчера владелец с кем-то поссорился — бережно спроси, всё ли наладилось"
    } else when (c.kind) {
        Kind.LOW -> "yesterday the owner felt down — gently ask how they are today"
        Kind.SICK -> "yesterday the owner was ill — ask how they feel"
        Kind.QUARREL -> "yesterday the owner had a quarrel — gently ask whether it's better now"
    }
}
