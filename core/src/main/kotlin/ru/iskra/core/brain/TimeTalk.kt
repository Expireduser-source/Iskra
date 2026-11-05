package ru.iskra.core.brain

import ru.iskra.core.i18n.Plural
import ru.iskra.core.i18n.tr
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Exact answers about time and dates, without the internet: "который час", "какое сегодня число",
 * "сколько дней до Нового года", "какой день недели будет 1 января", "how many days since March 8",
 * "what day is it in 10 days". Models often get weekdays and day counts wrong; this never does.
 */
object TimeTalk {
    private val monthsRu = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val monthsEn = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val monthRuRe = """(январ[яеь]|феврал[яеь]|март[аеу]?|апрел[яеь]|ма[яейю]|июн[яеь]|июл[яеь]|август[аеу]?|сентябр[яеь]|октябр[яеь]|ноябр[яеь]|декабр[яеь])"""
    private val monthEnRe = """(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"""

    private val ordinalStems = listOf("перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят",
        "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат", "двадцат")
    private val ordinalRe = Regex("""(?:(двадцать|тридцать)\s+)?(перв|втор|трет|четверт|пят|шест|седьм|восьм|девят|десят|одиннадцат|двенадцат|тринадцат|четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат)(?:ого|ое|ье|ьего|ий|ый|ой)(?![\p{L}\p{N}_])""")

    /** "пятнадцатого марта" → "15 марта", "двадцать первое" → "21". */
    internal fun ordinalsToDigits(s: String): String = ordinalRe.replace(s) { m ->
        val tens = when (m.groupValues[1]) { "двадцать" -> 20; "тридцать" -> 30; else -> 0 }
        val stem = m.groupValues[2]
        val v = if (stem == "тридцат") 30 else ordinalStems.indexOf(stem) + 1
        (tens + v).toString()
    }

    private fun locale() = Locale.forLanguageTag(tr("ru", "en"))
    private fun dayText(d: LocalDate, today: LocalDate): String {
        val f = if (d.year == today.year) tr("EEEE, d MMMM", "EEEE, MMMM d") else tr("EEEE, d MMMM yyyy", "EEEE, MMMM d, yyyy")
        return DateTimeFormatter.ofPattern(f, locale()).format(d)
    }
    private fun days(n: Long) = Plural.count(n, "день", "дня", "дней", "day", "days")

    private enum class Mode { FUTURE, PAST }

    private val norm = Regex("""[?!.…]+$""")

