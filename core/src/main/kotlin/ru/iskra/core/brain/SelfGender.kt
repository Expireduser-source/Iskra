package ru.iskra.core.brain

import ru.iskra.core.memory.MemoryItem

/**
 * How the owner speaks of themselves in Russian — «я устала», «я рада» or «я устал», «я рад» — so replies address them
 * the same way instead of always «ты устал». Only the owner's own words count (never their name), quotes are left out,
 * and it takes two messages saying the same with nothing against. The result is an ordinary memory entry, so the owner
 * sees it in «Память» and can delete it.
 */
object SelfGender {
    const val FEMININE_RU = "Владелец говорит о себе в женском роде"
    const val MASCULINE_RU = "Владелец говорит о себе в мужском роде"

    private const val FILLER = "уже|так|очень|не|ни|сегодня|вчера|только|тоже|всё|все|ещё|еще|вообще|просто|наконец|почти|совсем|опять|снова|" +
        "бы|же|правда|реально|прям|сильно|немного|чуть|давно|недавно|сейчас|тут|здесь|там|раньше|никогда|всегда|кажется|тогда|вот|ведь|" +
        "честно|точно|бы|сама|сам|ж"
    /** Nouns that end like a past-tense verb and can follow «я» («я футбол не смотрю», «я сериал досмотрел»). */
    private const val NOT_VERBS = "футбол|баскетбол|волейбол|гандбол|стол|пол|зал|сериал|журнал|материал|вокзал|канал|финал|скандал|бокал|" +
        "металл|сигнал|интервал|портал|генерал|капитал|идеал|бал|балл|гол|школа|сила|глава|стрела|скала|пчела|игла"
    private val fem = Regex("""(?iu)(?<!\p{L})я\s+(?:(?:$FILLER)\s+){0,3}(?!(?:$NOT_VERBS)(?!\p{L}))(?:\p{L}{2,}(?:ла|лась)|рада|готова|должна|уверена|согласна|сама|занята|влюблена|счастлива|голодна|больна|свободна|одна|довольна|способна|обязана|благодарна|виновата|замужем)(?!\p{L})""")
    private val masc = Regex("""(?iu)(?<!\p{L})я\s+(?:(?:$FILLER)\s+){0,3}(?!(?:$NOT_VERBS)(?!\p{L}))(?:\p{L}+(?:л|лся)|рад|готов|должен|уверен|согласен|сам|занят|влюбл[её]н|счастлив|голоден|болен|свободен|один|доволен|способен|обязан|благодарен|виноват|женат)(?!\p{L})""")
    private val quoted = Regex("""«[^»]*»|"[^"]*"|“[^”]*”""")

    /** Nouns and names that look like a past-tense verb at the start of a sentence («Акула!», «Павел на работе»). */
    private const val NOT_VERBS_START = "акула|юла|мила|смола|зола|кабала|гала|мула|метла|мгла|алла|стелла|белла|павел|михаил|даниил|гавриил|" +
        "ангел|стул|угол|посол|ос[её]л|ор[её]л|кот[её]л|пепел|дятел|мул|аул|тыл|пыл|вал|ил|мел|пол|дол"
    /** What may follow such a verb in the same sentence — a place, a time, a manner, but never a subject of its own. */
    private const val AFTER_VERB = "на|в|во|с|со|из|к|ко|по|до|за|у|от|домой|сегодня|вчера|только|уже|наконец|опять|снова|очень|так|ужасно|" +
        "жутко|немного|чуть|недавно|сейчас|тоже|весь|всю|целый|целую|сама|сам"
    /**
     * «Сдала на пятёрку!», «Устала.», «Вернулся домой» — a sentence that begins with the verb and has no subject of its
     * own (so «Пришла посылка» doesn't count), not a question («Устала?» is asked of her).
     */
    private fun startVerb(ending: String) = Regex("""(?iu)(?:^|[.!?…]\s+)(?!(?:$NOT_VERBS|$NOT_VERBS_START)(?!\p{L}))\p{L}*(?:$ending)""" +
        """(?=\s*(?:[.!…,]|$)|\s+(?:$AFTER_VERB)(?!\p{L})[^.!?…]*(?:[.!…]|$))""")
    private val femStart = startVerb("""[аеиоуыэюяёшгкрзсб]ла|[аеиоуыэюяёшгкрзсб]лась""")
    private val mascStart = startVerb("""[аеиоуыэюяё]л|[аеиоуыэюяё]лся|ш[её]л""")

    /** 'f', 'm', or null when one message doesn't tell (or tells both). */
    fun of(text: String): Char? {
        val t = quoted.replace(text, " ").trim()
        val f = fem.findAll(t).count() + femStart.findAll(t).count()
        val m = masc.findAll(t).count() + mascStart.findAll(t).count()
        return when {
            f > 0 && m == 0 -> 'f'
            m > 0 && f == 0 -> 'm'
            else -> null
        }
    }

