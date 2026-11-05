package ru.iskra.core.brain

import ru.iskra.core.i18n.Plural
import ru.iskra.core.i18n.tr
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.round

/**
 * Unit conversion without the internet: length, weight, temperature, volume, speed, area, data and time, in Russian
 * (with its case endings) and English. "переведи 5 миль в километры", "сколько сантиметров в дюйме",
 * "100 fahrenheit to celsius", "how many ounces in a pound".
 */
object Units {
    enum class Dim { LENGTH, MASS, TEMP, VOLUME, SPEED, AREA, DATA, TIME }

    /**
     * [factor] converts to the dimension's base unit (temperature is special-cased).
     * [ru] is an abbreviation that never changes, or four forms: 1 миля / 2 мили / 5 миль / 2,5 мили.
     */
    class Unit(val id: String, val dim: Dim, val factor: Double, val ru: List<String>, val en: List<String>, val aliases: List<String>) {
        val patterns = aliases.map { Regex(it) }
        fun name(v: Double): String {
            val whole = v == round(v) && abs(v) < 1e15
            return tr(
                if (ru.size == 1) ru[0] else if (!whole) ru[3] else Plural.ru(abs(v).toLong(), ru[0], ru[1], ru[2]),
                if (en.size == 1) en[0] else if (whole && abs(v) == 1.0) en[0] else en[1],
            )
        }
    }

    private fun u(id: String, dim: Dim, factor: Double, ru: String, en: String, vararg aliases: String) =
        Unit(id, dim, factor, ru.split('|'), en.split('|'), aliases.toList())

    private const val L = """\p{L}{0,3}"""

