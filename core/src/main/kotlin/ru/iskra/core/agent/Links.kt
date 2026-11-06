package ru.iskra.core.agent

/**
 * What in a reply can be tapped: web links and phone numbers (the chat opens the browser or the dialer — calling is the
 * owner's own tap there). Long numbers always («+7 495 989-50-50», «8-800-2000-122»); short help numbers (112, 911, 988,
 * 999, 116 123) only in a sentence that says to call, so «112 рублей» stays text.
 */
object Links {
    data class Span(val start: Int, val end: Int, val uri: String)

    private val url = Regex("""https?://[^\s<>«»"]*[^\s<>«»".,;:!?)\]]""")

    /** «+7 495 989-50-50», «8-800-2000-122», «8 (800) 100-49-94». */
    val longPhone = Regex("""(?<![\p{L}\p{N}+])(?:\+\d(?:[\s()-]{0,2}\d){7,14}|8[\s-]?\(?800\)?(?:[\s-]?\d){7})(?![\p{N}])""")

    private val shortPhone = Regex("""(?<![\p{N}.,])(?:116[\s-]?123|112|911|988|999)(?![\p{N}]|[.,]\d)""")
    private val callWord = Regex("""(?iu)(?<!\p{L})(?:звони|позвони|звоните|позвоните|набери|наберите|номер|телефон|call|text|dial|phone|ring)""")

    fun find(text: String): List<Span> {
        val spans = ArrayList<Span>()
        url.findAll(text).forEach { spans += Span(it.range.first, it.range.last + 1, it.value) }
        fun free(r: IntRange) = spans.none { r.first < it.end && it.start <= r.last }
        longPhone.findAll(text).filter { free(it.range) }.forEach { spans += Span(it.range.first, it.range.last + 1, tel(it.value)) }
        shortPhone.findAll(text).filter { free(it.range) }.forEach { m ->
            val sentence = text.substring(0, m.range.first).split(Regex("""[.!?\n]\s""")).last()
            if (callWord.containsMatchIn(sentence)) spans += Span(m.range.first, m.range.last + 1, tel(m.value))
        }
        return spans.sortedBy { it.start }
    }

    private fun tel(number: String) = "tel:" + number.filter { it.isDigit() || it == '+' }

    /** For the voice: «8-800-2000-122» → «8, 800, 2000, 122», so it is read in groups, not as one long number or with dashes. */
    fun speakable(text: String): String = longPhone.replace(text) { m ->
        m.value.split(Regex("""[\s()-]+""")).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
