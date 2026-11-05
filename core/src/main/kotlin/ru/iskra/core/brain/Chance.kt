package ru.iskra.core.brain

import ru.iskra.core.i18n.tr
import kotlin.random.Random

/** Coin, dice, a random number, "выбери: пицца или суши". */
object Chance {
    private val coin = Regex("""^(?:подбрось|подкинь|брось|кинь|подбросить)\s+монет\p{L}*|^монет(?:ка|ку)|^орел\s+или\s+решка|^решка\s+или\s+орел|^(?:flip|toss)\s+a\s+coin|^coin\s+(?:flip|toss)|^heads\s+or\s+tails""")
    private val dice = Regex("""^(?:брось|кинь|подбрось|бросить)\s+(?:(\d)\s+)?(?:кубик\p{L}*|кост\p{L}*)|^кубик|^roll\s+(?:(\d)\s+)?(?:a\s+|the\s+)?(?:die|dice)""")
    private val number = Regex("""^(?:назови|загадай|скажи|выбери|дай|придумай)?\s*(?:мне\s+)?(?:любое\s+)?(?:случайное\s+)?число\s+от\s+(-?\d+)\s+до\s+(-?\d+)|^(?:назови|загадай|скажи|выбери|дай)?\s*(?:мне\s+)?случайное\s+число|""" +
        """^(?:give\s+me\s+|pick\s+|say\s+)?a\s+random\s+number(?:\s+(?:between|from)\s+(-?\d+)\s+(?:and|to)\s+(-?\d+))?|^random\s+number(?:\s+(?:between|from)\s+(-?\d+)\s+(?:and|to)\s+(-?\d+))?""")
    private val choose = Regex("""^(?:выбери|выбирай|реши\s+за\s+меня|что\s+выбрать|choose|pick|decide)[:,]?\s+(.+)$""")

    fun answer(raw: String, random: Random): String? {
        val t = NumberWords.digitize(raw.lowercase().replace('ё', 'е')).trim()
            .removePrefix("искра,").removePrefix("iskra,").trim().trimEnd('?', '.', '!', ' ')
        if (coin.containsMatchIn(t)) return if (random.nextBoolean()) tr("Орёл!", "Heads!") else tr("Решка!", "Tails!")
        dice.find(t)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull() ?: 1).coerceIn(1, 6)
            val rolls = List(n) { random.nextInt(1, 7) }
            return if (n == 1) tr("Выпало ${rolls[0]}.", "You rolled ${rolls[0]}.")
            else tr("Выпало ${rolls.joinToString(", ")} — всего ${rolls.sum()}.", "You rolled ${rolls.joinToString(", ")} — ${rolls.sum()} in total.")
        }
        number.find(t)?.let { m ->
            val g = m.groupValues.drop(1).filter { it.isNotEmpty() }
            var lo = g.getOrNull(0)?.toLongOrNull() ?: 1
            var hi = g.getOrNull(1)?.toLongOrNull() ?: 100
            if (lo > hi) lo = hi.also { hi = lo }
            if (hi - lo > 1_000_000_000_000) return null
            val v = random.nextLong(lo, hi + 1)
            return tr("Пусть будет $v.", "Let's say $v.")
        }
        choose.find(t)?.let { m ->
            val options = m.groupValues[1].split(Regex("""\s*(?:,|;|\s+или\s+|\s+or\s+)\s*""")).map { it.trim().trimStart(':') }.filter { it.isNotEmpty() }
            if (options.size < 2 || options.size > 10) return null
            val pick = options[random.nextInt(options.size)]
            return tr("Я за «$pick». Мрр, решено.", "I pick “$pick”. Mrr, decided.")
        }
        return null
    }
}