    /** From the owner's messages: at least two saying the same and three times more than against; else null. */
    fun decide(messages: List<String>): Char? {
        val votes = messages.mapNotNull(::of)
        val f = votes.count { it == 'f' }
        val m = votes.count { it == 'm' }
        return when {
            f >= 2 && f >= 3 * m -> 'f'
            m >= 2 && m >= 3 * f -> 'm'
            else -> null
        }
    }

    private val feminineOf = mapOf("готов" to "готова", "рад" to "рада", "уверен" to "уверена", "прав" to "права", "сам" to "сама",
        "один" to "одна", "занят" to "занята", "должен" to "должна", "согласен" to "согласна", "свободен" to "свободна", "голоден" to "голодна",
        "болен" to "больна", "счастлив" to "счастлива", "доволен" to "довольна", "способен" to "способна", "обязан" to "обязана", "нужен" to "нужна",
        "виноват" to "виновата", "умён" to "умна", "умен" to "умна", "влюблён" to "влюблена", "влюблен" to "влюблена", "расстроен" to "расстроена",
        "удивлён" to "удивлена", "удивлен" to "удивлена", "огорчён" to "огорчена", "огорчен" to "огорчена", "утомлён" to "утомлена", "утомлен" to "утомлена",
        "прекрасен" to "прекрасна", "красив" to "красива", "умница" to "умница", "силён" to "сильна", "силен" to "сильна", "смел" to "смела",
        // Long forms, from compliments and support (in the evaluation: «ты замечательный и любимый», «ты просто прекрасен — очень добрый»).
        "замечательный" to "замечательная", "любимый" to "любимая", "добрый" to "добрая", "понимающий" to "понимающая", "умный" to "умная",
        "лучший" to "лучшая", "хороший" to "хорошая", "красивый" to "красивая", "сильный" to "сильная", "смелый" to "смелая",
        "особенный" to "особенная", "единственный" to "единственная", "дорогой" to "дорогая", "милый" to "милая", "талантливый" to "талантливая",
        "удивительный" to "удивительная", "чудесный" to "чудесная", "уникальный" to "уникальная", "способный" to "способная", "заботливый" to "заботливая",
        "храбрый" to "храбрая", "честный" to "честная", "трудолюбивый" to "трудолюбивая", "упорный" to "упорная", "внимательный" to "внимательная",
        "интересный" to "интересная", "классный" to "классная", "крутой" to "крутая", "настоящий" to "настоящая", "усталый" to "усталая",
        "грустный" to "грустная", "весёлый" to "весёлая", "веселый" to "веселая", "сонный" to "сонная", "голодный" to "голодная", "одинокий" to "одинокая",
        "готовый" to "готовая", "уставший" to "уставшая", "довольный" to "довольная", "счастливый" to "счастливая", "сам по себе" to "сама по себе",
        // «Ты такой милая!» (E2B, ARM evaluation 0.16.1): the pronoun-adjective agrees too, and more words of praise.
        "такой" to "такая", "прекрасный" to "прекрасная", "отличный" to "отличная", "нежный" to "нежная", "смешной" to "смешная",
        "спокойный" to "спокойная", "солнечный" to "солнечная", "потрясающий" to "потрясающая", "великолепный" to "великолепная",
        "необыкновенный" to "необыкновенная", "золотой" to "золотая", "молодой" to "молодая", "сладкий" to "сладкая")
    private val notVerbs = NOT_VERBS.split('|').toSet()
    /**
     * Masculine nouns said of anyone: the words before them agree with the noun, not with the person — «ты такой интересный
     * человек» stays (E2B said it to Аня in the ARM evaluation of 0.16.2; «такая интересная человек» would be wrong).
     */
    private val masculineNouns = setOf("человек", "человечек", "друг", "дружище", "собеседник", "специалист", "профессионал", "мастер", "гений",
        "пример", "врач", "доктор", "учитель", "студент", "котик", "зайчик", "ангел", "лучик", "талант", "герой", "боец", "товарищ", "партнёр",
        "партнер", "ребёнок", "ребенок", "кот", "котёнок", "котенок", "помощник", "слушатель", "рассказчик", "мечтатель")
    /** Who else a clause may be about: past this word the verb is no longer the owner's. */
    private val otherSubject = setOf("я", "он", "она", "оно", "мы", "вы", "они", "кто", "что", "это", "все", "всё", "мама", "папа")
    private val word = Regex("""\p{L}+|[.!?;:,()«»"]""")
    /** Words set off by commas that don't end the clause: «ты, наверное, устал». */
    private val parenthetical = setOf("наверное", "кажется", "конечно", "правда", "похоже", "видимо", "ведь", "же", "да", "ну", "наконец", "может",
        "возможно", "небось", "мрр", "вроде", "честно", "кстати")