    fun answer(text: String, now: LocalDateTime): String? {
        val t = ordinalsToDigits(NumberWords.digitize(text.lowercase().replace('ё', 'е')).trim()
            .removePrefix("искра,").removePrefix("iskra,").trim().replace(norm, "").trim()
            .removePrefix("а ").removePrefix("so ").removePrefix("and ").trim())
        val today = now.toLocalDate()

        if (TIME.matches(t)) return tr("Сейчас ${now.format(DateTimeFormatter.ofPattern("H:mm"))}.",
            "It's ${now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}.")
        if (YEAR.matches(t)) return tr("Сейчас ${today.year} год.", "It's ${today.year}.")
        if (TODAY.matches(t)) return tr("Сегодня ${dayText(today, today)}.", "Today is ${dayText(today, today)}.")
        TOMORROW.find(t)?.let {
            val afterTomorrow = it.value.contains("послезавтра") || it.value.contains("after tomorrow")
            val d = if (afterTomorrow) today.plusDays(2) else if (it.value.contains("вчера") || it.value.contains("yesterday")) today.minusDays(1) else today.plusDays(1)
            val word = when {
                afterTomorrow -> tr("Послезавтра", "The day after tomorrow is")
                d.isBefore(today) -> tr("Вчера было", "Yesterday was")
                else -> tr("Завтра", "Tomorrow is")
            }
            return "$word ${dayText(d, today)}."
        }
        IN_DAYS.find(t)?.let { m ->
            val n = m.groupValues[1].ifEmpty { m.groupValues[3] }.toLongOrNull() ?: return null
            val unit = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val d = when {
                unit.startsWith("нед") || unit.startsWith("week") -> today.plusWeeks(n)
                unit.startsWith("мес") || unit.startsWith("month") -> today.plusMonths(n)
                unit.startsWith("год") || unit.startsWith("лет") || unit.startsWith("year") -> today.plusYears(n)
                else -> today.plusDays(n)
            }
            if (n > 100_000) return null
            return tr("Это будет ${dayText(d, today)}.", "That will be ${dayText(d, today)}.")
        }
        UNTIL.find(t)?.let { m ->
            val what = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            val d = date(what, today, Mode.FUTURE) ?: return null
            val n = ChronoUnit.DAYS.between(today, d)
            return when (n) {
                0L -> tr("Это сегодня!", "That's today!")
                1L -> tr("Это уже завтра — ${dayText(d, today)}.", "That's tomorrow — ${dayText(d, today)}.")
                else -> tr("До $what осталось ${days(n)} — это ${dayText(d, today)}.",
                    "${days(n)} until $what — ${dayText(d, today)}.").replaceFirstChar { it.uppercase() }
            }
        }
        SINCE.find(t)?.let { m ->
            val what = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
            val d = date(what, today, Mode.PAST) ?: return null
            val n = ChronoUnit.DAYS.between(d, today)
            if (n == 0L) return tr("Это сегодня!", "That's today!")
            return tr("С $what прошло ${days(n)} (${dayText(d, today)}).", "${days(n)} since $what (${dayText(d, today)}).").replaceFirstChar { it.uppercase() }
        }
        WEEKDAY.find(t)?.let { m ->
            val what = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: return null
            val past = Regex("""(?<![\p{L}\p{N}_])(?:был|была|было|was)(?![\p{L}\p{N}_])""").containsMatchIn(t)
            val d = date(what, today, if (past) Mode.PAST else Mode.FUTURE) ?: return null
            val weekday = DateTimeFormatter.ofPattern("EEEE", locale()).format(d)
            val full = DateTimeFormatter.ofPattern(tr("d MMMM yyyy", "MMMM d, yyyy"), locale()).format(d)
            return tr("$full — $weekday.", "$full is a $weekday.").replaceFirstChar { it.uppercase() }
        }
        return null
    }

