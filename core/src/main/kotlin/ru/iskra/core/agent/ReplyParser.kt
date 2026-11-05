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
        return Parsed(text, emotion)
    }
}