    /**
     * A reply that calls a woman «ты устал», «ты готов» (small models often do, whatever the prompt says) → «ты устала»,
     * «ты готова»: past-tense verbs and short adjectives within four words after «ты», in the same clause, up to another
     * subject («ты устал, а я рада» → «ты устала, а я рада»). Nouns like «футбол» are left alone.
     */
    fun agreeFeminine(text: String): String {
        val tokens = word.findAll(text).toList()
        val out = StringBuilder(text)
        val edits = ArrayList<Pair<IntRange, String>>()
        for ((i, t) in tokens.withIndex()) {
            if (!t.value.equals("ты", ignoreCase = true)) continue
            var words = 0
            var aside = false
            for (k in i + 1 until tokens.size) {
                val w = tokens[k].value
                if (w == ",") {
                    // «ты, наверное, устал»: an aside goes on; any other comma ends the clause («ты знаешь, кот пришёл»).
                    if (aside) { aside = false; continue }
                    if (tokens.getOrNull(k + 1)?.value?.lowercase() in parenthetical) { aside = true; continue }
                    break
                }
                // Dashes are skipped («ты прекрасен — очень добрый»): they aren't tokens. Up to six words after «ты».
                if (!w.first().isLetter() || w.lowercase() in otherSubject || w.equals("ты", true) || ++words > 6) break
                val lw = w.lowercase()
                if (lw in masculineNouns) { while (edits.isNotEmpty() && edits.last().first.first > t.range.first) edits.removeAt(edits.lastIndex); break }
                val fem = when {
                    lw in notVerbs || lw.length < 3 -> null
                    feminineOf.containsKey(lw) -> feminineOf[lw]
                    lw.endsWith("шёл") || lw.endsWith("шел") -> w.dropLast(3) + "шла"
                    lw.endsWith("чёл") || lw.endsWith("чел") -> w.dropLast(3) + "чла"
                    lw.endsWith("лся") -> w.dropLast(3) + "лась"
                    lw.endsWith("л") && lw.dropLast(1).lastOrNull()?.let { it in "аеиоуыяюё" } == true -> w + "а"
                    else -> null
                }
                if (fem != null) edits += tokens[k].range to fem
            }
        }
        for ((range, fem) in edits.distinctBy { it.first.first }.sortedByDescending { it.first.first }) out.replace(range.first, range.last + 1, fem)
        return out.toString()
    }

    private val slash = Regex("""(?<!\p{L})(\p{IsCyrillic}+)/(\p{IsCyrillic}+)(?!\p{L})""")
    private val paren = Regex("""(?<!\p{L})(\p{IsCyrillic}{2,})\(а\)""")

    /** «милый»/«милая», «вернулся»/«вернулась» → (masculine, feminine); «и/или», «чай/кофе» → null. */
    private fun pair(a: String, b: String): Pair<String, String>? {
        fun fem(w: String) = w.lowercase().let { it.endsWith("ась") || it.endsWith("а") || (it.endsWith("я") && !it.endsWith("ся")) }
        if (a.lowercase().commonPrefixWith(b.lowercase()).length < 2 || fem(a) == fem(b)) return null
        return if (fem(b)) a to b else b to a
    }

    /**
     * «Ой, милый/милая», «ты устал(а)?»: a model hedging because it doesn't know whom it talks to. When the owner's way of
     * speaking is known ([feminine]), the matching form stays; while it isn't, a lone form of address («Ой, милый/милая.»)
     * goes and the rest is left as it is.
     */
    fun slashes(text: String, feminine: Boolean?): String {
        if (!text.contains('/') && !text.contains("(а)")) return text
        if (feminine != null) return text
            .replace(paren) { m -> if (feminine) m.groupValues[1] + "а" else m.groupValues[1] }
            .replace(slash) { m ->
                val (a, b) = m.destructured
                pair(a, b)?.let { (if (feminine) it.second else it.first).let { w -> if (a[0].isUpperCase()) w.replaceFirstChar(Char::uppercaseChar) else w } } ?: m.value
            }
        fun gendered(a: String, b: String) = pair(a, b) != null
        // An address on its own: «Милый/милая, не грусти» → «Не грусти»; «Не грусти, милый/милая, всё…» → «Не грусти, всё…»;
        // «Ой, милый/милая.» → «Ой.»
        return text
            .replace(Regex("""(^|[.!?…]\s+)(\p{IsCyrillic}+)/(\p{IsCyrillic}+)\s*,\s*(\p{L})""")) { m ->
                if (gendered(m.groupValues[2], m.groupValues[3])) m.groupValues[1] + m.groupValues[4].uppercase() else m.value
            }
            .replace(Regex(""",\s*(\p{IsCyrillic}+)/(\p{IsCyrillic}+)\s*,""")) { m ->
                if (gendered(m.groupValues[1], m.groupValues[2])) "," else m.value
            }
            .replace(Regex(""",\s*(\p{IsCyrillic}+)/(\p{IsCyrillic}+)(?=\s*[.!?…]|\s*$)""")) { m ->
                if (gendered(m.groupValues[1], m.groupValues[2])) "" else m.value
            }
    }

    /** What memory holds: true — feminine, false — masculine, null — not known yet. */
    fun feminine(memories: List<MemoryItem>): Boolean? = memories.sortedByDescending { it.createdAtMs }
        .firstNotNullOfOrNull { when (it.text) { FEMININE_RU -> true; MASCULINE_RU -> false; else -> null } }
}
