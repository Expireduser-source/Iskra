package ru.iskra.core.skills

import ru.iskra.core.i18n.tr

/** What the owner can do with text shared to Iskra or selected in another app. */
enum class ShareAction { EXPLAIN, SUMMARIZE, TRANSLATE, REMEMBER, NOTE, REMIND }

/**
 * Text that came from another app ("Share → Iskra", or "Ask Iskra" in the text-selection menu).
 * Nothing happens until the owner taps an action: then it becomes an ordinary message, so the usual
 * rules apply (offline commands for memory/notes/reminders, confirmations for risky tools).
 */
object SharedText {
    const val MAX_CHARS = 3500

    fun clean(text: CharSequence?): String? = text?.toString()?.trim()?.take(MAX_CHARS)?.takeIf { it.isNotBlank() }

    /** Actions that make sense for [text]. Explaining, summarizing and translating need the cloud model. */
    fun actions(text: String, brain: Boolean): List<ShareAction> = buildList {
        if (brain) { add(ShareAction.EXPLAIN); add(ShareAction.SUMMARIZE); add(ShareAction.TRANSLATE) }
        val n = flat(text).length
        if (n <= 300) add(ShareAction.REMEMBER)
        if (n <= 1000) add(ShareAction.NOTE)
        if (n <= 200) add(ShareAction.REMIND)
    }

    fun title(a: ShareAction): String = when (a) {
        ShareAction.EXPLAIN -> tr("Объясни", "Explain")
        ShareAction.SUMMARIZE -> tr("Кратко", "Summarize")
        ShareAction.TRANSLATE -> tr("Переведи", "Translate")
        ShareAction.REMEMBER -> tr("Запомни", "Remember")
        ShareAction.NOTE -> tr("В заметки", "To notes")
        ShareAction.REMIND -> tr("Напомни", "Remind me")
    }

    /** True: put [message] into the input so the owner can edit it (the reminder time) instead of sending it. */
    fun editFirst(a: ShareAction) = a == ShareAction.REMIND

    /** The message Iskra gets for [a]. */
    fun message(a: ShareAction, text: String): String {
        val flat = flat(text)
        return when (a) {
            ShareAction.EXPLAIN -> tr("Объясни простыми словами: «$text»", "Explain in simple words: “$text”")
            ShareAction.SUMMARIZE -> tr("Перескажи коротко: «$text»", "Summarize briefly: “$text”")
            ShareAction.TRANSLATE -> tr("Переведи: «$text»", "Translate: “$text”")
            ShareAction.REMEMBER -> tr("Запомни: $flat", "Remember: $flat")
            ShareAction.NOTE -> tr("Запиши заметку: $flat", "Note: $flat")
            ShareAction.REMIND -> tr("Напомни через час $flat", "Remind me in an hour to $flat")
        }
    }

    private fun flat(t: String) = t.replace(Regex("\\s+"), " ").trim()

    /** Without a model only memory, notes and reminders work: say so instead of failing later. */
    val needsBrainHint: String
        get() = tr("Объяснить, пересказать и перевести Искра сможет, когда подключишь «мозг» в настройках — это можно бесплатно.",
            "Iskra can explain, summarize and translate once you connect a “brain” in Settings — it can be free.")
}
