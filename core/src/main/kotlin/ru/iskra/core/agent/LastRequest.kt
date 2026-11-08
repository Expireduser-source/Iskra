package ru.iskra.core.agent

import ru.iskra.core.i18n.tr

/**
 * «О чём я тебя только что попросила?», "what did I just ask you?" — answered from the chat itself where the brain can't
 * keep track of it: Gemma 3 1B said «Я просто пытаюсь помочь!» or «Ох, это так неловко!» in every ARM evaluation. Words
 * without «он/она»: the owner's way of speaking may not be known yet.
 */
object LastRequest {
    private val asked = Regex("""(?iu)(?:о\s+ч[её]м|что)\s+я\s+(?:тебя\s+|тебе\s+|у\s+тебя\s+)?(?:только\s+что\s+|сейчас\s+|недавно\s+)?""" +
        """(?:попросил\p{L}*|спросил\p{L}*|просил\p{L}*|сказал\p{L}*|написал\p{L}*|говорил\p{L}*)|""" +
        """what\s+did\s+i\s+(?:just\s+)?(?:ask(?:\s+you)?|say|tell\s+you|write)|what\s+was\s+my\s+last\s+(?:question|message|request)""")

    fun asks(text: String): Boolean = asked.containsMatchIn(text)

    /** Her answer, given the person's message before this one ([before]), or that there was none. */
    fun answer(before: String?): String {
        val last = before?.trim()?.takeIf { it.isNotEmpty() }
            ?: return tr("Ты меня пока ни о чём не просишь — спрашивай, мрр!", "You haven't asked me anything yet — go ahead, mrr!")
        val quote = last.replace(Regex("""\s+"""), " ").let { if (it.length > 160) it.take(158).trimEnd() + "…" else it }
        return if (ActionPromise.asks(last) || ActionPromise.wantsTimer(last))
            tr("Мрр, твоя последняя просьба: «$quote»", "Mrr, your last request was: “$quote”")
        else tr("Мрр, твоё последнее сообщение: «$quote»", "Mrr, your last message was: “$quote”")
    }
}
