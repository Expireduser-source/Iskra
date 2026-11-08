package ru.iskra.core.brain

import ru.iskra.core.agent.Turn
import ru.iskra.core.persona.Emotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Yesterday the owner said they were sad, ill, had a quarrel or spoke of not wanting to live ([CrisisTalk]) — today's
 * first words are «как ты?». Nothing new is kept
 * for it: the check-in comes from yesterday's messages in the chat, only when nothing later that day said it got better,
 * and only in the first exchange of the day (so it is asked once).
 */
object CareTalk {
    /** [WORRY] — «волнуюсь», "I'm stressed": only for the message being answered ([feels]), not for tomorrow's check-in. */
    enum class Kind { CRISIS, LOW, SICK, QUARREL, WORRY }

    data class Care(val kind: Kind, val ru: Boolean)

    private val low = Regex("""(?iu)(?<!\p{L})(?:грустно|тоскливо|одиноко|печально|паршиво|хреново|плохо\s+на\s+душе|тяжело\s+на\s+душе|на\s+душе\s+кошки\s+скребут|(?:тяж[её]лый|ужасный|плохой|отвратительный|кошмарный)\s+день|мне\s+(?:(?!не\s)\p{L}+\s+)?плохо|мне\s+(?:(?!не\s)\p{L}+\s+)?тяжело|депресси\p{L}*)(?!\p{L})""")
    private val sick = Regex("""(?iu)(?<!\p{L})(?:заболел|заболела|простыл|простыла|простудился|простудилась|болею|приболел|приболела|температур\p{L}*|простуда|грипп|ковид|отравился|отравилась|болит\s+(?:голова|горло|живот|зуб|спина|ухо)|(?:голова|горло|живот|зуб|спина|ухо)\s+болит)(?!\p{L})""")
    private val quarrel = Regex("""(?iu)(?<!\p{L})(?:поругал(?:ся|ась|ись)|поссорил(?:ся|ась|ись)|разругал(?:ся|ась|ись)|расстал(?:ся|ась|ись))(?!\p{L})""")
    private val lowEn = Regex("""(?iu)(?<!\p{L})i(?:'m|\s+am|\s+feel|\s+'m\s+feeling|'m\s+feeling|\s+am\s+feeling)\s+(?:so\s+|really\s+|very\s+|kind\s+of\s+|a\s+bit\s+)?(?:sad|down|lonely|depressed|awful|terrible|miserable|blue|low)(?!\p{L})""")
    private val sickEn = Regex("""(?iu)(?<!\p{L})(?:i(?:'m|\s+am)\s+(?:so\s+|really\s+)?(?:sick|ill|unwell)|i\s+(?:have|got|caught)\s+(?:a\s+|the\s+)?(?:cold|fever|flu|headache|sore\s+throat|covid|stomach\s+bug))(?!\p{L})""")
    private val quarrelEn = Regex("""(?iu)(?<!\p{L})i\s+(?:had\s+a\s+fight|had\s+an\s+argument|fought|argued|broke\s+up)\s+with(?!\p{L})""")
    private val worry = Regex("""(?iu)(?<!\p{L})(?:волну(?:юсь|емся)|переживаю|нервничаю|тревож(?:усь|но)|паникую|в\s+стрессе|стрессую|на\s+нервах|""" +
        """мне\s+(?:(?!не\s)\p{L}+\s+)?страшно|я\s+(?:очень\s+|так\s+|жутко\s+)?боюсь|не\s+могу\s+успокоиться)(?!\p{L})""")
    private val worryEn = Regex("""(?iu)(?<!\p{L})(?:i(?:'m|\s+am|\s+feel|'m\s+feeling|\s+am\s+feeling)\s+(?:so\s+|really\s+|very\s+|kind\s+of\s+|a\s+bit\s+|""" +
        """a\s+little\s+|super\s+|pretty\s+)?(?:stressed|anxious|worried|nervous|scared|afraid|overwhelmed|panicking|freaking\s+out)|i'm\s+stressing)(?!\p{L})""")
    /** Asked about her, not said of oneself: «Тебе грустно?», "Are you sad?". */
    private val aboutHer = Regex("""(?iu)(?<!\p{L})(?:тебе|ты|тебя|you)(?!\p{L})""")
    private val aboutMe = Regex("""(?iu)(?<!\p{L})(?:мне|я|меня|i|i'm|me|my)(?!\p{L})""")