    val all: List<Unit> = listOf(
        u("mm", Dim.LENGTH, 0.001, "мм", "mm", "мм", "миллиметр$L", "mm", "millimet(?:er|re)s?"),
        u("cm", Dim.LENGTH, 0.01, "см", "cm", "см", "сантиметр$L", "cm", "centimet(?:er|re)s?"),
        u("m", Dim.LENGTH, 1.0, "м", "m", "м", "метр$L", "m", "met(?:er|re)s?"),
        u("km", Dim.LENGTH, 1000.0, "км", "km", "км", "километр$L", "km", "kilomet(?:er|re)s?"),
        u("in", Dim.LENGTH, 0.0254, "дюйм|дюйма|дюймов|дюйма", "inch|inches", "дюйм$L", "in", "inch(?:es)?"),
        u("ft", Dim.LENGTH, 0.3048, "фут|фута|футов|фута", "foot|feet", "фут$L", "ft", "foot", "feet"),
        u("yd", Dim.LENGTH, 0.9144, "ярд|ярда|ярдов|ярда", "yard|yards", "ярд$L", "yd", "yards?"),
        u("mi", Dim.LENGTH, 1609.344, "миля|мили|миль|мили", "mile|miles", "мил(?:я|и|ю|е|ь|ях|ями|ям)", "mi", "miles?"),
        u("nmi", Dim.LENGTH, 1852.0, "морская миля|морские мили|морских миль|морской мили", "nautical mile|nautical miles",
            "морск\\p{L}* мил\\p{L}{1,3}", "nautical miles?"),

        u("mg", Dim.MASS, 1e-6, "мг", "mg", "мг", "миллиграмм$L", "mg", "milligrams?"),
        u("g", Dim.MASS, 0.001, "г", "g", "г", "гр", "грамм$L", "g", "grams?"),
        u("kg", Dim.MASS, 1.0, "кг", "kg", "кг", "килограмм$L", "кило", "kg", "kilograms?", "kilos?"),
        u("t", Dim.MASS, 1000.0, "т", "t", "т", "тонн$L", "tons?", "tonnes?"),
        u("lb", Dim.MASS, 0.45359237, "фунт|фунта|фунтов|фунта", "pound|pounds", "фунт$L", "lbs?", "pounds?"),
        u("oz", Dim.MASS, 0.028349523125, "унция|унции|унций|унции", "ounce|ounces", "унци\\p{L}{1,3}", "oz", "ounces?"),
        u("st", Dim.MASS, 6.35029318, "стоун|стоуна|стоунов|стоуна", "stone|stones", "стоун$L", "stones?"),

        u("c", Dim.TEMP, 1.0, "°C", "°C", "градус\\p{L}* цельси\\p{L}*", "цельси\\p{L}*", "°c", "°с", "c", "с", "celsius", "degrees? celsius", "centigrade"),
        u("f", Dim.TEMP, 1.0, "°F", "°F", "градус\\p{L}* фаренгейт\\p{L}*", "фаренгейт\\p{L}*", "°f", "f", "fahrenheit", "degrees? fahrenheit"),
        u("k", Dim.TEMP, 1.0, "K", "K", "кельвин\\p{L}*", "kelvins?", "°k"),

        u("ml", Dim.VOLUME, 0.001, "мл", "ml", "мл", "миллилитр$L", "ml", "millilit(?:er|re)s?"),
        u("l", Dim.VOLUME, 1.0, "л", "l", "л", "литр$L", "l", "lit(?:er|re)s?"),
        u("gal", Dim.VOLUME, 3.785411784, "галлон|галлона|галлонов|галлона", "gallon|gallons", "галлон$L", "gal", "gallons?"),
        u("pt", Dim.VOLUME, 0.473176473, "пинта|пинты|пинт|пинты", "pint|pints", "пинт\\p{L}{0,2}", "pints?"),
        u("cup", Dim.VOLUME, 0.2365882365, "чашка|чашки|чашек|чашки", "cup|cups", "чаш(?:ка|ки|ек|ку|ке)", "cups?"),
        u("glass", Dim.VOLUME, 0.25, "стакан|стакана|стаканов|стакана", "glass|glasses", "стакан$L"),
        u("tbsp", Dim.VOLUME, 0.015, "ст. л.", "tbsp", "столов\\p{L}* лож\\p{L}*", "tablespoons?", "tbsp"),
        u("tsp", Dim.VOLUME, 0.005, "ч. л.", "tsp", "чайн\\p{L}* лож\\p{L}*", "teaspoons?", "tsp"),
        u("floz", Dim.VOLUME, 0.0295735295625, "жидк. унц.", "fl oz", "fl\\.? ?oz", "fluid ounces?", "жидк\\p{L}* унци\\p{L}*"),
        u("m3", Dim.VOLUME, 1000.0, "м³", "m³", "кубометр$L", "куб\\p{L}* метр$L", "м3", "м³", "m3", "m³", "cubic met(?:er|re)s?"),

        u("kmh", Dim.SPEED, 1 / 3.6, "км/ч", "km/h", "км/ч", "км в час", "километр$L в час", "km/h", "kph", "kmh", "kilomet(?:er|re)s? (?:per|an) hour"),
        u("ms", Dim.SPEED, 1.0, "м/с", "m/s", "м/с", "метр$L в секунду", "m/s", "met(?:er|re)s? per second"),
        u("mph", Dim.SPEED, 0.44704, "миль/ч", "mph", "мил\\p{L}{1,3} в час", "mph", "miles? (?:per|an) hour"),
        u("kn", Dim.SPEED, 0.514444, "уз.", "kn", "узл\\p{L}{0,3}", "узел", "knots?"),

        u("m2", Dim.AREA, 1.0, "м²", "m²", "м2", "м²", "кв\\.? ?м", "квадратн\\p{L}* метр$L", "m2", "m²", "sq\\.? ?m", "square met(?:er|re)s?"),
        u("km2", Dim.AREA, 1e6, "км²", "km²", "км2", "км²", "кв\\.? ?км", "квадратн\\p{L}* километр$L", "km2", "km²", "sq\\.? ?km", "square kilomet(?:er|re)s?"),
        u("ha", Dim.AREA, 1e4, "га", "ha", "гектар$L", "га", "hectares?", "ha"),
        u("sotka", Dim.AREA, 100.0, "сотка|сотки|соток|сотки", "are|ares", "сот(?:ка|ки|ок|ку|ках|кам)"),
        u("acre", Dim.AREA, 4046.8564224, "акр|акра|акров|акра", "acre|acres", "акр$L", "acres?"),
        u("sqft", Dim.AREA, 0.09290304, "кв. фут.", "sq ft", "кв\\.? ?фут$L", "квадратн\\p{L}* фут$L", "sq\\.? ?ft", "square f(?:oo|ee)t"),

        u("b", Dim.DATA, 1.0, "байт|байта|байт|байта", "byte|bytes", "байт$L", "bytes?"),
        u("kb", Dim.DATA, 1024.0, "КБ", "KB", "кб", "килобайт$L", "kb", "kilobytes?"),
        u("mb", Dim.DATA, 1024.0 * 1024, "МБ", "MB", "мб", "мегабайт$L", "mb", "megabytes?"),
        u("gb", Dim.DATA, 1024.0 * 1024 * 1024, "ГБ", "GB", "гб", "гигабайт$L", "gb", "gigabytes?"),
        u("tb", Dim.DATA, 1024.0 * 1024 * 1024 * 1024, "ТБ", "TB", "тб", "терабайт$L", "tb", "terabytes?"),

        u("s", Dim.TIME, 1.0, "секунда|секунды|секунд|секунды", "second|seconds", "секунд\\p{L}{0,2}", "сек", "seconds?", "secs?"),
        u("min", Dim.TIME, 60.0, "минута|минуты|минут|минуты", "minute|minutes", "минут\\p{L}{0,2}", "мин", "minutes?", "mins?"),
        u("h", Dim.TIME, 3600.0, "час|часа|часов|часа", "hour|hours", "час\\p{L}{0,2}", "ч", "hours?", "hrs?"),
        u("day", Dim.TIME, 86400.0, "день|дня|дней|дня", "day|days", "сут\\p{L}{0,2}", "дн(?:я|ей|ях|ям)", "день", "days?"),
        u("week", Dim.TIME, 604800.0, "неделя|недели|недель|недели", "week|weeks", "недел\\p{L}{1,3}", "weeks?"),
        u("month", Dim.TIME, 2629746.0, "месяц|месяца|месяцев|месяца", "month|months", "месяц$L", "months?"),
        u("year", Dim.TIME, 31556952.0, "год|года|лет|года", "year|years", "год\\p{L}{0,2}", "лет", "years?"),
    )

