package ru.iskra.core.voice

import ru.iskra.core.persona.Emotion

/** Receives a reply while it is being generated: the emotion as soon as the tag is known, then speakable chunks. */
interface ReplySink {
    fun emotion(e: Emotion)
    /** A clean, speakable piece (one or a few sentences), in order. */
    fun sentence(text: String, emotion: Emotion)
}

/**
 * Turns a streamed reply into speakable chunks:
 *  - strips the leading emotion tag ("[happy] …") and reports the emotion right away;
 *  - cuts at sentence ends (. ! ? … ; new line) that are followed by a space, not at "т.е.",
 *    "г.", initials or list numbers;
 *  - the first chunk goes out at the first sentence end (fast first sound), later ones are merged
 *    up to [mergeChars] so the voice service gets fewer, more natural requests;
 *  - a long run without punctuation is cut at a comma or space after [maxChars].
 * Chunks are cleaned with [SpeechText.clean]; the total is capped at [SpeechText.MAX_CHARS].
 */
class ReplyStream(
    private val sink: ReplySink,
    private val fallbackEmotion: Emotion = Emotion.NEUTRAL,
    private val mergeChars: Int = 60,
    private val maxChars: Int = 220,
) {
    private val buf = StringBuilder()
    private var tagResolved = false
    private var emotion = fallbackEmotion
    private var emitted = 0
    private var spokenChars = 0
    private var finished = false

    val chunks get() = emitted

    private val think = ru.iskra.core.llm.ThinkFilter()

    fun feed(raw: String) {
        if (finished || raw.isEmpty()) return
        val delta = think.feed(raw)
        if (delta.isEmpty()) return
        buf.append(delta)
        if (!tagResolved) resolveTag(final = false)
        if (tagResolved) drain(final = false)
    }

    fun finish() {
        if (finished) return
        buf.append(think.finish())
        if (!tagResolved) resolveTag(final = true)
        drain(final = true)
        finished = true
    }

    private fun resolveTag(final: Boolean) {
        var start = 0
        while (start < buf.length && buf[start].isWhitespace()) start++
        if (start == buf.length) { if (final) tagResolved = true; return }
        if (buf[start] != '[') { tagResolved = true; return }
        val close = buf.indexOf("]", start)
        if (close < 0) {
            if (buf.length - start > 24 || final) tagResolved = true // not a tag after all
            return
        }
        val name = buf.substring(start + 1, close)
        val e = runCatching { Emotion.valueOf(name.trim().uppercase()) }.getOrNull()
        if (e != null) {
            emotion = e
            sink.emotion(e)
            buf.delete(0, close + 1)
        }
        tagResolved = true
    }

    private fun drain(final: Boolean) {
        while (true) {
            val cut = findCut(final) ?: break
            val piece = buf.substring(0, cut)
            buf.delete(0, cut)
            emit(piece)
        }
        if (final && buf.isNotBlank()) { emit(buf.toString()); buf.setLength(0) }
    }

    /** End index (exclusive) of the next chunk to speak, or null to wait for more text. */
    private fun findCut(final: Boolean): Int? {
        val need = if (emitted == 0) 2 else mergeChars
        var i = 0
        var lastSoft = -1
        while (i < buf.length) {
            val c = buf[i]
            if (c == ',' || c == ' ' && i > 0 && buf[i - 1] in ",:—–-") lastSoft = i + 1
            if (c in TERMINATORS || c == '\n') {
                var end = i + 1
                while (end < buf.length && (buf[end] in TERMINATORS || buf[end] in CLOSERS)) end++
                val atEnd = end >= buf.length
                if (atEnd && !final) return longCut(lastSoft) // the run may continue ("..." or "?!")
                val boundary = c == '\n' || atEnd || buf[end].isWhitespace()
                if (boundary && !(c == '.' && isAbbreviation(i)) && letters(0, end) >= 2 && end >= need) return end
                i = end
                continue
            }
            i++
        }
        return longCut(lastSoft)
    }

    private fun longCut(lastSoft: Int): Int? {
        if (buf.length <= maxChars) return null
        if (lastSoft > maxChars / 3) return lastSoft
        val space = buf.lastIndexOf(" ", maxChars)
        return if (space > maxChars / 3) space + 1 else maxChars
    }

    private fun letters(from: Int, to: Int): Int { var n = 0; for (k in from until to) if (buf[k].isLetter()) n++; return n }

    /** "т.е.", "г.", "ул.", initials "А.", list numbers "1." — a dot that does not end a sentence. */
    private fun isAbbreviation(dot: Int): Boolean {
        var s = dot
        while (s > 0 && buf[s - 1].isLetterOrDigit()) s--
        val word = buf.substring(s, dot)
        if (word.isEmpty()) return false
        if (word.all { it.isDigit() }) {
            // "1. Купить" at the start of a line, or a time/number "в 7." followed by digits.
            val lineStart = s == 0 || buf[s - 1] == '\n'
            val digitNext = dot + 1 < buf.length && buf[dot + 1].isDigit()
            return (lineStart && word.length <= 2) || digitNext
        }
        if (word.length == 1 && word[0].isUpperCase()) return true // initials
        val prevDot = s > 0 && buf[s - 1] == '.'
        return word.lowercase() in ABBREVIATIONS || (prevDot && word.length <= 2)
    }

    private fun emit(raw: String) {
        if (spokenChars >= SpeechText.MAX_CHARS) return
        val clean = SpeechText.clean(raw).take(SpeechText.MAX_CHARS - spokenChars)
        if (clean.isEmpty() || clean.none { it.isLetterOrDigit() }) return
        spokenChars += clean.length
        emitted++
        sink.sentence(clean, emotion)
    }

    companion object {
        private const val TERMINATORS = ".!?…;"
        private const val CLOSERS = ")»\"'”"
        private val ABBREVIATIONS = setOf(
            "т", "д", "г", "гг", "др", "пр", "см", "им", "ул", "стр", "руб", "коп", "тыс", "млн", "млрд",
            "мин", "сек", "ч", "кв", "пер", "просп", "обл", "р", "св", "напр", "т.е", "т.к", "т.д", "т.п",
            "mr", "mrs", "ms", "dr", "st", "vs", "etc", "e.g", "i.e", "approx", "no", "jr", "sr", "inc", "ltd", "a.m", "p.m",
        )

        /** Splits a finished text the same way (for replies that were not streamed). */
        fun split(text: String, emotion: Emotion = Emotion.NEUTRAL): List<String> {
            val out = ArrayList<String>()
            val s = ReplyStream(object : ReplySink {
                override fun emotion(e: Emotion) = Unit
                override fun sentence(text: String, emotion: Emotion) { out += text }
            }, emotion)
            s.feed(text); s.finish()
            return out
        }
    }
}
