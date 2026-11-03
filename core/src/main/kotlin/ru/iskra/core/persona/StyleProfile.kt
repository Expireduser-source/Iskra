package ru.iskra.core.persona

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
        if (verbosity < -0.3) parts += "владелец просил отвечать короче"
        if (verbosity > 0.3) parts += "владелец любит подробные ответы"
        if (humor < -0.3) parts += "владелец просил меньше шуток"
        if (humor > 0.3) parts += "владельцу нравятся твои шутки"
        if (catSpeech < -0.3) parts += "почти не используй кошачьи словечки"
        if (catSpeech > 0.3) parts += "владельцу нравятся кошачьи словечки"
        return if (parts.isEmpty()) "" else "Предпочтения владельца: " + parts.joinToString("; ") + "."
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
        return if (changed) p.clamp() else null
    }
}
