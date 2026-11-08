package ru.iskra.core.agent

/**
 * «Я не могу дать тебе медицинский совет, но знаю, что иногда помогает немного активности…» — a phone model's hedge before
 * the advice it then gives anyway, on «Посоветуй, как не заснуть на скучной лекции» (E4B, twice in four ARM evaluations
 * of 0.16.1: the person studies medicine, and the model took a lecture for medicine). The hedge goes and the advice stays.
 * Not when the person's words are about health: there a model saying it isn't a doctor is right to.
 */
object AdviceHedge {
    private val ru = Regex("""(?iu)(?<![\p{L}])(?:я\s+)?(?:не\s+могу|не\s+смогу)\s+(?:тебе\s+)?(?:прямо\s+|точно\s+|сама\s+)?""" +
        """(?:дать|давать|предложить)\s+(?:тебе\s+)?(?:\p{L}+\s+)?совет\p{L}*[^.!?]{0,60}?,\s*(?:но|однако)\s+""")
    private val ruVerb = Regex("""(?iu)(?<![\p{L}])(?:я\s+)?не\s+могу\s+(?:тебе\s+)?(?:прямо\s+|точно\s+)?посоветовать[^.!?]{0,60}?,\s*(?:но|однако)\s+(?=\p{L})""")
    private val en = Regex("""(?i)\bI\s+(?:can't|cannot|can\s+not|am\s+not\s+able\s+to)\s+(?:give|offer|provide)\s+(?:you\s+)?(?:\w+\s+)?advice[^.!?]{0,60}?,\s*but\s+""")

    /** The person's words are about their health: a hedge there stays. */
    private val health = Regex("""(?iu)(?<![\p{L}])(?:бол(?:ит|ят|ь|и|ью|ел\p{L}*|езн\p{L}*)|температур\p{L}*|лекарств\p{L}*|таблетк\p{L}*|симптом\p{L}*|давлени\p{L}*|""" +
        """сердц\p{L}*|беремен\p{L}*|тошн\p{L}*|рвот\p{L}*|кашл\p{L}*|аллерги\p{L}*|диагноз\p{L}*|лечен\p{L}*|лечить|""" +
        """pain|hurts?|ache|fever|medicine|pills?|symptoms?|pressure|heart|pregnan\p{L}*|nause\p{L}*|cough|allerg\p{L}*|diagnos\p{L}*|treatment)(?![\p{L}])""")

    /** [sentence] without such a hedge before the advice; [asked] is the person's message. */
    fun strip(sentence: String, asked: String): String {
        if (health.containsMatchIn(asked)) return sentence
        var out = sentence
        for (re in listOf(ru, ruVerb, en)) {
            val m = re.find(out) ?: continue
            val start = m.range.first
            val rest = out.substring(m.range.last + 1)
            // At the start of a sentence the advice that follows begins with a capital.
            val atStart = out.substring(0, start).trimEnd().let { it.isEmpty() || it.last() in ".!?…" }
            out = out.substring(0, start) + if (atStart) rest.replaceFirstChar { it.uppercaseChar() } else rest
        }
        return out
    }
}
