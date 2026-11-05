package ru.iskra.core.usage

import ru.iskra.core.i18n.tr
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import java.util.Locale

/** Today's cloud usage. Only counts — never message text. */
data class UsageDay(
    val day: String = "",
    val llmRequests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Some tokens were estimated: the service did not report usage. */
    val estimated: Boolean = false,
    val ttsChars: Long = 0,
    /** The 80 % warning was already shown today. */
    val warned: Boolean = false,
) {
    val tokens get() = inputTokens + outputTokens
}

/** Optional daily limits (0 = none) and prices per million the owner enters; nothing is hard-coded. */
data class UsageLimits(
    val llmRequests: Int = 0,
    val tokens: Long = 0,
    val ttsChars: Long = 0,
    val priceInPerM: Double = 0.0,
    val priceOutPerM: Double = 0.0,
    val priceTtsPerM: Double = 0.0,
    val currency: String = "₽",
) {
    val any get() = llmRequests > 0 || tokens > 0 || ttsChars > 0
    val priced get() = priceInPerM > 0 || priceOutPerM > 0 || priceTtsPerM > 0
}

interface UsageStore {
    fun load(): UsageDay
    fun save(d: UsageDay)
}

class InMemoryUsageStore : UsageStore {
    private var d = UsageDay()
    @Synchronized override fun load() = d
    @Synchronized override fun save(d: UsageDay) { this.d = d }
}

/** Rough tokens for services that do not report usage: Russian text is ~3 characters per token. */
object TokenEstimate {
    fun of(text: String): Int = (text.length + 2) / 3

    fun of(request: LlmRequest): Int = of(request.fullSystem) + request.messages.sumOf { m ->
        when (m) {
            is ChatMessage.User -> of(m.text)
            is ChatMessage.Assistant -> of(m.text) + m.toolCalls.size * 20
            is ChatMessage.ToolResult -> of(m.content)
        } + 4
    } + request.tools.sumOf { of(it.description) + of(it.parameters.toString()) }
}

/**
 * Daily counters and limits for the cloud model and the cloud voice. At 80 % of any limit the owner
 * is told once a day; at 100 % the cloud is not used until the next day (offline replies, system voice).
 */
class UsageMeter(
    private val store: UsageStore,
    private val limits: () -> UsageLimits,
    private val today: () -> String,
) {
    @Synchronized fun current(): UsageDay = store.load().let { if (it.day == today()) it else UsageDay(day = today()) }

    @Synchronized fun recordLlm(inputTokens: Int, outputTokens: Int, estimated: Boolean) {
        val d = current()
        store.save(d.copy(llmRequests = d.llmRequests + 1, inputTokens = d.inputTokens + inputTokens,
            outputTokens = d.outputTokens + outputTokens, estimated = d.estimated || estimated))
    }

    @Synchronized fun recordTts(chars: Int) {
        val d = current()
        store.save(d.copy(ttsChars = d.ttsChars + chars))
    }

    fun llmAllowed(): Boolean {
        val l = limits(); val d = current()
        return !(l.llmRequests > 0 && d.llmRequests >= l.llmRequests) && !(l.tokens > 0 && d.tokens >= l.tokens)
    }

    fun ttsAllowed(): Boolean {
        val l = limits()
        return !(l.ttsChars > 0 && current().ttsChars >= l.ttsChars)
    }

    /** Share of the most-used limit, 0 when there are no limits. */
    fun fraction(d: UsageDay = current(), l: UsageLimits = limits()): Double = listOfNotNull(
        if (l.llmRequests > 0) d.llmRequests.toDouble() / l.llmRequests else null,
        if (l.tokens > 0) d.tokens.toDouble() / l.tokens else null,
        if (l.ttsChars > 0) d.ttsChars.toDouble() / l.ttsChars else null,
    ).maxOrNull() ?: 0.0

    /** A line for the owner the first time today usage crosses 80 % of a limit, otherwise null. */
    @Synchronized fun takeWarning(): String? {
        val d = current()
        val f = fraction(d)
        if (d.warned || f < 0.8) return null
        store.save(d.copy(warned = true))
        return if (f >= 1.0) tr("Сегодняшний лимит облака исчерпан — до завтра отвечаю попроще и говорю системным голосом. Лимиты — в «Настройки → Экономия».", "Today's cloud limit is used up — until tomorrow I keep answers simple and speak with the system voice. Limits are in “Settings → Economy”.")
        else tr("Мы потратили ${(f * 100).toInt()} % дневного лимита облака. Ближе к 100 % перейду на офлайн до завтра.", "We've used ${(f * 100).toInt()} % of the daily cloud limit. Close to 100 % I'll go offline until tomorrow.")
    }

    /** Estimated cost today, or null if the owner entered no prices. */
    fun cost(d: UsageDay = current(), l: UsageLimits = limits()): Double? {
        if (!l.priced) return null
        return d.inputTokens * l.priceInPerM / 1e6 + d.outputTokens * l.priceOutPerM / 1e6 + d.ttsChars * l.priceTtsPerM / 1e6
    }

    fun describe(d: UsageDay = current(), l: UsageLimits = limits()): String {
        val tokens = (if (d.estimated) "≈" else "") + d.tokens
        val base = tr("сегодня ${d.llmRequests} запросов к модели, $tokens токенов, ${d.ttsChars} символов облачного голоса", "today ${d.llmRequests} model requests, $tokens tokens, ${d.ttsChars} characters of cloud voice")
        val money = cost(d, l)?.let { ", ≈ ${String.format(Locale.ROOT, "%.2f", it)} ${l.currency}" }.orEmpty()
        val lim = if (l.any) tr("; лимит использован на ${(fraction(d, l) * 100).toInt()} %", "; limit ${(fraction(d, l) * 100).toInt()} % used") else ""
        return base + money + lim
    }
}

/**
 * Counts every model request and refuses new ones once today's limit is reached. Failed requests are
 * not counted: the services do not bill them (except cut streams, which are rare).
 */
class MeteredProvider(private val inner: LlmProvider, private val meter: UsageMeter) : LlmProvider {
    override val id: String get() = inner.id

    override suspend fun complete(request: LlmRequest): LlmResponse = metered(request) { inner.complete(request) }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse =
        metered(request) { inner.stream(request, onText) }

    private suspend fun metered(request: LlmRequest, call: suspend () -> LlmResponse): LlmResponse {
        if (!meter.llmAllowed()) throw LlmException("Daily usage limit reached", LlmException.Kind.LIMIT)
        val r = call()
        val reported = r.inputTokens > 0 || r.outputTokens > 0
        meter.recordLlm(
            inputTokens = if (r.inputTokens > 0) r.inputTokens else TokenEstimate.of(request),
            outputTokens = if (r.outputTokens > 0) r.outputTokens else TokenEstimate.of(r.text) + r.toolCalls.size * 20,
            estimated = !reported,
        )
        return r
    }
}