    /** Later that day: it passed. */
    private val better = Regex("""(?iu)(?<!\p{L})(?:уже\s+)?(?:лучше|получше|прошло|полегчало|отпустило|выздоровел\p{L}*|помирил\p{L}*|всё\s+хорошо|все\s+хорошо|всё\s+нормально|все\s+нормально|better|fine\s+now|okay\s+now|ok\s+now|made\s+up|all\s+good)(?!\p{L})""")

    /** «не» as a word right before: «мне не грустно», «я не болею» (but «Мне грустно» — «не» there is part of «мне»). */
    private val negated = Regex("""(?iu)(?<!\p{L})(?:не|not|n't)\s+$""")

    private fun said(re: Regex, text: String) = re.findAll(text).any { !negated.containsMatchIn(text.substring(0, it.range.first)) }

    /** What a message says about how the owner is, or null. */
    fun heard(text: String, crisis: Boolean = true): Kind? = when {
        crisis && CrisisTalk.said(text) -> Kind.CRISIS
        said(sick, text) || said(sickEn, text) -> Kind.SICK
        said(quarrel, text) || said(quarrelEn, text) -> Kind.QUARREL
        said(low, text) || said(lowEn, text) -> Kind.LOW
        else -> null
    }

    /**
     * How the owner says they are in the message being answered — low, ill, after a quarrel, worried — or null. Not when
     * they ask how she is («Тебе грустно?»), and words about not wanting to live are [CrisisTalk]'s.
     */
    fun feels(text: String): Kind? {
        if (aboutHer.containsMatchIn(text) && !aboutMe.containsMatchIn(text) || better.containsMatchIn(text)) return null
        return heard(text, crisis = false) ?: if (said(worry, text) || said(worryEn, text)) Kind.WORRY else null
    }

    private val glad = setOf(Emotion.HAPPY, Emotion.EXCITED, Emotion.CURIOUS, Emotion.POUTY, Emotion.SURPRISED, Emotion.SLEEPY)

    /**
     * Her face to what the owner [feels]: sympathy, not curiosity or joy, at «мне грустно» (E2B looked curious at it in the
     * ARM evaluation of 0.16.2); calm, not excitement, at «волнуюсь» — a smile of encouragement stays.
     */
    fun face(feels: Kind?, e: Emotion): Emotion = when {
        feels == null -> e
        feels == Kind.WORRY -> if (e in glad && e != Emotion.HAPPY) Emotion.NEUTRAL else e
        else -> if (e in glad) Emotion.SAD else e
    }

    /**
     * For the model, with the message it answers: «Мне сегодня грустно, поддержи меня» got one line from E2B, and a
     * curious face (ARM evaluation, 0.16.2).
     */
    fun nowNote(k: Kind, ru: Boolean): String? = if (ru) when (k) {
        Kind.LOW -> "собеседнику сейчас грустно — посочувствуй тепло; если не сказано почему, мягко спроси, что случилось"
        Kind.SICK -> "собеседнику нездоровится — посочувствуй и пожелай поскорее поправиться"
        Kind.QUARREL -> "у собеседника ссора с близким — выслушай и посочувствуй, не принимая ничью сторону"
        Kind.WORRY -> "собеседник волнуется — успокой и подбодри, коротко и тепло"
        Kind.CRISIS -> null
    } else when (k) {
        Kind.LOW -> "the person feels low right now — comfort them warmly; if they haven't said why, gently ask what happened"
        Kind.SICK -> "the person is unwell — sympathize and wish them a quick recovery"
        Kind.QUARREL -> "the person has quarrelled with someone close — listen and sympathize without taking sides"
        Kind.WORRY -> "the person is worried — reassure and encourage them, briefly and warmly"
        Kind.CRISIS -> null
    }

