package ru.iskra.core.brain

/**
 * Number words → digits: "двадцать пять" → "25", "две тысячи двадцать шесть" → "2026",
 * "one hundred and five" → "105", "полтора" → "1.5". Speech recognisers on the phone (Vosk) write numbers
 * as words, so sums, timers and dates have to understand them too. "семь тридцать" stays two numbers ("7 30").
 */
object NumberWords {
    private enum class Cls { UNIT, TEEN, TEN, HUNDRED }
    private class W(val value: Long, val cls: Cls)

    private val words: Map<String, W> = HashMap<String, W>().apply {
        fun add(cls: Cls, value: Long, vararg forms: String) = forms.forEach { put(it, W(value, cls)) }
        add(Cls.UNIT, 0, "ноль", "нуля", "zero")
        add(Cls.UNIT, 1, "один", "одна", "одно", "одну", "одного", "одной", "one")
        add(Cls.UNIT, 2, "два", "две", "двух", "two")
        add(Cls.UNIT, 3, "три", "трех", "three")
        add(Cls.UNIT, 4, "четыре", "четырех", "four")
        add(Cls.UNIT, 5, "пять", "пяти", "five")
        add(Cls.UNIT, 6, "шесть", "шести", "six")
        add(Cls.UNIT, 7, "семь", "семи", "seven")
        add(Cls.UNIT, 8, "восемь", "восьми", "eight")
        add(Cls.UNIT, 9, "девять", "девяти", "nine")
        add(Cls.TEEN, 10, "десять", "десяти", "ten")
        val teensRu = listOf("одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
            "семнадцать", "восемнадцать", "девятнадцать")
        val teensEn = listOf("eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        teensRu.forEachIndexed { i, w -> add(Cls.TEEN, 11L + i, w, w.dropLast(1) + "и", teensEn[i]) }
        add(Cls.TEN, 20, "двадцать", "двадцати", "twenty")
        add(Cls.TEN, 30, "тридцать", "тридцати", "thirty")
        add(Cls.TEN, 40, "сорок", "сорока", "forty")
        add(Cls.TEN, 50, "пятьдесят", "пятидесяти", "fifty")
        add(Cls.TEN, 60, "шестьдесят", "шестидесяти", "sixty")
        add(Cls.TEN, 70, "семьдесят", "семидесяти", "seventy")
        add(Cls.TEN, 80, "восемьдесят", "восьмидесяти", "eighty")
        add(Cls.TEN, 90, "девяносто", "девяноста", "ninety")
        add(Cls.HUNDRED, 100, "сто", "ста")
        add(Cls.HUNDRED, 200, "двести", "двухсот")
        add(Cls.HUNDRED, 300, "триста", "трехсот")
        add(Cls.HUNDRED, 400, "четыреста", "четырехсот")
        add(Cls.HUNDRED, 500, "пятьсот", "пятисот")
        add(Cls.HUNDRED, 600, "шестьсот", "шестисот")
        add(Cls.HUNDRED, 700, "семьсот", "семисот")
        add(Cls.HUNDRED, 800, "восемьсот", "восьмисот")
        add(Cls.HUNDRED, 900, "девятьсот", "девятисот")
    }

    private val scales: Map<String, Long> = HashMap<String, Long>().apply {
        listOf("тысяча", "тысячи", "тысяч", "тысячу", "тысячей", "thousand").forEach { put(it, 1_000L) }
        listOf("миллион", "миллиона", "миллионов", "million").forEach { put(it, 1_000_000L) }
        listOf("миллиард", "миллиарда", "миллиардов", "billion").forEach { put(it, 1_000_000_000L) }
    }

    private val halves = setOf("полтора", "полторы")
    private val word = Regex("""\p{L}+(?:-\p{L}+)*""")

    private fun norm(w: String) = w.lowercase().replace('ё', 'е')

    private fun isNumberWord(w: String): Boolean {
        val n = norm(w)
        return n in words || n in scales || n == "hundred" || n in halves ||
            (n.contains('-') && n.split('-').all { it in words })
    }

    /** Replaces every run of number words with digits; everything else is kept as it was. */
    fun digitize(text: String): String {
        val matches = word.findAll(text).toList()
        if (matches.none { isNumberWord(it.value) }) return text
        val out = StringBuilder()
        var pos = 0
        var i = 0
        while (i < matches.size) {
            val m = matches[i]
            if (!isNumberWord(m.value)) { i++; continue }
            // Collect a run of number words separated by spaces (and English "and" inside the run).
            var j = i
            val run = ArrayList<String>()
            while (j < matches.size) {
                val w = norm(matches[j].value)
                val gapOk = j == i || text.substring(matches[j - 1].range.last + 1, matches[j].range.first).isBlank()
                if (!gapOk) break
                // "one hundred and five", "two thousand and six" — but "five and six" are two numbers.
                if (w == "and" && (run.lastOrNull() == "hundred" || run.lastOrNull() in scales) && j + 1 < matches.size && isNumberWord(matches[j + 1].value)) { run += w; j++; continue }
                if (!isNumberWord(w)) break
                run += w
                j++
            }
            if (run.lastOrNull() == "and") { run.removeAt(run.size - 1); j-- }
            out.append(text, pos, m.range.first)
            out.append(render(run))
            pos = matches[j - 1].range.last + 1
            i = j
        }
        out.append(text, pos, text.length)
        return out.toString()
    }

    private fun render(run: List<String>): String {
        val numbers = ArrayList<String>()
        var total = 0L
        var group = 0L
        var last: Cls? = null
        var any = false
        fun flush() {
            if (any) numbers += (total + group).toString()
            total = 0; group = 0; last = null; any = false
        }
        for (w in run) {
            if (w == "and") continue
            if (w in halves) { flush(); numbers += "1.5"; continue }
            val scale = scales[w]
            if (scale != null) {
                if (!any && group == 0L) group = 1
                total += group * scale; group = 0; last = null; any = true
                continue
            }
            if (w == "hundred") { group = (if (group == 0L) 1 else group) * 100; last = Cls.HUNDRED; any = true; continue }
            val parts = if (w.contains('-')) w.split('-') else listOf(w)
            for (p in parts) {
                val ww = words[p] ?: continue
                if (last != null && !canFollow(last!!, ww.cls)) flush()
                group += ww.value; last = ww.cls; any = true
            }
        }
        flush()
        return numbers.joinToString(" ")
    }

    private fun canFollow(prev: Cls, next: Cls): Boolean = when (prev) {
        Cls.HUNDRED -> next != Cls.HUNDRED
        Cls.TEN -> next == Cls.UNIT
        Cls.TEEN, Cls.UNIT -> false
    }
}
