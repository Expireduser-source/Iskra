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

    /** 'f', 'm', or null when one message doesn't tell (or tells both). */
    fun of(text: String): Char? {
        val t = quoted.replace(text, " ")
        val f = fem.findAll(t).count()
        val m = masc.findAll(t).count()
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
        "готовый" to "готовая", "уставший" to "уставшая", "довольный" to "довольная", "счастливый" to "счастливая", "сам по себе" to "сама по себе")
    private val notVerbs = NOT_VERBS.split('|').toSet()
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

    /** What memory holds: true — feminine, false — masculine, null — not known yet. */
    fun feminine(memories: List<MemoryItem>): Boolean? = memories.sortedByDescending { it.createdAtMs }
        .firstNotNullOfOrNull { when (it.text) { FEMININE_RU -> true; MASCULINE_RU -> false; else -> null } }
}
