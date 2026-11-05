package ru.iskra.core.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.iskra.core.i18n.I18n
import ru.iskra.core.i18n.tr
import ru.iskra.core.llm.Http
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.ToolSpec
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolSchemas
import ru.iskra.core.tools.str
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Sent with every request to Wikipedia and the exchange-rate service: the app and its page, nothing about the owner. */
internal const val USER_AGENT = "Iskra (+https://github.com/Expireduser-source/Iskra)"

/**
 * Wikipedia search + the article's first sentences, in one request. Only the search words leave the phone.
 * Text is CC BY-SA; the tool result carries the article link.
 */
class WikipediaClient(
    private val client: OkHttpClient = Http.defaultClient,
    private val endpoint: (lang: String) -> String = { "https://$it.wikipedia.org/w/api.php" },
) {
    data class Article(val title: String, val extract: String, val url: String)

    suspend fun lookup(query: String, lang: String): Article? {
        val url = endpoint(lang).toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", query.take(200)).addQueryParameter("gsrlimit", "3")
            .addQueryParameter("prop", "extracts|info").addQueryParameter("exintro", "1").addQueryParameter("explaintext", "1")
            .addQueryParameter("exsentences", "6").addQueryParameter("exlimit", "3").addQueryParameter("inprop", "url")
            .addQueryParameter("redirects", "1").build()
        val (code, body) = Http.execute(client, Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build())
        Http.checkStatus(code, body)
        return parse(body)
    }

    companion object {
        fun parse(json: String): Article? {
            val pages = (Json.parseToJsonElement(json).jsonObject["query"] as? JsonObject)?.get("pages") as? JsonArray ?: return null
            return pages.mapNotNull { it as? JsonObject }
                .sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: 99 }
                .firstNotNullOfOrNull { p ->
                    val extract = (p["extract"] as? JsonPrimitive)?.contentOrNull?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
                    val title = (p["title"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
                    if (extract.length < 40) null
                    else Article(title, if (extract.length > 900) extract.take(900).substringBeforeLast(". ") + "." else extract,
                        (p["fullurl"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
        }
    }
}

class WikipediaTool(private val wiki: WikipediaClient) : Tool {
    override val spec get() = ToolSpec(BrainNames.WIKIPEDIA,
        tr("Найти факты в Википедии: кто такой, что такое, когда было, сколько жителей и т. п. Используй, когда не уверена " +
            "в факте, а не выдумывай. Отвечай своими словами коротко и скажи, что это по Википедии.",
            "Look up facts on Wikipedia: who someone is, what something is, when it happened, population, etc. Use it when you're " +
                "not sure of a fact instead of making it up. Answer briefly in your own words and say it's from Wikipedia."),
        ToolSchemas.obj(listOf("query"), mapOf(
            "query" to ToolSchemas.str(tr("Что искать — название или 2–4 ключевых слова", "What to look up — a title or 2–4 keywords")),
            "lang" to ToolSchemas.str(tr("Язык Википедии: ru, en, …", "Wikipedia language: en, ru, …")),
        )))
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = tr("Википедия: «${args.str("query").orEmpty().take(60)}»", "Wikipedia: “${args.str("query").orEmpty().take(60)}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val q = args.str("query")?.trim()?.takeIf { it.length >= 2 } ?: return ToolOutcome(false, tr("пустой запрос", "empty query"))
        val lang = args.str("lang")?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: tr("ru", "en")
        val a = try {
            wiki.lookup(q, lang) ?: if (lang != "en") wiki.lookup(q, "en") else null
        } catch (e: LlmException) {
            return ToolOutcome(false, if (e.kind == LlmException.Kind.NETWORK) tr("нет связи с Википедией", "no connection to Wikipedia") else tr("Википедия ответила ошибкой", "Wikipedia returned an error"))
        } ?: return ToolOutcome(true, tr("В Википедии ничего не нашлось по запросу «$q».", "Nothing found on Wikipedia for “$q”."))
        return ToolOutcome(true, "«${a.title}»: ${a.extract}" + (if (a.url.isNotEmpty()) tr(" (Википедия: ${a.url})", " (Wikipedia: ${a.url})") else ""))
    }
}

/** Daily exchange rates from ExchangeRate-API's open endpoint (open.er-api.com, no key; "Rates By Exchange Rate API"). */
class CurrencyClient(
    private val client: OkHttpClient = Http.defaultClient,
    private val base: String = "https://open.er-api.com/v6/latest/",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Rates(val base: String, val rates: Map<String, Double>, val updatedMs: Long)
    private val cache = HashMap<String, Pair<Long, Rates>>()

    suspend fun rates(code: String): Rates {
        val now = clock()
        synchronized(cache) { cache[code]?.takeIf { now - it.first < 6 * 3_600_000L }?.let { return it.second } }
        val (status, body) = Http.execute(client, Request.Builder().url(base + code).header("User-Agent", USER_AGENT).get().build())
        Http.checkStatus(status, body)
        val r = parse(body) ?: throw LlmException("bad rates", LlmException.Kind.BAD_RESPONSE)
        synchronized(cache) { cache[code] = now to r }
        return r
    }

    companion object {
        fun parse(json: String): Rates? {
            val o = Json.parseToJsonElement(json).jsonObject
            if ((o["result"] as? JsonPrimitive)?.contentOrNull != "success") return null
            val rates = (o["rates"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap() ?: return null
            val updated = (o["time_last_update_unix"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.times(1000) ?: 0L
            return Rates((o["base_code"] as? JsonPrimitive)?.contentOrNull ?: "", rates, updated)
        }
    }
}

class CurrencyTool(private val currency: CurrencyClient, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val spec get() = ToolSpec(BrainNames.CURRENCY,
        tr("Курс валют на сегодня и пересчёт суммы (коды ISO: USD, EUR, RUB, CNY, KZT…).", "Today's exchange rate and converting an amount (ISO codes: USD, EUR, RUB, CNY, KZT…)."),
        ToolSchemas.obj(listOf("from", "to"), mapOf(
            "from" to ToolSchemas.str(tr("Из какой валюты, код ISO", "From currency, ISO code")),
            "to" to ToolSchemas.str(tr("В какую валюту, код ISO", "To currency, ISO code")),
            "amount" to ToolSchemas.num(tr("Сумма (по умолчанию 1)", "Amount (default 1)")),
        )))
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = tr("курс ${args.str("from").orEmpty()} → ${args.str("to").orEmpty()}", "rate ${args.str("from").orEmpty()} → ${args.str("to").orEmpty()}")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val from = args.str("from")?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return ToolOutcome(false, tr("неизвестная валюта", "unknown currency"))
        val to = args.str("to")?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return ToolOutcome(false, tr("неизвестная валюта", "unknown currency"))
        val amount = (args["amount"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() } ?: 1.0
        val r = try { currency.rates(from) } catch (e: LlmException) {
            return ToolOutcome(false, if (e.kind == LlmException.Kind.NETWORK) tr("нет интернета — курс не узнать", "no internet — can't get the rate") else tr("сервис курсов не знает валюту $from", "the rates service doesn't know $from"))
        }
        val rate = r.rates[to] ?: return ToolOutcome(false, tr("сервис курсов не знает валюту $to", "the rates service doesn't know $to"))
        val v = amount * rate
        val day = if (r.updatedMs > 0) DateTimeFormatter.ofPattern(tr("d MMMM", "MMMM d"), Locale.forLanguageTag(tr("ru", "en")))
            .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.updatedMs), zone())) else null
        return ToolOutcome(true, "${Calc.format(amount)} $from = ${money(v)} $to" + (day?.let { tr(" (курс на $it)", " (rate as of $it)") } ?: ""))
    }

    private fun money(v: Double): String = Calc.format(if (kotlin.math.abs(v) >= 1) Math.round(v * 100) / 100.0 else BigDecimal(v).round(MathContext(3)).toDouble())
}

/** "курс доллара", "100 долларов в рублях", "how much is 50 euros in dollars" → the currency tool's arguments. */
object CurrencyWords {
    // Specific names first: "канадский доллар" before "доллар".
    private val names: List<Pair<String, String>> = listOf(
        "CAD" to """канадск\p{L}*\s+доллар\p{L}{0,3}|canadian\s+dollars?|cad""",
        "AUD" to """австралийск\p{L}*\s+доллар\p{L}{0,3}|australian\s+dollars?|aud""",
        "BYN" to """белорусск\p{L}*\s+рубл\p{L}{0,3}|byn""",
        "GBP" to """фунт\p{L}{0,3}\s+стерлинг\p{L}{0,3}|фунт\p{L}{0,3}|pounds?\s+sterling|pounds?|gbp|£""",
        "USD" to """доллар\p{L}{0,3}|бакс\p{L}{0,3}|dollars?|bucks?|usd|\$""",
        "EUR" to """евро|euros?|eur|€""",
        "RUB" to """рубл\p{L}{0,3}|rubles?|roubles?|rub|₽""",
        "CNY" to """юан\p{L}{0,3}|yuan|renminbi|rmb|cny""",
        "JPY" to """[ий]ен\p{L}{0,2}|yen|jpy|¥""",
        "KZT" to """тенге|tenge|kzt""",
        "UAH" to """гривн\p{L}{0,2}|hryvnias?|uah""",
        "TRY" to """турецк\p{L}*\s+лир\p{L}{0,2}|лир\p{L}{0,2}|turkish\s+liras?|liras?""",
        "CHF" to """швейцарск\p{L}*\s+франк\p{L}{0,3}|франк\p{L}{0,3}|swiss\s+francs?|francs?|chf""",
        "PLN" to """злот\p{L}{0,3}|zloty|zlotys|pln""",
        "AED" to """дирхам\p{L}{0,3}|dirhams?|aed""",
        "THB" to """тайск\p{L}*\s+бат\p{L}{0,2}|baht|thb""",
        "INR" to """рупи\p{L}{0,2}|rupees?|inr""",
        "GEL" to """лари|lari|gel""",
        "AMD" to """армянск\p{L}*\s+драм\p{L}{0,2}|драм\p{L}{0,2}|drams?|amd""",
        "KGS" to """киргизск\p{L}*\s+сом\p{L}{0,2}|kgs""",
        "UZS" to """узбекск\p{L}*\s+сум\p{L}{0,2}|uzs""",
        "BTC" to """биткоин\p{L}{0,3}|bitcoins?|btc""",
    )
    private val any = names.joinToString("|") { "(?:${it.second})" }
    private const val END = """(?![\p{L}\p{N}_])"""
    private val cur = "($any)$END"
    private const val NUM = """(\d+(?:[.,]\d+)?)"""

    private val rateRu = Regex("""^(?:какой\s+(?:сейчас\s+|сегодня\s+)?)?курс\s+$cur(?:\s+к\s+$cur)?(?:\s+(?:на\s+)?сегодня)?$""")
    private val priceRu = Regex("""^(?:сколько\s+(?:сейчас\s+|сегодня\s+)?стоит|почем|почём|по\s+чем)\s+$cur(?:\s+(?:в|во)\s+$cur)?(?:\s+(?:сейчас|сегодня))?$""")
    private val convert = Regex("""^(?:переведи|перевести|конвертируй|сколько\s+будет|convert|how\s+much\s+is|what(?:'s|\s+is))?\s*$NUM\s*$cur\s+(?:в|во|to|in|into)\s+$cur$""")
    private val rateEn = Regex("""^(?:what(?:'s|\s+is)\s+the\s+)?$cur\s+(?:exchange\s+)?rate(?:\s+(?:to|in|against)\s+$cur)?(?:\s+today)?$|^(?:exchange\s+)?rate\s+(?:of\s+)?(?:the\s+)?$cur(?:\s+to\s+$cur)?$""")
    private val priceEn = Regex("""^how\s+much\s+is\s+(?:a|one|the)\s+$cur(?:\s+(?:in|to)\s+$cur)?(?:\s+today)?$""")

    fun code(word: String): String? = names.firstOrNull { Regex("^(?:${it.second})$").matches(word.trim()) }?.first

    /** Arguments for [CurrencyTool], or null if [text] isn't about exchange rates. */
    fun parse(text: String): JsonObject? {
        val t = NumberWords.digitize(text.lowercase().replace('ё', 'е')).trim()
            .removePrefix("искра,").removePrefix("iskra,").trim().trimEnd('?', '.', '!', ' ')
        var amount = 1.0
        val (a, b) = convert.find(t)?.let { m -> amount = m.groupValues[1].replace(',', '.').toDouble(); m.groupValues[2] to m.groupValues[3] }
            ?: (rateRu.find(t) ?: priceRu.find(t) ?: priceEn.find(t))?.let { m -> m.groupValues[1] to m.groupValues[2] }
            ?: rateEn.find(t)?.let { m -> val g = m.groupValues; g[1].ifEmpty { g[3] } to g[2].ifEmpty { g[4] } }
            ?: return null
        val from = code(a) ?: return null
        val home = if (I18n.isEnglish) "USD" else "RUB"
        val to = b.takeIf { it.isNotEmpty() }?.let { code(it) ?: return null } ?: if (from == home) (if (home == "RUB") "USD" else "EUR") else home
        if (from == to) return null
        return buildJsonObject { put("from", from); put("to", to); put("amount", amount) }
    }
}
