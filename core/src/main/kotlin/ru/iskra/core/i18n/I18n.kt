package ru.iskra.core.i18n

/**
 * The two languages Iskra speaks. Russian is her first language; English is for everyone else.
 *
 * Texts live next to each other in the code — `tr("Привет!", "Hi!")` — so a sentence and its translation
 * are changed together and nothing can be left untranslated by a missing key. The current language is a
 * process-wide setting like [java.util.Locale.getDefault]: the app sets it at start and when the owner
 * switches it; tests that need English use [withLang].
 */
enum class Lang(val tag: String) {
    RU("ru"), EN("en");

    companion object {
        /** Languages whose speakers usually read Russian (Russian is official or common there). */
        private val RUSSIAN_READERS = setOf("ru", "be", "kk", "ky", "tg")

        /** The phone's language → Iskra's language: Russian for [RUSSIAN_READERS], English for everyone else. */
        fun forLocale(language: String?): Lang = if (language?.lowercase()?.take(2) in RUSSIAN_READERS) RU else EN

        fun of(tag: String?): Lang? = entries.firstOrNull { it.tag == tag }
    }
}

object I18n {
    /** Current language. Set by the app; read from any thread. */
    @Volatile
    var lang: Lang = Lang.RU

    val isEnglish: Boolean get() = lang == Lang.EN
}

/** Russian and English side by side; the current language picks one. Works for strings and lists alike. */
fun <T> tr(ru: T, en: T): T = if (I18n.lang == Lang.EN) en else ru

/** Runs [block] in [lang] and restores the previous language (tests, or text for a specific listener). */
inline fun <T> withLang(lang: Lang, block: () -> T): T {
    val old = I18n.lang
    I18n.lang = lang
    try {
        return block()
    } finally {
        I18n.lang = old
    }
}

object Plural {
    /** Russian: 1 минута, 2 минуты, 5 минут, 21 минута, 11 минут. */
    fun ru(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    fun en(n: Long, one: String, other: String): String = if (n == 1L) one else other

    /** "5 минут" / "5 minutes" in the current language. */
    fun count(n: Long, ruOne: String, ruFew: String, ruMany: String, enOne: String, enOther: String): String =
        "$n " + tr(ru(n, ruOne, ruFew, ruMany), en(n, enOne, enOther))
}
