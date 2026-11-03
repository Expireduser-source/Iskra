package ru.iskra.core.agent

import ru.iskra.core.persona.Emotion

/**
 * The model is asked to start its reply with an emotion tag like "[happy]".
 * This is provider-agnostic (no special API needed) and robust: a missing/unknown tag is ignored.
 */
object ReplyParser {
    private val tag = Regex("""^\s*\[(\p{L}+)]\s*""")

    data class Parsed(val text: String, val emotion: Emotion?)

    fun parse(raw: String): Parsed {
        var text = raw.trim()
        var emotion: Emotion? = null
        tag.find(text)?.let { m ->
            emotion = runCatching { Emotion.valueOf(m.groupValues[1].uppercase()) }.getOrNull()
            if (emotion != null) text = text.substring(m.range.last + 1).trim()
        }
        // Defensive cleanup of stray tags anywhere in the text.
        text = text.replace(Regex("""\[(?:${Emotion.entries.joinToString("|") { it.name.lowercase() }})]""", RegexOption.IGNORE_CASE), "").trim()
        return Parsed(text, emotion)
    }
}