    private val aliasAlternation: String = all.flatMap { it.aliases }.sortedByDescending { it.length }
        .joinToString("|") { "(?:$it)" }
    private const val NUM = """(-?\d+(?:[.,]\d+)?)"""
    private val unitRe = "((?:$aliasAlternation))(?!\\p{L})"

    private val direct = Regex("""^$NUM\s*$unitRe\s+(?:в|во|to|in|into)\s+$unitRe$""")
    private val howManyRu = Regex("""^сколько\s+$unitRe\s+(?:в|во)\s+(?:$NUM\s*)?(?:одн\p{L}*\s+)?$unitRe$""")
    private val howManyEn = Regex("""^how\s+many\s+$unitRe\s+(?:are\s+)?(?:in|is|are)\s+(?:$NUM\s*|an?\s+|one\s+)?$unitRe$""")
    private val lead = Regex("""^(?:(?:а|и|so)\s+)?(?:переведи|перевести|конвертируй|сконвертируй|пересчитай|сколько\s+будет|convert|what(?:'s|\s+is)|how\s+much\s+is)\s+""")

    fun unitFor(text: String): Unit? = all.firstOrNull { u -> u.patterns.any { it.matches(text) } }

    /** "5 миль = 8,047 км", or null if [text] isn't a conversion. */
    fun answer(text: String): String? {
        var t = NumberWords.digitize(text.lowercase().replace('ё', 'е')).trim()
            .removePrefix("искра,").removePrefix("iskra,").trim().trimEnd('?', '.', '!', ' ')
        lead.find(t)?.let { t = t.substring(it.range.last + 1) }
        val (num, from, to) = direct.find(t)?.let { m -> Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
            ?: howManyRu.find(t)?.let { m -> Triple(m.groupValues[2], m.groupValues[3], m.groupValues[1]) }
            ?: howManyEn.find(t)?.let { m -> Triple(m.groupValues[2], m.groupValues[3], m.groupValues[1]) }
            ?: return null
        val a = unitFor(from) ?: return null
        val b = unitFor(to) ?: return null
        val v = num.ifEmpty { "1" }.replace(',', '.').toDoubleOrNull() ?: return null
        if (a.dim != b.dim) return tr("Не получится: ${a.name(2.0)} и ${b.name(2.0)} измеряют разное.", "Can't do that: ${a.name(2.0)} and ${b.name(2.0)} measure different things.")
        if (a.id == b.id) return "${Calc.format(v)} ${a.name(v)}."
        val r = convert(v, a, b)
        return "${Calc.format(v)} ${a.name(v)} = ${short(r)} ${b.name(roundForName(r))}."
    }

    fun convert(v: Double, a: Unit, b: Unit): Double {
        if (a.dim != Dim.TEMP) return v * a.factor / b.factor
        val c = when (a.id) { "f" -> (v - 32) * 5 / 9; "k" -> v - 273.15; else -> v }
        return when (b.id) { "f" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
    }

    private fun rounded(v: Double): Double = if (v == 0.0) 0.0 else BigDecimal(v).round(MathContext(4)).toDouble()
    private fun short(v: Double) = Calc.format(rounded(v))
    private fun roundForName(v: Double) = rounded(v)
}
