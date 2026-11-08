package ru.iskra.core.agent

/**
 * A sentence she already said in one of her last replies: «У меня тут столько всего интересного, и ты — такой интересный
 * человек!» from Gemma 3 1B in two replies running (ARM evaluation, 0.16.2). Said again it sounds like a recording, so it
 * goes — when the reply has something else to say. Short ones («Мрр.», «Я так рада!») may well come again.
 */
object Echo {
    private val split = Regex("""(?<=[.!?…])\s+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    private fun norm(s: String) = nonWord.replace(s.lowercase().replace('ё', 'е'), " ").trim()

    /** Asked to say it again, or not understood: then a repeat is what is wanted. */
    private val again = Regex("""(?iu)(?<!\p{L})(?:повтори\p{L}*|ещё\s+раз|еще\s+раз|снова|что\s+ты\s+(?:сейчас\s+)?(?:сказала|говорила)|""" +
        """не\s+(?:расслышал\p{L}*|понял\p{L}*|услышал\p{L}*)|переспрош\p{L}*|repeat|again|say\s+that|what\s+did\s+you\s+say|""" +
        """didn'?t\s+(?:catch|hear|get|understand))(?!\p{L})""")

    /** The person asks her to repeat herself («Повтори, пожалуйста», "Say that again?"). */
    fun asksAgain(text: String): Boolean = again.containsMatchIn(text)

    /** [sentence] said word for word in one of [before] (her last replies), and five words long or more. */
    fun said(sentence: String, before: List<String>): Boolean {
        val n = norm(sentence)
        if (n.split(' ').size < 5) return false
        return before.any { " ${norm(it)} ".contains(" $n ") }
    }

    /** [text] without the sentences said before (maybe nothing). [same]: a sentence as it would be shown. */
    fun strip(text: String, before: List<String>, same: (String) -> String = { it }): String =
        if (before.isEmpty()) text else text.split(split).filterNot { said(same(it), before) }.joinToString(" ")

    /** [text] without the sentences said before, unless nothing that stands on its own would be left. */
    fun without(text: String, before: List<String>, same: (String) -> String = { it }): String {
        if (before.isEmpty()) return text
        val parts = text.split(split)
        val kept = parts.filterNot { said(same(it), before) }
        return if (kept.size == parts.size || kept.none(ActionPromise::substantive)) text else kept.joinToString(" ")
    }
}
