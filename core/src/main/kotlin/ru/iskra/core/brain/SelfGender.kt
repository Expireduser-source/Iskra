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

    /** What memory holds: true — feminine, false — masculine, null — not known yet. */
    fun feminine(memories: List<MemoryItem>): Boolean? = memories.sortedByDescending { it.createdAtMs }
        .firstNotNullOfOrNull { when (it.text) { FEMININE_RU -> true; MASCULINE_RU -> false; else -> null } }
}