    private val TIME = Regex("""^(?:который\s+(?:сейчас\s+)?час|сколько\s+(?:сейчас\s+)?времени|какое\s+(?:сейчас\s+)?время|скажи\s+(?:который\s+час|время|сколько\s+времени)|время|""" +
        """what\s+time\s+is\s+it(?:\s+now)?|what's\s+the\s+time|what\s+is\s+the\s+time|tell\s+me\s+the\s+time|time)$""")
    private val YEAR = Regex("""^(?:какой\s+(?:сейчас\s+)?год(?:\s+сейчас)?|what\s+year\s+is\s+it)$""")
    private val TODAY = Regex("""^(?:какое\s+(?:сегодня\s+)?число(?:\s+сегодня)?|сегодня\s+какое\s+число|какая\s+(?:сегодня\s+)?дата(?:\s+сегодня)?|какой\s+(?:сегодня\s+)?день(?:\s+недели)?(?:\s+сегодня)?|""" +
        """сегодня\s+какой\s+день(?:\s+недели)?|what(?:'s|\s+is)\s+(?:the\s+date|today's\s+date|the\s+date\s+today|today)|what\s+day\s+is\s+(?:it|today)(?:\s+today)?|""" +
        """what\s+day\s+of\s+the\s+week\s+is\s+it(?:\s+today)?|what\s+date\s+is\s+it(?:\s+today)?)$""")
    private val TOMORROW = Regex("""^(?:какое\s+число\s+(?:будет\s+)?(?:послезавтра|завтра)|какой\s+день(?:\s+недели)?\s+(?:будет\s+)?(?:послезавтра|завтра)|какое\s+число\s+было\s+вчера|""" +
        """какой\s+день(?:\s+недели)?\s+был\s+вчера|(?:послезавтра|завтра)\s+какое\s+число|what\s+day\s+is\s+(?:it\s+)?(?:the\s+day\s+after\s+tomorrow|tomorrow)|""" +
        """what(?:'s|\s+is)\s+the\s+date\s+tomorrow|what\s+day\s+was\s+yesterday)$""")
    private val IN_DAYS = Regex("""^(?:какое\s+число|какой\s+день(?:\s+недели)?|какая\s+дата)\s+будет\s+через\s+(\d+)\s+(дн\p{L}*|день|недел\p{L}*|месяц\p{L}*|год\p{L}*|лет)$|""" +
        """^what\s+(?:day|date)\s+(?:will\s+it\s+be|is\s+it)\s+in\s+(\d+)\s+(days?|weeks?|months?|years?)$""")
    private val UNTIL = Regex("""^сколько\s+(?:(?:еще\s+)?(?:дней\s+)?(?:осталось\s+)?(?:дней\s+)?|ждать\s+)до\s+(.+)$|^how\s+many\s+days\s+(?:are\s+)?(?:left\s+)?(?:until|till|to|before)\s+(.+)$|^days\s+(?:until|till|to)\s+(.+)$|^how\s+long\s+(?:until|till)\s+(.+)$""")
    private val SINCE = Regex("""^сколько\s+(?:дней\s+)?прошло\s+(?:с|со|после)\s+(.+)$|^сколько\s+дней\s+(?:с|со)\s+(.+)$|^how\s+many\s+days\s+(?:have\s+passed\s+)?since\s+(.+)$|^how\s+long\s+since\s+(.+)$""")
    private val WEEKDAY = Regex("""^(?:какой|каким)\s+день(?:\s+недели)?\s+(?:будет|был|было|приходится\s+на|выпадает\s+на)\s+(.+)$|^на\s+какой\s+день(?:\s+недели)?\s+(?:выпадает|приходится|попадает)\s+(.+)$|""" +
        """^what\s+day(?:\s+of\s+the\s+week)?\s+(?:is|was|will\s+be|falls\s+on)\s+(.+)$""")

