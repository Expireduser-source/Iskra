package ru.iskra.core.brain

import ru.iskra.core.i18n.I18n
import ru.iskra.core.i18n.tr
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small exact calculator: + − × ÷ ^ % !, brackets, √ and a few functions (trigonometry in degrees), percent the way
 * people mean it ("2300 + 15%" = 2645, "15% от 2300" = 345). [answer] also understands spoken sums in Russian and
 * English ("сколько будет двадцать пять умножить на четыре", "what's 15 percent of 80"). Models are bad at arithmetic;
 * this is both an offline answer and the `calculate` tool.
 */
object Calc {
    sealed interface Outcome {
        data class Ok(val value: Double) : Outcome
        data class Error(val message: String) : Outcome
    }

    private sealed interface Tok
    private data class Num(val v: Double) : Tok
    private data class Op(val s: String) : Tok
    private data class Fn(val name: String) : Tok

    private class Fail(val why: String) : Exception(why)

    private val functions = setOf("sqrt", "cbrt", "sin", "cos", "tan", "ln", "log", "abs", "round")

    /** null — this is not an arithmetic expression at all. */
    fun evaluate(expr: String): Outcome? {
        val toks = tokenize(expr) ?: return null
        if (toks.none { it is Num }) return null
        return try {
            val p = Parser(toks)
            val v = p.expr()
            if (p.i != toks.size) null
            else if (!v.v.isFinite()) Outcome.Error(tr("слишком большое число", "the number is too big"))
            else Outcome.Ok(v.v)
        } catch (e: Fail) {
            Outcome.Error(e.why)
        } catch (e: ParseEnd) {
            null
        }
    }

    fun eval(expr: String): Double? = (evaluate(expr) as? Outcome.Ok)?.value

    /** A reply for a spoken or typed sum, or null if [text] isn't one. */
    fun answer(text: String): String? {
        val expr = fromSpeech(text) ?: return null
        return when (val r = evaluate(expr) ?: return null) {
            is Outcome.Ok -> "${pretty(expr)} = ${format(r.value)}"
            is Outcome.Error -> tr("Не посчитать: ${r.message}.", "Can't work it out: ${r.message}.")
        }
    }

    // --- natural language → expression ---

    private val prefix = Regex(
        """^(?:(?:а|и|ну|so|and)\s+)?(?:сколько\s+будет|сколько\s+это\s+будет|сколько\s+это|сколько|посчитай(?:\s+мне)?|подсчитай|вычисли|реши(?:\s+пример)?|""" +
            """what(?:'s|\s+is|\s+does)|how\s+much\s+is|how\s+much\s+does|calculate|compute|work\s+out|solve)\s*[:,]?\s*""",
    )
    private val verbFirst: List<Pair<Regex, String>> = listOf(
        Regex("""^(?:умножь|умножить|помножь|помножить)\s+(\S+)\s+на\s+(\S+)$""") to "$1*$2",
        Regex("""^(?:раздели|разделить|подели|поделить)\s+(\S+)\s+на\s+(\S+)$""") to "$1/$2",
        Regex("""^(?:сложи|сложить|прибавь|прибавить)\s+(\S+)\s+(?:и|к|с)\s+(\S+)$""") to "$1+$2",
        Regex("""^(?:отними|отнять|вычти|вычесть)\s+(\S+)\s+(?:из|от)\s+(\S+)$""") to "$2-$1",
        Regex("""^multiply\s+(\S+)\s+(?:by|and)\s+(\S+)$""") to "$1*$2",
        Regex("""^divide\s+(\S+)\s+by\s+(\S+)$""") to "$1/$2",
        Regex("""^add\s+(\S+)\s+(?:and|to)\s+(\S+)$""") to "$1+$2",
        Regex("""^subtract\s+(\S+)\s+from\s+(\S+)$""") to "$2-$1",
    )
    private val phrases: List<Pair<Regex, String>> = listOf(
        """кубическ\p{L}*\s+корень\s+из|cube\s+root\s+of""" to " cbrt ",
        """квадратн\p{L}*\s+корень\s+из|корень\s+квадратн\p{L}*\s+из|корень\s+из|корень|square\s+root\s+of|root\s+of|sqrt\s+of""" to " sqrt ",
        """в\s+степени|to\s+the\s+power\s+of|to\s+the\s+power|raised\s+to(?:\s+the\s+power\s+of)?""" to "^",
        """в\s+квадрате|squared""" to "^2",
        """в\s+кубе|cubed""" to "^3",
        """умнож\p{L}*\s+на|помнож\p{L}*\s+на|умнож\p{L}*|multiplied\s+by|times""" to "*",
        """раздел\p{L}*\s+на|подел\p{L}*\s+на|делен\p{L}*\s+на|делить\s+на|divided\s+by|over""" to "/",
        """плюс|прибавить|plus|added\s+to""" to "+",
        """минус|отнять|вычесть|minus""" to "-",
        """процент\p{L}*\s+от|%\s*от|percent\s+of|per\s*cent\s+of|%\s*of""" to "%of",
        """процент\p{L}*|percent|per\s*cent""" to "%",
        """факториал\s+(\d+)|factorial\s+of\s+(\d+)""" to "$1$2!",
        """(\d+)\s+factorial""" to "$1!",
        """синус|sine\s+of|sin\s+of""" to " sin ",
        """косинус|cosine\s+of|cos\s+of""" to " cos ",
        """тангенс|tangent\s+of|tan\s+of""" to " tan ",
        """натуральн\p{L}*\s+логарифм|natural\s+log(?:arithm)?\s+of""" to " ln ",
        """десятичн\p{L}*\s+логарифм|логарифм|log(?:arithm)?\s+of""" to " log ",
        """по\s+модулю|modulo|mod""" to " mod ",
        """равно|equals|is\s+equal\s+to""" to "",
    ).map { (re, rep) -> Regex(re) to rep }