    /**
     * The check-in due today: yesterday the owner said they were low, ill or quarrelled, nothing later that day said it
     * passed, and there is no exchange today yet. [turns] are the recent dialogue, oldest first. [crisis]: «Помощь в
     * кризисе» is on (words about not wanting to live count; off — they are left to the model).
     */
    fun pending(turns: List<Turn>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), crisis: Boolean = true): Care? {
        fun day(t: Turn) = Instant.ofEpochMilli(t.atMs).atZone(zone).toLocalDate()
        if (turns.any { day(it) == today }) return null
        val yesterday = turns.filter { it.role == Turn.Role.USER && day(it) == today.minusDays(1) }
        var found: Care? = null
        for (t in yesterday) {
            // A later «мне грустно» doesn't make words about not wanting to live less serious.
            heard(t.text, crisis)?.takeIf { found?.kind != Kind.CRISIS || it == Kind.CRISIS }
                ?.let { found = Care(it, t.text.any { c -> c in 'а'..'я' || c in 'А'..'Я' }) }
            if (better.containsMatchIn(t.text)) found = null // «было плохо, но уже лучше», or a later «полегчало»
        }
        return found
    }

    /** «Как ты сегодня? Вчера тебе было нелегко — надеюсь, сегодня светлее. Мрр.» */
    fun ask(c: Care): String = if (c.ru) when (c.kind) {
        Kind.CRISIS -> "Как ты сегодня? Вчера тебе было очень тяжело. Я рядом, если захочешь поговорить."
        Kind.LOW -> "Как ты сегодня? Вчера тебе было нелегко — надеюсь, сегодня светлее. Мрр."
        Kind.SICK -> "Как самочувствие? Мрр, выздоравливай скорее — я за тебя переживаю."
        Kind.QUARREL -> "Как ты? Помирились после вчерашнего? Мрр, надеюсь, всё наладилось."
        Kind.WORRY -> "Как ты сегодня? Надеюсь, вчерашнее волнение позади. Мрр."
    } else when (c.kind) {
        Kind.CRISIS -> "How are you today? Yesterday was really hard for you. I'm here if you want to talk."
        Kind.LOW -> "How are you today? Yesterday was hard for you — I hope today's brighter. Mrr."
        Kind.SICK -> "How are you feeling? Mrr, get well soon — I've been worrying about you."
        Kind.QUARREL -> "How are you? Did you make up after yesterday? Mrr, I hope it's all okay now."
        Kind.WORRY -> "How are you today? I hope yesterday's worries are behind you. Mrr."
    }

    /** For the model, in the first exchange of the day. */
    fun note(c: Care, ru: Boolean): String = if (ru) when (c.kind) {
        // Without «он»: the owner may well be a woman, and the model takes the word for it («ты выздоровел?»).
        Kind.CRISIS -> "вчера владельцу было очень тяжело, звучали слова о том, что не хочется жить, — бережно спроси, как дела сегодня, без шуток"
        Kind.LOW -> "вчера владельцу было грустно или тяжело — мягко спроси, как дела сегодня"
        Kind.SICK -> "вчера владельцу нездоровилось — спроси о самочувствии"
        Kind.QUARREL -> "вчера у владельца была ссора с кем-то — бережно спроси, всё ли наладилось"
        Kind.WORRY -> "вчера владельцу было тревожно — спроси, как всё прошло"
    } else when (c.kind) {
        Kind.CRISIS -> "yesterday the owner was in a very dark place and spoke of not wanting to live — gently ask how they are today, no jokes"
        Kind.LOW -> "yesterday the owner felt down — gently ask how they are today"
        Kind.SICK -> "yesterday the owner was ill — ask how they feel"
        Kind.QUARREL -> "yesterday the owner had a quarrel — gently ask whether it's better now"
        Kind.WORRY -> "yesterday the owner was worried — ask how it went"
    }
}