    private val weekdaysRu = listOf("понедельник", "вторник", "сред", "четверг", "пятниц", "суббот", "воскресен")
    private val weekdaysEn = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    /** A date described in words: a holiday, "15 марта", "15.03.2027", "March 15", a weekday, "summer". */
    private fun date(raw: String, today: LocalDate, mode: Mode): LocalDate? {
        val s = raw.trim().replace(Regex("""(\d{4})\s+(?:года|г\.?)$"""), "$1").removeSuffix(" числа").trim()
        fun md(month: Int, day: Int, year: Int? = null): LocalDate? {
            val y = year ?: today.year
            val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: return null
            if (year != null) return d
            return when (mode) {
                Mode.FUTURE -> if (d.isBefore(today)) d.plusYears(1) else d
                Mode.PAST -> if (d.isAfter(today)) d.minusYears(1) else d
            }
        }
        holidays[s.removePrefix("the ")]?.let { (m, d) -> return md(m, d) }
        if (s == "рождества" || s == "рождество") return md(1, 7)
        when (s) {
            "сегодня", "today" -> return today
            "завтра", "tomorrow" -> return today.plusDays(1)
            "послезавтра", "the day after tomorrow" -> return today.plusDays(2)
            "вчера", "yesterday" -> return today.minusDays(1)
            "конца года", "конец года", "the end of the year", "end of the year" -> return LocalDate.of(today.year, 12, 31)
            "конца месяца", "конец месяца", "the end of the month", "end of the month" -> return today.with(TemporalAdjusters.lastDayOfMonth())
            "выходных", "выходные", "the weekend", "weekend" -> return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        }
        Regex("""^(\d{1,2})\s+$monthRuRe(?:\s+(\d{4}))?$""").find(s)?.let { m ->
            val month = monthsRu.indexOfFirst { m.groupValues[2].startsWith(it) && !(it == "ма" && m.groupValues[2].startsWith("март")) } + 1
            return md(month, m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull())
        }
        Regex("""^(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?$""").find(s)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            return md(m.groupValues[2].toInt(), m.groupValues[1].toInt(), y)
        }
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(s)?.let { m ->
            return md(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toInt())
        }
        Regex("""^$monthEnRe\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""").find(s)?.let { m ->
            return md(enMonth(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())
        }
        Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$monthEnRe(?:,?\s+(\d{4}))?$""").find(s)?.let { m ->
            return md(enMonth(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull())
        }
        val last = s.split(' ').last()
        val wdRu = weekdaysRu.indexOfFirst { last.startsWith(it) }
        val wdEn = weekdaysEn.indexOfFirst { last == it }
        val wd = if (wdRu >= 0) wdRu else wdEn
        if (wd >= 0 && s.split(' ').size <= 2) {
            val dow = DayOfWeek.of(wd + 1)
            return if (mode == Mode.PAST) today.with(TemporalAdjusters.previous(dow)) else today.with(TemporalAdjusters.next(dow))
        }
        return null
    }

    private fun enMonth(s: String) = monthsEn.indexOfFirst { it.startsWith(s.take(3)) } + 1

    /** The day and month in a phrase like "12 мая 1990", "двенадцатого мая", "12.05", "May 12th" (the year is ignored). */
    fun monthDay(text: String): java.time.MonthDay? {
        val s = ordinalsToDigits(NumberWords.digitize(text.lowercase().replace('ё', 'е')))
        fun md(m: Int, d: Int) = runCatching { java.time.MonthDay.of(m, d) }.getOrNull()
        Regex("""(\d{1,2})\s+$monthRuRe""").find(s)?.let { m ->
            val month = monthsRu.indexOfFirst { m.groupValues[2].startsWith(it) } + 1
            return md(month, m.groupValues[1].toInt())
        }
        Regex("""$monthEnRe\s+(\d{1,2})""").find(s)?.let { m -> return md(enMonth(m.groupValues[1]), m.groupValues[2].toInt()) }
        Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$monthEnRe""").find(s)?.let { m -> return md(enMonth(m.groupValues[2]), m.groupValues[1].toInt()) }
        Regex("""(\d{1,2})\.(\d{1,2})""").find(s)?.let { m -> return md(m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        return null
    }

    /** Holidays by the words people use after "до" / "until". */
    private val holidays: Map<String, Pair<Int, Int>> get() = HOLIDAYS + mapOf(
        "christmas" to (12 to 25), "christmas day" to (12 to 25), "christmas eve" to (12 to 24),
    )

    private val HOLIDAYS = mapOf(
        "нового года" to (1 to 1), "новый год" to (1 to 1), "нг" to (1 to 1), "new year" to (1 to 1), "new year's" to (1 to 1),
        "new years" to (1 to 1), "new year's day" to (1 to 1), "new year's eve" to (12 to 31),
        "дня святого валентина" to (2 to 14), "дня влюбленных" to (2 to 14), "валентинова дня" to (2 to 14),
        "valentine's day" to (2 to 14), "valentines day" to (2 to 14), "valentine's" to (2 to 14),
        "23 февраля" to (2 to 23), "дня защитника отечества" to (2 to 23),
        "8 марта" to (3 to 8), "международного женского дня" to (3 to 8), "женского дня" to (3 to 8),
        "дня победы" to (5 to 9), "дня знаний" to (9 to 1),
        "хэллоуина" to (10 to 31), "хеллоуина" to (10 to 31), "halloween" to (10 to 31),
        "весны" to (3 to 1), "лета" to (6 to 1), "осени" to (9 to 1), "зимы" to (12 to 1),
        "spring" to (3 to 1), "summer" to (6 to 1), "fall" to (9 to 1), "autumn" to (9 to 1), "winter" to (12 to 1),
    )
}
