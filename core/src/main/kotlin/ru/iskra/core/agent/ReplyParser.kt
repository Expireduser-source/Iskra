package ru.iskra.core.agent

import ru.iskra.core.persona.Emotion

/**
 * The model is asked to start its reply with an emotion tag like "[happy]".
 * This is provider-agnostic (no special API needed) and robust: a missing/unknown tag is ignored.
 */
object ReplyParser {
    private val tag = Regex("""^\s*\[(\p{L}+)]\s*""")

    data class Parsed(val text: String, val emotion: Emotion?)

    /** Bracketed words at the very start, up to three words each: "[mrр]", "[Mood: Curious]". */
    private val LEADING_TAGS = Regex("""^(?:\s*\[[^\]\s]{1,20}(?:\s[^\]\s]{1,20}){0,2}])+\s*""")

    private val PLACEHOLDER = Regex("""(?iu)(?:\b(?:Mr|Ms|Mrs)\.?/?(?:Mr|Ms|Mrs)?\.?\s*)?\[[^\]]{0,24}(?:name|имя|user|пользовател)[^\]]{0,24}],?\s*""")

    /** Removes unfilled slots; a sentence that began with one now begins with the next word, capitalised. */
    private fun withoutSlots(text: String): String {
        val out = StringBuilder()
        var last = 0
        var capitalise = false
        for (m in PLACEHOLDER.findAll(text)) {
            out.append(text, last, m.range.first)
            last = m.range.last + 1
            val before = out.toString().trimEnd()
            capitalise = before.isEmpty() || before.last() in ".!?"
            if (capitalise) {
                // Keep one space after the sentence end; the next word starts the sentence.
                out.setLength(before.length); if (before.isNotEmpty()) out.append(' ')
                val rest = text.substring(last)
                val i = rest.indexOfFirst { it.isLetter() }
                if (i >= 0) { out.append(rest.substring(0, i)); out.append(rest[i].uppercaseChar()); last += i + 1 }
            }
        }
        out.append(text.substring(last))
        return out.toString().replace(Regex("""\s+(?=[,.!?])"""), "").replace(Regex(""",(?=[,.!?])"""), "").replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun parse(raw: String): Parsed {
        var text = ru.iskra.core.llm.ThinkFilter.strip(raw)
        var emotion: Emotion? = null
        tag.find(text)?.let { m ->
            emotion = runCatching { Emotion.valueOf(m.groupValues[1].uppercase()) }.getOrNull()
            if (emotion != null) text = text.substring(m.range.last + 1).trim()
        }
        // Defensive cleanup of stray tags anywhere in the text.
        text = text.replace(Regex("""\[(?:${Emotion.entries.joinToString("|") { it.name.lowercase() }})]""", RegexOption.IGNORE_CASE), "").trim()
        // Small models add their own: "[mrр] Мрр…", "[Mood: Curious] …", or wrap the whole answer: "[I love winter evenings.]".
        text = text.replace(LEADING_TAGS, "").trim()
        if (text.length > 2 && text.first() == '[' && text.last() == ']' && text.count { it == '[' } == 1) text = text.substring(1, text.length - 1).trim()
        // A template slot left unfilled: "Mr./Ms. [person's name], what are you up to?" (Gemma 4 E2B in the evaluation).
        if (PLACEHOLDER.containsMatchIn(text)) text = withoutSlots(text)
        return Parsed(text, emotion)
    }
}
