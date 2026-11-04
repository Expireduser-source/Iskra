package ru.iskra.core.persona

import ru.iskra.core.i18n.tr

/**
 * Slowly adapted conversational preferences of the owner. Each axis is in [-1, 1] and is
 * only nudged by explicit feedback, so the core persona never drifts on its own.
 */
data class StyleProfile(
    val verbosity: Double = 0.0, // -1 terse .. 1 detailed
    val humor: Double = 0.0,     // -1 serious .. 1 jokey
    val catSpeech: Double = 0.0, // -1 no cat words .. 1 lots
) {
    fun clamp() = StyleProfile(verbosity.coerceIn(-1.0, 1.0), humor.coerceIn(-1.0, 1.0), catSpeech.coerceIn(-1.0, 1.0))

    fun describeForPrompt(): String {
        val parts = mutableListOf<String>()
        if (verbosity < -0.3) parts += tr("владелец просил отвечать короче", "the owner asked for shorter answers")
        if (verbosity > 0.3) parts += tr("владелец любит подробные ответы", "the owner likes detailed answers")
        if (humor < -0.3) parts += tr("владелец просил меньше шуток", "the owner asked for fewer jokes")
        if (humor > 0.3) parts += tr("владельцу нравятся твои шутки", "the owner enjoys your jokes")
        if (catSpeech < -0.3) parts += tr("почти не используй кошачьи словечки", "hardly use cat words")
        if (catSpeech > 0.3) parts += tr("владельцу нравятся кошачьи словечки", "the owner likes cat words")
        return if (parts.isEmpty()) "" else tr("Предпочтения владельца: ", "Owner's preferences: ") + parts.joinToString("; ") + "."
    }
}

object StyleFeedback {
    private const val STEP = 0.35

    /** Detects explicit style feedback in a user message and returns the adjusted profile, or null. */
    fun detect(text: String, profile: StyleProfile): StyleProfile? {
        val t = text.lowercase()
        var p = profile
        var changed = false
        fun adj(block: (StyleProfile) -> StyleProfile) { p = block(p); changed = true }
        if (Regex("""(отвечай|пиши|говори)\s+(покороче|короче|кратко|короче\b)""").containsMatchIn(t) || t.contains("слишком длинно"))
            adj { it.copy(verbosity = it.verbosity - STEP) }
        if (Regex("""(отвечай|пиши|говори|объясняй)\s+(подробнее|подробно|развёрнуто|развернуто)""").containsMatchIn(t))
            adj { it.copy(verbosity = it.verbosity + STEP) }
        if (t.contains("без шуток") || t.contains("хватит шутить") || t.contains("не шути"))
            adj { it.copy(humor = it.humor - STEP) }
        if (t.contains("ты смешная") || t.contains("шути ещё") || t.contains("шути еще"))
            adj { it.copy(humor = it.humor + STEP) }
        if (t.contains("хватит мяукать") || t.contains("без мрр") || t.contains("не мяукай"))
            adj { it.copy(catSpeech = it.catSpeech - STEP) }
        if (t.contains("мяукай") && !t.contains("не мяукай") && !t.contains("хватит мяукать"))
            adj { it.copy(catSpeech = it.catSpeech + STEP) }
        // English
        if (Regex("""\b(answer|reply|talk|speak|be)\s+(shorter|briefer|brief|more briefly)\b""").containsMatchIn(t) || t.contains("too long"))
            adj { it.copy(verbosity = it.verbosity - STEP) }
        if (Regex("""\b(answer|reply|explain)\s+(in more detail|in detail|more fully)\b|\bmore details?\b""").containsMatchIn(t))
            adj { it.copy(verbosity = it.verbosity + STEP) }
        if (Regex("""\b(no (more )?jokes|stop joking|don'?t joke)\b""").containsMatchIn(t))
            adj { it.copy(humor = it.humor - STEP) }
        if (Regex("""\b(you'?re funny|you are funny|joke more|more jokes)\b""").containsMatchIn(t))
            adj { it.copy(humor = it.humor + STEP) }
        val noMeow = Regex("""\b(stop meowing|no meow\w*|don'?t meow|no purring)\b""").containsMatchIn(t)
        if (noMeow) adj { it.copy(catSpeech = it.catSpeech - STEP) }
        if (!noMeow && Regex("""\b(meow more|more meows?|keep meowing)\b""").containsMatchIn(t))
            adj { it.copy(catSpeech = it.catSpeech + STEP) }
        return if (changed) p.clamp() else null
    }
}
