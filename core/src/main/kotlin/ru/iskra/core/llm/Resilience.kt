package ru.iskra.core.llm

import kotlinx.coroutines.delay

/**
 * Tries [providers] in order. A busy free model (rate limit, upstream error, model withdrawn, privacy rule)
 * hands the same request to the next one; the one that answered is preferred for a while. With a single
 * provider, a short hiccup (rate limit, server error) gets one quiet retry. Nothing is retried once text has
 * already been streamed to the listener — the owner would hear the start twice.
 */
class FallbackProvider(
    private val providers: List<LlmProvider>,
    private val retryDelayMs: Long = 1_500,
    private val stickyMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmProvider {
    init { require(providers.isNotEmpty()) }

    override val id: String get() = providers.first().id

    @Volatile private var preferred = 0
    @Volatile private var preferredUntil = 0L

    override suspend fun complete(request: LlmRequest): LlmResponse = run { it.complete(request) }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse {
        var spoke = false
        return run(canRetry = { !spoke }) { p -> p.stream(request) { spoke = true; onText(it) } }
    }

    private suspend fun run(canRetry: () -> Boolean = { true }, call: suspend (LlmProvider) -> LlmResponse): LlmResponse {
        val start = if (clock() < preferredUntil) preferred else 0
        val order = (providers.indices.drop(start) + providers.indices.take(start))
        var last: LlmException? = null
        for ((n, i) in order.withIndex()) {
            try {
                return call(providers[i]).also { if (i != 0) { preferred = i; preferredUntil = clock() + stickyMs } }
            } catch (e: LlmException) {
                last = e
                if (!canRetry()) throw e
                if (providers.size == 1 && e.kind in TRANSIENT) {
                    delay(retryDelayMs)
                    return call(providers[i])
                }
                if (e.kind !in HAND_OVER || n == order.lastIndex) throw e
            }
        }
        throw last ?: LlmException("no provider", LlmException.Kind.NOT_CONFIGURED)
    }

    companion object {
        /** Errors where another model may well answer. */
        val HAND_OVER = setOf(LlmException.Kind.RATE_LIMIT, LlmException.Kind.SERVER, LlmException.Kind.MODEL,
            LlmException.Kind.POLICY, LlmException.Kind.BAD_RESPONSE, LlmException.Kind.NO_TOOLS)
        val TRANSIENT = setOf(LlmException.Kind.RATE_LIMIT, LlmException.Kind.SERVER)
    }
}

/**
 * Some models think out loud in <think>…</think> before answering (Qwen3, DeepSeek-R1 on some hosts). That text
 * must be neither shown nor spoken. Works on a stream: tags split across pieces are held back until clear.
 */
class ThinkFilter {
    private val pending = StringBuilder()
    private var inside = false

    fun feed(delta: String): String {
        pending.append(delta)
        val out = StringBuilder()
        while (true) {
            if (inside) {
                val end = pending.indexOf(CLOSE)
                if (end < 0) { keepTail(CLOSE.length - 1); break }
                pending.delete(0, end + CLOSE.length)
                inside = false
                continue
            }
            val open = pending.indexOf(OPEN)
            if (open >= 0) {
                out.append(pending, 0, open)
                pending.delete(0, open + OPEN.length)
                inside = true
                continue
            }
            // Hold back a possible start of "<think>" at the end.
            val hold = (1 until OPEN.length).lastOrNull { k -> pending.length >= k && OPEN.startsWith(pending.substring(pending.length - k)) } ?: 0
            out.append(pending, 0, pending.length - hold)
            pending.delete(0, pending.length - hold)
            break
        }
        return out.toString()
    }

    fun finish(): String = if (inside) "" else pending.toString().also { pending.setLength(0) }

    private fun keepTail(n: Int) { if (pending.length > n) pending.delete(0, pending.length - n) }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"

        /** The visible part of a finished reply (also drops reasoning whose opening tag the host removed). */
        fun strip(text: String): String {
            var t = text
            val lone = t.indexOf(CLOSE)
            if (lone >= 0 && t.indexOf(OPEN).let { it < 0 || it > lone }) t = t.substring(lone + CLOSE.length)
            t = t.replace(Regex("""<think>[\s\S]*?</think>"""), "")
            val open = t.indexOf(OPEN)
            if (open >= 0) t = t.substring(0, open)
            return t.trim()
        }
    }
}