    private val funcStart = Regex("""^(?:квадратн\p{L}*\s+|кубическ\p{L}*\s+)?корень\s|^(?:факториал|синус|косинус|тангенс|логарифм|натуральн\p{L}*\s+логарифм)\s|^(?:square|cube)\s+root\s+of\s|^(?:factorial|sine|cosine|tangent)\s+of\s""")
    private val bare = Regex("""^[\d\s.,+\-*/^()%!×÷·:xх]+$""")
    private val hasOperator = Regex("""[+\-*/^%!]|sqrt|cbrt|sin|cos|tan|ln|log|mod""")

    /** An expression for the calculator, or null if [text] isn't a request to calculate. */
    fun fromSpeech(text: String): String? {
        var t = NumberWords.digitize(text.lowercase().replace('ё', 'е')).trim()
            .removePrefix("искра,").removePrefix("iskra,").trim()
            .trimEnd('?', '.', '!', ' ', '=')
        if (t.isEmpty()) return null
        val m = prefix.find(t)
        var asked = m != null
        if (m != null) t = t.substring(m.range.last + 1).trim()
        for ((re, rep) in verbFirst) {
            val vm = re.find(t) ?: continue
            t = vm.groupValues.drop(1).let { g -> rep.replace("$1", g[0]).replace("$2", g[1]) }
            asked = true
            break
        }
        if (!asked && funcStart.containsMatchIn(t)) asked = true   // "корень из 144", "square root of 2"
        if (!asked && !bare.matches(t)) return null
        for ((re, rep) in phrases) t = re.replace(t, rep)
        t = t.replace(Regex("""(\d)\s*[xх×·]\s*(?=\d)"""), "$1*")    // 3 x 4 (Latin or Cyrillic x)
            .replace(Regex("""(\d)\s*:\s*(?=\d)"""), "$1/")             // school division 10 : 2
            .replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty() || !hasOperator.containsMatchIn(t)) return null
        return t
    }

    /** "25*4" → "25 × 4" with localized numbers, for showing next to the result. */
    fun pretty(expr: String): String = (if (I18n.isEnglish) expr.replace(Regex("""(\d),(?=\d{3}(?!\d))"""), "$1") else expr)
        .replace(Regex("""\s*%of\s*"""), "% " + tr("от", "of") + " ")
        .replace("sqrt", "√").replace("cbrt", "∛")
        .replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("""(?<=[\d)!%])\s*-\s*"""), " − ")
        .replace(Regex("""\d+(?:[.,]\d+)?""")) { format(it.value.replace(',', '.').toDouble()) }
        .replace(Regex("""\s+"""), " ").replace(Regex("""([√∛])\s+"""), "$1").trim()

    /** 10 significant digits, Russian decimal comma, thousands grouped from 10 000. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e15 || a < 1e-9) return String.format(Locale.ROOT, "%.4e", v).let { if (I18n.isEnglish) it else it.replace('.', ',') }
        var s = BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
        val neg = s.startsWith("-")
        if (neg) s = s.drop(1)
        val int = s.substringBefore('.')
        val frac = s.substringAfter('.', "")
        val grouped = if (int.length > 4) int.reversed().chunked(3).joinToString(tr(" ", ",")).reversed() else int
        return (if (neg) "−" else "") + grouped + (if (frac.isNotEmpty()) tr(",", ".") + frac else "")
    }

    // --- expression → value ---

    private fun tokenize(src: String): List<Tok>? {
        var s = src.lowercase().replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("**", "^").replace("√", " sqrt ").replace("∛", " cbrt ").replace("π", " pi ")
        s = s.replace(Regex("""(\d)[  ](?=\d{3}(?!\d))"""), "$1")           // 10 000
        if (I18n.isEnglish) s = s.replace(Regex("""(\d),(?=\d{3}(?!\d))"""), "$1") // 10,000
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' || c == ',') && i + 1 < s.length && s[i + 1].isDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    if (j < s.length && (s[j] == '.' || s[j] == ',') && j + 1 < s.length && s[j + 1].isDigit()) {
                        j++
                        while (j < s.length && s[j].isDigit()) j++
                    }
                    out += Num(s.substring(i, j).replace(',', '.').toDouble())
                    i = j
                }
                s.startsWith("%of", i) -> { out += Op("%of"); i += 3 }
                c in "+-*/^()!%" -> { out += Op(c.toString()); i++ }
                c.isLetter() -> {
                    var j = i
                    while (j < s.length && s[j].isLetter()) j++
                    when (val w = s.substring(i, j)) {
                        in functions -> out += Fn(w)
                        "pi", "пи" -> out += Num(PI)
                        "e" -> out += Num(E)
                        "mod" -> out += Op("mod")
                        "of" -> if (out.lastOrNull() == Op("%")) out[out.size - 1] = Op("%of") else return null
                        else -> return null
                    }
                    i = j
                }
                else -> return null
            }
        }
        return out
    }

    private object ParseEnd : Exception() { private fun readResolve(): Any = ParseEnd }

    private class V(val v: Double, val pct: Boolean = false)

    private class Parser(val t: List<Tok>) {
        var i = 0
        private fun op(): String? = (t.getOrNull(i) as? Op)?.s

        fun expr(): V {
            var a = term()
            while (true) {
                val o = op()
                if (o != "+" && o != "-") return a
                i++
                val b = term()
                val bv = if (b.pct) a.v * b.v else b.v   // 2300 + 15% = 2300 + 345
                a = V(if (o == "+") a.v + bv else a.v - bv)
            }
        }

        fun term(): V {
            var a = unary()
            while (true) {
                when (op()) {
                    "*" -> { i++; a = V(a.v * unary().v) }
                    "/" -> { i++; val b = unary().v; if (b == 0.0) throw Fail(tr("на ноль делить нельзя — даже кошкам", "you can't divide by zero, not even cats can")); a = V(a.v / b) }
                    "mod" -> { i++; val b = unary().v; if (b == 0.0) throw Fail(tr("остаток от деления на ноль не бывает", "there's no remainder of division by zero")); a = V(a.v.mod(b)) }
                    "%of" -> { i++; a = V(a.v / 100 * unary().v) }
                    else -> {
                        val n = t.getOrNull(i)
                        if (n == Op("(") || n is Fn) a = V(a.v * unary().v) else return a   // 2(3+4), 2√9
                    }
                }
            }
        }

        fun unary(): V = when (op()) {
            "-" -> { i++; val x = unary(); V(-x.v, x.pct) }
            "+" -> { i++; unary() }
            else -> power()
        }

        fun power(): V {
            val base = postfix()
            if (op() != "^") return base
            i++
            val e = unary().v
            if (base.v < 0 && e != round(e)) throw Fail(tr("корень из отрицательного числа", "a root of a negative number"))
            return V(base.v.pow(e))
        }

        fun postfix(): V {
            var x = primary()
            while (true) {
                x = when (op()) {
                    "!" -> { i++; V(factorial(x.v)) }
                    "%" -> { i++; V(x.v / 100, pct = true) }
                    else -> return x
                }
            }
        }

        fun primary(): V {
            val tok = t.getOrNull(i) ?: throw ParseEnd
            return when (tok) {
                is Num -> { i++; V(tok.v) }
                is Fn -> { i++; V(apply(tok.name, if (op() == "(") primary().v else postfix().v)) }
                is Op -> {
                    if (tok.s != "(") throw ParseEnd
                    i++
                    val v = expr()
                    if (op() == ")") i++ else if (i < t.size) throw ParseEnd   // a missing ")" at the very end is forgiven
                    V(v.v)
                }
            }
        }

        private fun factorial(x: Double): Double {
            if (x < 0 || x != round(x)) throw Fail(tr("факториал бывает только у целых чисел от нуля", "factorials exist only for whole numbers from zero"))
            if (x > 170) throw Fail(tr("слишком большое число", "the number is too big"))
            var r = 1.0
            for (k in 2..x.toInt()) r *= k
            return r
        }

        private fun apply(f: String, x: Double): Double = when (f) {
            "sqrt" -> if (x < 0) throw Fail(tr("корень из отрицательного числа", "a root of a negative number")) else sqrt(x)
            "cbrt" -> cbrt(x)
            "sin" -> clean(sin(Math.toRadians(x)))
            "cos" -> clean(cos(Math.toRadians(x)))
            "tan" -> if (abs((x % 180 + 180) % 180 - 90) < 1e-9) throw Fail(tr("тангенс 90° не существует", "tan 90° doesn't exist")) else clean(tan(Math.toRadians(x)))
            "ln" -> if (x <= 0) throw Fail(tr("логарифм бывает только у положительных чисел", "logarithms exist only for positive numbers")) else ln(x)
            "log" -> if (x <= 0) throw Fail(tr("логарифм бывает только у положительных чисел", "logarithms exist only for positive numbers")) else log10(x)
            "abs" -> abs(x)
            "round" -> round(x)
            else -> throw ParseEnd
        }

        /** sin 30° = 0.49999999999999994 → 0.5. */
        private fun clean(v: Double) = if (abs(v - round(v * 1e12) / 1e12) < 1e-13) round(v * 1e12) / 1e12 else v
    }
}
