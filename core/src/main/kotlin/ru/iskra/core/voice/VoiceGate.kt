package ru.iskra.core.voice

/**
 * Finds the companion's name at the start of a recognised phrase ("Искра, поставь таймер",
 * "эй искорка", "искру позови", "Spark, set a timer"...). Recognisers often bend the word, so a match is: same word,
 * a different case ending of it, or one edit away (for names of 4+ letters).
 *
 * The name must be among the first [maxLeadingWords] words, so stories that merely mention a spark
 * ("я видел искру в проводе") do not wake her.
 */
class WakeWordMatcher(names: List<String>, private val maxLeadingWords: Int = 3) {
    private val targets = names.map(::norm).filter { it.length >= 3 }.ifEmpty { listOf("искра") }

    data class Match(val remainder: String)

    fun match(text: String): Match? {
        val words = tokens(text)
        val limit = minOf(words.size, maxLeadingWords)
        for (i in 0 until limit) {
            if (isName(words[i])) {
                val rest = words.drop(i + 1).dropWhile { it in fillersAfterName }
                return Match(rest.joinToString(" "))
            }
        }
        return null
    }

    fun isName(word: String): Boolean = targets.any { t ->
        val russian = t.any { it in 'а'..'я' }
        word == t ||
            // case endings: искра → искру/искре/искрой/искры (Russian names only: "spark" must not match "spare")
            (russian && t.length >= 4 && word.length in t.length - 1..t.length + 2 && word.startsWith(t.dropLast(1))) ||
            (t.length >= (if (russian) 4 else 5) && levenshtein(word, t) <= 1 && (russian || word.first() == t.first()))
    }

    companion object {
        private val fillersAfterName = setOf("пожалуйста", "слушай", "а", "ну", "please", "hey", "ok", "okay", "listen", "so")

        fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()

        fun tokens(text: String): List<String> =
            norm(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}

/**
 * Decides what to do with each phrase from an always-on recogniser. Pure logic, clock passed in.
 *
 *  WAITING   — only phrases that start with her name count. "Искра" alone → she perks up and
 *              waits for the command; "Искра, который час" → command right away.
 *  ATTENTIVE — the next phrase is a command without the name. Entered after the bare name, and
 *              (optionally) for a few seconds after she finished answering, so a dialogue flows.
 */
class VoiceGate(
    private val matcher: WakeWordMatcher,
    private val attentiveMs: Long = 7_000,
    private val followUpMs: Long = 6_000,
) {
    enum class Mode { WAITING, ATTENTIVE }

    sealed interface Action {
        data object Ignore : Action
        /** Name heard, waiting for the command: show that she is listening. */
        data object Perk : Action
        data class Command(val text: String) : Action
        /** "Искра, стоп" — interrupt whatever she is doing. */
        data object Stop : Action
        /** Attentive window ran out. */
        data object Timeout : Action
    }

    var mode = Mode.WAITING; private set
    private var deadline = 0L
    private var perkedThisPhrase = false

    /** Partial (unfinished) phrase. Returns true the first time the name shows up in it. */
    fun onPartial(text: String, nowMs: Long): Boolean {
        if (perkedThisPhrase || mode == Mode.ATTENTIVE) return false
        if (matcher.match(text) != null) { perkedThisPhrase = true; deadline = nowMs + attentiveMs; return true }
        return false
    }

    fun onFinal(text: String, nowMs: Long): Action {
        perkedThisPhrase = false
        val words = WakeWordMatcher.tokens(text)
        if (words.isEmpty() || isNoise(words)) return Action.Ignore
        val match = matcher.match(text)
        val command = when {
            match != null -> match.remainder
            mode == Mode.ATTENTIVE && nowMs <= deadline -> words.joinToString(" ")
            else -> return Action.Ignore
        }
        if (command.isBlank()) {
            mode = Mode.ATTENTIVE; deadline = nowMs + attentiveMs
            return Action.Perk
        }
        mode = Mode.WAITING
        return if (isStop(command)) Action.Stop else Action.Command(command)
    }

    /** She finished answering; listen for a reply without the name if [followUp] is on. */
    fun onReplyFinished(nowMs: Long, followUp: Boolean) {
        if (followUp) { mode = Mode.ATTENTIVE; deadline = nowMs + followUpMs }
    }

    fun tick(nowMs: Long): Action? {
        if (mode == Mode.ATTENTIVE && nowMs > deadline) { mode = Mode.WAITING; return Action.Timeout }
        return null
    }

    fun reset() { mode = Mode.WAITING; perkedThisPhrase = false }

    private fun isNoise(words: List<String>) =
        words.all { it.length <= 1 || it in noise }

    private fun isStop(command: String): Boolean {
        val w = WakeWordMatcher.tokens(command)
        return w.size <= 2 && w.any { it in stopWords }
    }

    private companion object {
        val noise = setOf("а", "и", "э", "м", "ну", "угу", "ага", "эм", "хм", "о", "у", "uh", "um", "hmm", "hm", "oh", "ah", "mm", "huh", "the", "a")
        val stopWords = setOf("стоп", "хватит", "замолчи", "тихо", "помолчи", "отмена", "перестань", "остановись",
            "stop", "enough", "quiet", "silence", "cancel", "shush", "hush")
    }
}
