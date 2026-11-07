package ru.iskra.core.llm.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.llm.ThinkFilter
import kotlin.coroutines.resume

/** JNI bridge to llama.cpp (app/src/main/cpp/iskra_llama.cpp). The app loads the library before use. */
object LlamaNative {
    /** Gets the reply piece by piece; return false to stop. Called on the generating thread. */
    fun interface TokenSink { fun onToken(piece: String): Boolean }

    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun free(handle: Long)
    /** [head], [stable]: see [LlamaEngine.generate]. A [temperature] of 0 always picks the likeliest token (for tests). */
    @JvmStatic external fun generate(handle: Long, prompt: String, head: Int, stable: Int, maxTokens: Int, temperature: Float,
                                     topP: Float, minP: Float, repeatPenalty: Float, sink: TokenSink): String
    @JvmStatic external fun cancel(handle: Long)
    /** [tokens in the last prompt, how many of them came from the cache]. */
    @JvmStatic external fun lastRead(handle: Long): IntArray
    @JvmStatic external fun saveSession(handle: Long, path: String): Boolean
    @JvmStatic external fun loadSession(handle: Long, path: String): Int
    @JvmStatic external fun setThreads(handle: Long, gen: Int, batch: Int)
    /** [reading, writing] tokens per second with the current threads; empties the dialogue cache. */
    @JvmStatic external fun bench(handle: Long, nPrompt: Int, nGen: Int): FloatArray
    /** "qwen2 1.5B Q4_K - Medium · ctx 4096 · 4 threads". */
    @JvmStatic external fun info(handle: Long): String
}

/** A loaded on-device model. One generation at a time. */
interface LlamaEngine : AutoCloseable {
    /**
     * Continues [prompt]; [onPiece] gets the text as it comes (return false to stop). Blocking.
     * [stable]: how many characters at the start of [prompt] the next message's prompt will most likely repeat; [head]:
     * how many will stay even when the dialogue window moves on (the instructions). Models that can't cut their cache
     * back (Qwen3.5) save their state at both, so later messages don't re-read the dialogue.
     */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int = 0, head: Int = 0, onPiece: (String) -> Boolean): String
    fun cancel()
    val info: String
    /** Tokens the context holds: prompt plus reply must fit. */
    val contextSize: Int
    /** Tokens in the last prompt and how many of them were already in the cache (not computed again). */
    fun lastRead(): Pair<Int, Int> = 0 to 0
    /**
     * Saves the dialogue cache to [path], so the next load of this model doesn't read the whole dialogue again.
     * The file holds the conversation's tokens: keep it private and delete it with the conversation.
     */
    fun saveSession(path: String): Boolean = false
    /** Restores a cache saved by [saveSession] with the same model; the number of tokens it holds (0: nothing). */
    fun loadSession(path: String): Int = 0
    /** Threads for writing the answer and for reading a prompt. */
    fun setThreads(gen: Int, batch: Int) {}
    /** Speed with the current threads on throwaway tokens (the dialogue cache is emptied); null if unknown. */
    fun bench(nPrompt: Int, nGen: Int): EngineSpeed? = null
}

/** Tokens per second: reading a prompt (many at once) and writing an answer (one at a time). */
data class EngineSpeed(val read: Double, val write: Double)

class NativeLlamaEngine private constructor(private val handle: Long, override val contextSize: Int) : LlamaEngine {
    @Volatile private var closed = false
    override val info: String get() = if (closed) "" else LlamaNative.info(handle)

    override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
        check(!closed) { "engine closed" }
        return LlamaNative.generate(handle, prompt, head, stable, maxTokens, temperature, 0.9f, 0.05f, 1.1f) { onPiece(it) }
    }

    override fun lastRead(): Pair<Int, Int> = if (closed) 0 to 0 else LlamaNative.lastRead(handle).let { it[0] to it[1] }
    override fun saveSession(path: String): Boolean = !closed && LlamaNative.saveSession(handle, path)
    override fun loadSession(path: String): Int = if (closed) 0 else LlamaNative.loadSession(handle, path)
    override fun setThreads(gen: Int, batch: Int) { if (!closed) LlamaNative.setThreads(handle, gen, batch) }
    override fun bench(nPrompt: Int, nGen: Int): EngineSpeed? =
        if (closed) null else LlamaNative.bench(handle, nPrompt, nGen).let { if (it[0] > 0f && it[1] > 0f) EngineSpeed(it[0].toDouble(), it[1].toDouble()) else null }

    override fun cancel() { if (!closed) LlamaNative.cancel(handle) }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        LlamaNative.free(handle)
    }

    companion object {
        /** null if the file isn't a model llama.cpp can load (or memory ran out). */
        fun open(path: String, nCtx: Int, nThreads: Int): NativeLlamaEngine? =
            LlamaNative.load(path, nCtx, nThreads).takeIf { it != 0L }?.let { NativeLlamaEngine(it, nCtx) }
    }
}

/** How a model family expects the dialogue to be written. */
enum class ChatFormat {
    /** Qwen2.5 and most others: <|im_start|>role … <|im_end|>. */
    CHATML,
    /** Qwen3: ChatML with thinking switched off (an empty <think></think> before the answer). */
    CHATML_NO_THINK,
    /** Gemma 2/3: <start_of_turn>user/model, no system role. */
    GEMMA,
    /**
     * Gemma 4: <|turn>system/user/model … <turn|>. Thinking is off when the system turn has no <|think|>; then E2B and E4B
     * answer right after <|turn>model (Google's model card). An empty thought channel there — what the bigger models
     * write themselves — made E2B think aloud for a hundred tokens before most answers (ARM evaluation, 0.12.7).
     */
    GEMMA4,
    /** Llama 3.x and the many models built on it: <|start_header_id|>role<|end_header_id|> … <|eot_id|>. */
    LLAMA3,
    /** Mistral and Mixtral and their fine-tunes: [INST] … [/INST] answer</s>; no system role, it goes into the first message. */
    MISTRAL,
    /** Phi-3 / Phi-4-mini: <|system|> / <|user|> / <|assistant|> … <|end|>. */
    PHI;

    companion object

    /** Renders the dialogue; the per-message situation goes right before the last message, so the rest stays cached. */
    fun render(request: LlmRequest): String = prompt(request).text

    /**
     * The rendered dialogue and its [Prompt.stable] part: the next message's prompt repeats it, because the situation
     * and the answer header (Qwen's empty <think>, Gemma 4's thought channel) appear only at the end of this one.
     */
    fun prompt(request: LlmRequest): Prompt {
        val turns = request.messages.mapNotNull {
            when (it) {
                is ChatMessage.User -> "user" to it.text
                is ChatMessage.Assistant -> if (it.text.isBlank()) null else "assistant" to it.text
                is ChatMessage.ToolResult -> null
            }
        }
        val situation = request.situation.trim()
        val sb = StringBuilder()
        var stable = 0
        var head = 0
        when (this) {
            CHATML, CHATML_NO_THINK -> {
                sb.append("<|im_start|>system\n").append(request.systemWithProfile).append("<|im_end|>\n")
                head = sb.length
                turns.forEachIndexed { i, (role, text) ->
                    val last = i == turns.lastIndex
                    if (last) stable = sb.length
                    if (last && situation.isNotEmpty()) sb.append("<|im_start|>system\n").append(situation).append("<|im_end|>\n")
                    sb.append("<|im_start|>").append(role).append('\n').append(text.trim()).append("<|im_end|>\n")
                    if (last && situation.isEmpty()) stable = sb.length
                }
                if (turns.isEmpty()) stable = sb.length
                sb.append("<|im_start|>assistant\n")
                if (this == CHATML_NO_THINK) sb.append("<think>\n\n</think>\n\n")
            }
            GEMMA -> {
                // Read ahead before the first message: the instructions open its turn, so that much is read now.
                if (turns.isEmpty() && request.warmUp) {
                    sb.append("<start_of_turn>user\n").append(request.systemWithProfile).append("\n\n")
                    head = sb.length; stable = sb.length
                    return Prompt(sb.toString(), stable, head)
                }
                turns.forEachIndexed { i, (role, text) ->
                    val last = i == turns.lastIndex
                    sb.append("<start_of_turn>").append(if (role == "assistant") "model" else "user").append('\n')
                    if (i == 0) { sb.append(request.systemWithProfile).append("\n\n"); head = sb.length }
                    if (last && situation.isNotEmpty()) { stable = sb.length; sb.append(situation).append("\n\n") }
                    sb.append(text.trim()).append("<end_of_turn>\n")
                    if (last && situation.isEmpty()) stable = sb.length
                }
                sb.append("<start_of_turn>model\n")
            }
            GEMMA4 -> {
                sb.append("<|turn>system\n").append(request.system.trim())
                // Tools are declared in the system turn, as Google's template does (Gemma4Tools); the facts about the person,
                // which change now and then, come after them, so a new fact doesn't make the model read the tools again.
                if (request.tools.isNotEmpty()) sb.append(Gemma4Tools.declarations(request.tools))
                if (request.profile.isNotBlank()) sb.append("\n\n").append(request.profile.trim())
                sb.append("<turn|>\n")
                head = sb.length
                stable = sb.length
                val msgs = request.messages
                val lastUser = msgs.indexOfLast { it is ChatMessage.User }
                // After a tool's result the model goes on in the same turn: no closing tag, no new header.
                var open = false
                var i = 0
                while (i < msgs.size) {
                    when (val m = msgs[i]) {
                        is ChatMessage.User -> {
                            if (open) { sb.append("<turn|>\n"); open = false }
                            // A system turn is only expected first, so the per-message situation leads the last message.
                            val last = i == lastUser
                            sb.append("<|turn>user\n")
                            if (last && situation.isNotEmpty()) { stable = sb.length; sb.append(situation).append("\n\n") }
                            sb.append(m.text.trim()).append("<turn|>\n")
                            if (last && situation.isEmpty()) stable = sb.length
                        }
                        is ChatMessage.Assistant -> if (m.text.isNotBlank() || m.toolCalls.isNotEmpty()) {
                            if (!open) sb.append("<|turn>model\n")
                            // As the model wrote it: its words first, then the call and right after it the result.
                            if (m.text.isNotBlank()) sb.append(m.text.trim())
                            if (m.toolCalls.isEmpty()) { sb.append("<turn|>\n"); open = false } else {
                                for (c in m.toolCalls) {
                                    sb.append(Gemma4Tools.call(c))
                                    val r = msgs.drop(i + 1).takeWhile { it is ChatMessage.ToolResult }.map { it as ChatMessage.ToolResult }
                                        .firstOrNull { it.callId == c.id }
                                    sb.append(Gemma4Tools.response(c.name, r?.content ?: ""))
                                }
                                while (i + 1 < msgs.size && msgs[i + 1] is ChatMessage.ToolResult) i++
                                open = true
                            }
                        }
                        is ChatMessage.ToolResult -> Unit // written right after its call
                    }
                    i++
                }
                if (!open) {
                    sb.append("<|turn>model\n")
                    forcedCall(request)?.let { sb.append(it) }
                }
            }
            LLAMA3, PHI -> {
                // Like ChatML: instructions first, the situation as a system turn right before the last message.
                fun open(role: String) = if (this == LLAMA3) "<|start_header_id|>$role<|end_header_id|>\n\n" else "<|$role|>\n"
                val close = if (this == LLAMA3) "<|eot_id|>" else "<|end|>\n"
                sb.append(open("system")).append(request.systemWithProfile).append(close)
                head = sb.length
                turns.forEachIndexed { i, (role, text) ->
                    val last = i == turns.lastIndex
                    if (last) stable = sb.length
                    if (last && situation.isNotEmpty()) sb.append(open("system")).append(situation).append(close)
                    sb.append(open(role)).append(text.trim()).append(close)
                    if (last && situation.isEmpty()) stable = sb.length
                }
                if (turns.isEmpty()) stable = sb.length
                sb.append(open("assistant"))
            }
            MISTRAL -> {
                // [INST] instructions\n\nfirst message [/INST] answer</s>[INST] situation\n\nlast message [/INST]
                if (turns.isEmpty() && request.warmUp) {
                    sb.append("[INST] ").append(request.systemWithProfile).append("\n\n")
                    head = sb.length; stable = sb.length
                    return Prompt(sb.toString(), stable, head)
                }
                turns.forEachIndexed { i, (role, text) ->
                    val last = i == turns.lastIndex
                    if (role == "assistant") { sb.append(' ').append(text.trim()).append("</s>"); return@forEachIndexed }
                    sb.append("[INST] ")
                    if (i == 0) { sb.append(request.systemWithProfile).append("\n\n"); head = sb.length }
                    if (last && situation.isNotEmpty()) { stable = sb.length; sb.append(situation).append("\n\n") }
                    sb.append(text.trim()).append(" [/INST]")
                    if (last && situation.isEmpty()) stable = sb.length
                }
            }
        }
        return Prompt(sb.toString(), stable, head)
    }

    /** The beginning of the call [LlmRequest.forceTool] asks for, written for the model (Gemma 4), or null. */
    fun forcedCall(request: LlmRequest): String? = request.forceTool
        ?.takeIf { this == GEMMA4 && request.tools.any { t -> t.name == it } && request.messages.lastOrNull() is ChatMessage.User }
        ?.let { Gemma4Tools.CALL_OPEN + "call:" + it }

    /**
     * Characters per token, a cautious first guess for fitting the dialogue until the model's own prompts are measured:
     * Gemma's large vocabulary takes Russian in longer pieces than Qwen's.
     */
    val charsPerToken: Double get() = when (this) {
        GEMMA, GEMMA4 -> 3.0
        else -> 2.5
    }

    /** Text that means the model started writing the next turn itself. */
    val stops: List<String> get() = when (this) {
        CHATML, CHATML_NO_THINK -> listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
        GEMMA -> listOf("<end_of_turn>", "<start_of_turn>")
        GEMMA4 -> listOf("<turn|>", "<|turn>", Gemma4Tools.CALL_CLOSE)
        LLAMA3 -> listOf("<|eot_id|>", "<|start_header_id|>", "<|end_of_text|>")
        MISTRAL -> listOf("</s>", "[INST]")
        PHI -> listOf("<|end|>", "<|user|>", "<|assistant|>", "<|endoftext|>")
    }
}

/**
 * A rendered prompt. The first [stable] characters will most likely start the next message's prompt too; the first
 * [head] (the instructions) stay even when the dialogue window moves on.
 */
data class Prompt(val text: String, val stable: Int, val head: Int = 0)

/**
 * How one generation went: for the journal and the speed check. Pieces are about one token each. Of the
 * [promptTokens], only [newTokens] were computed; the rest came from the dialogue cache.
 */
data class GenStats(val promptChars: Int, val firstPieceMs: Long, val pieces: Int, val chars: Int, val totalMs: Long,
                    val promptTokens: Int = 0, val newTokens: Int = 0) {
    /** Pieces per second after the first one (the reading of the prompt is over by then). */
    val piecesPerSecond: Double get() = if (pieces > 1 && totalMs > firstPieceMs) (pieces - 1) * 1000.0 / (totalMs - firstPieceMs) else 0.0
}

/**
 * The model on the phone as an [LlmProvider]: no tools, a short prompt, a dialogue cache kept between messages.
 * [engine] loads the model on first use (seconds) and may return null if it can't.
 */
class LocalLlmProvider(
    private val engine: suspend () -> LlamaEngine?,
    private val format: ChatFormat,
    override val id: String,
    private val maxTokens: Int = 320,
    private val onStats: ((GenStats) -> Unit)? = null,
) : LlmProvider {
    override val onDevice: Boolean get() = true
    override val nativeTools: Boolean get() = format == ChatFormat.GEMMA4
    private val lock = Mutex()
    /** Replies since the last one with a «мрр»: small models began every reply with it, copying their own history. */
    private var sincePurr = 3
    /** Characters per token of this model's prompts, measured on the last long one; null before it. */
    @Volatile private var charsPerToken: Double? = null

    override suspend fun complete(request: LlmRequest): LlmResponse = stream(request) {}

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse = lock.withLock {
        // The engine refuses a prompt longer than its context before reading any of it: then the dialogue is fitted again,
        // with the measure of a token taken smaller.
        var perToken = charsPerToken?.let { it * 0.9 } ?: format.charsPerToken
        repeat(2) {
            try {
                return@withLock generateOnce(request, onText, perToken)
            } catch (e: PromptTooLong) {
                perToken *= 0.7
            }
        }
        try {
            generateOnce(request, onText, perToken)
        } catch (t: PromptTooLong) {
            throw LlmException("on-device: prompt too long for the context", LlmException.Kind.SERVER, t)
        }
    }

    /** The engine said the prompt doesn't fit its context (nothing was read or shown). */
    private class PromptTooLong(cause: Throwable) : Exception(cause)

    private suspend fun generateOnce(request: LlmRequest, onText: (String) -> Unit, perToken: Double): LlmResponse {
        val e = engine() ?: throw LlmException("on-device model unavailable", LlmException.Kind.NOT_CONFIGURED)
        val budget = minOf(maxTokens, request.maxTokens)
        val rendered = format.prompt(fit(request, e.contextSize - budget, perToken))
        val prompt = rendered.text
        if (request.warmUp) return warmUp(e, rendered)
        // Small Qwen models sometimes slip Chinese characters into Russian or English; unless the person wrote them, drop them.
        val lastUser = (request.messages.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
        // …and Llama 3.2 1B slipped in Devanagari («Мैं Аня»): letters of a script the person didn't write in go, unless they
        // asked about another language («как это по-японски?»).
        val allowed = BASE_SCRIPTS + scriptsOf(lastUser)
        val guard = !OTHER_LANGUAGE.containsMatchIn(lastUser)
        // Gemma 4 answers straight away with thinking off; if it opens its thought channel anyway, nothing is shown until
        // the channel closes. The beginning waits only while it could still be the channel's opening tag.
        val forced = format.forcedCall(request)
        // The tools offered this turn: a reply that begins with one of them and «{» is a call written without its markup.
        val toolNames = if (format == ChatFormat.GEMMA4) request.tools.map { it.name } else emptyList()
        var gate = when {
            forced != null -> Gate.CALL
            format == ChatFormat.GEMMA4 -> Gate.WAIT
            else -> Gate.OPEN
        }
        val held = StringBuilder()
        val think = ThinkFilter()
        val raw = StringBuilder()
        val t0 = System.nanoTime()
        var firstMs = -1L
        var pieces = 0
        // The beginning is held until its first word is complete, so stray punctuation (", , , Мрр" → "Мрр") and an
        // over-used «Мрр,» (a reply may begin with it only if the two before didn't) go before anything is shown or spoken.
        val dropPurr = sincePurr < 2
        val lead = StringBuilder()
        var leadDone = false
        val text = withContext(Dispatchers.IO) {
            val scope = currentCoroutineContext()
            suspendCancellableCoroutine<String> { cont ->
                cont.invokeOnCancellation { e.cancel() }
                val result = try {
                    e.generate(prompt, budget, request.temperature.toFloat().coerceIn(0.1f, 1.2f), rendered.stable, rendered.head) { piece ->
                        if (firstMs < 0) firstMs = (System.nanoTime() - t0) / 1_000_000
                        pieces++
                        raw.append(piece)
                        // Two empty lines after some words: a small model starting another version of its reply (Gemma 3 1B:
                        // «Привет! Всё отлично…» and then «Я поняла тебя, ты хочешь узнать, как я?…», «Ах... ничего особенного…»).
                        val stop = format.stops.firstOrNull { raw.contains(it) } ?: SECOND_TAKE.takeIf { piece.contains('\n') && sampleBreak(raw.toString()) >= 0 }
                        if (stop != null) return@generate false
                        val incoming = when (gate) {
                            Gate.OPEN -> piece
                            Gate.CALL -> ""
                            else -> {
                                held.append(piece)
                                if (gate == Gate.WAIT) {
                                    val h = held.toString().trimStart()
                                    gate = when {
                                        h.startsWith(THOUGHT_OPEN) -> Gate.THINKING
                                        h.startsWith(Gemma4Tools.CALL_OPEN) -> Gate.CALL
                                        THOUGHT_OPEN.startsWith(h) || Gemma4Tools.CALL_OPEN.startsWith(h) -> Gate.WAIT
                                        // A call written without its markup is not shown either (E4B, 0.15.0).
                                        else -> when (Gemma4Tools.bareStart(h, toolNames)) { true -> Gate.CALL; null -> Gate.WAIT; false -> Gate.OPEN }
                                    }
                                }
                                when (gate) {
                                    Gate.WAIT, Gate.CALL -> ""
                                    Gate.OPEN -> held.toString().also { held.clear() }
                                    Gate.THINKING -> held.indexOf(THOUGHT_CLOSE).let { end ->
                                        if (end < 0) "" else held.substring(end + THOUGHT_CLOSE.length).also { held.clear(); gate = Gate.OPEN }
                                    }
                                }
                            }
                        }.let { s ->
                            // A tool call after some words: the words are shown, the call is not.
                            if (format != ChatFormat.GEMMA4 || gate != Gate.OPEN) s
                            else s.indexOf(Gemma4Tools.CALL_OPEN).let { at -> if (at < 0) s else { gate = Gate.CALL; s.substring(0, at) } }
                        }
                        if (incoming.isEmpty()) return@generate scope.isActive
                        var visible = think.feed(incoming).let { if (guard) stripForeign(it, allowed) else it }
                        if (!leadDone) {
                            lead.append(visible)
                            val trimmed = trimLead(lead.toString())
                            visible = if (trimmed.length < 40 && !LEAD_READY.containsMatchIn(trimmed)) "" else {
                                leadDone = true
                                cleanStart(trimmed, dropPurr)
                            }
                        }
                        if (visible.isNotEmpty()) onText(visible)
                        scope.isActive
                    }
                } catch (t: Throwable) {
                    val failure = if (t is IllegalArgumentException && t.message.orEmpty().contains("too long") && pieces == 0) PromptTooLong(t)
                        else LlmException("on-device: ${t.message}", LlmException.Kind.SERVER, t)
                    if (cont.isActive) cont.resumeWith(Result.failure(failure))
                    return@suspendCancellableCoroutine
                }
                if (cont.isActive) cont.resume(result)
            }
        }
        if (!leadDone) cleanStart(lead.toString(), dropPurr).takeIf { it.isNotEmpty() }?.let(onText)
        val cut = (format.stops.mapNotNull { s -> text.indexOf(s).takeIf { it >= 0 } } + listOfNotNull(sampleBreak(text).takeIf { it >= 0 })).minOrNull()
        val (promptTokens, reused) = e.lastRead()
        // The real measure of a token for the next dialogue fitting (a short prompt says little: markup weighs more).
        if (promptTokens >= 200) charsPerToken = prompt.length.toDouble() / promptTokens
        onStats?.invoke(GenStats(prompt.length, firstMs, pieces, text.length, (System.nanoTime() - t0) / 1_000_000, promptTokens, promptTokens - reused))
        var reply = ThinkFilter.strip(if (cut != null) text.substring(0, cut) else text)
        // What was shown is the part after the thought channel; the reply kept in the chat is the same.
        if (format == ChatFormat.GEMMA4) reply = reply.substringAfterLast(THOUGHT_CLOSE).trim()
        // Gemma 4's tool calls: the words before the first call are the reply, the calls go to the agent (none if no tool
        // was offered — a call made up anyway is just not shown).
        val (said, made) = if (format == ChatFormat.GEMMA4) Gemma4Tools.parse(forced.orEmpty() + reply, toolNames) else reply to emptyList()
        val calls = if (request.tools.isEmpty()) emptyList() else made.filter { c -> request.tools.any { it.name == c.name } }
        reply = said
        val final = cleanStart(if (guard) stripForeign(reply, allowed).replace(Regex(" {2,}"), " ").replace(Regex(" ([,.!?])"), "$1").trim() else reply, dropPurr).trim()
        sincePurr = if (PURR.containsMatchIn(final)) 0 else sincePurr + 1
        return LlmResponse(final, calls)
    }

    /**
     * Reads [rendered] into the engine's cache and writes nothing (one token, dropped): the next message's prompt begins
     * the same, so only its own part is read then.
     */
    private suspend fun warmUp(e: LlamaEngine, rendered: Prompt): LlmResponse {
        val t0 = System.nanoTime()
        withContext(Dispatchers.IO) {
            try {
                e.generate(rendered.text, 1, 0.1f, rendered.stable, rendered.head) { false }
            } catch (t: IllegalArgumentException) {
                if (t.message.orEmpty().contains("too long")) throw PromptTooLong(t)
                throw LlmException("on-device: ${t.message}", LlmException.Kind.SERVER, t)
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val (promptTokens, reused) = e.lastRead()
        if (promptTokens >= 200) charsPerToken = rendered.text.length.toDouble() / promptTokens
        onStats?.invoke(GenStats(rendered.text.length, ms, 0, 0, ms, promptTokens, promptTokens - reused))
        return LlmResponse("", emptyList())
    }

    /** Gemma 4's start of a reply: waiting to see whether it opens the thought channel, inside it, or the answer itself. */
    private enum class Gate { WAIT, THINKING, OPEN, CALL }

    companion object {
        private const val THOUGHT_OPEN = "<|channel>"
        private const val SECOND_TAKE = "\n\n\n"

        /** Where two empty lines follow some words of the reply (not of a thought channel), or -1. */
        fun sampleBreak(s: String): Int {
            val at = s.indexOf(SECOND_TAKE)
            if (at < 0) return -1
            val before = s.substring(0, at)
            return if (before.substringAfterLast(THOUGHT_CLOSE).any { it.isLetter() } && !before.substringAfterLast(THOUGHT_CLOSE).contains(THOUGHT_OPEN)) at else -1
        }
        private const val THOUGHT_CLOSE = "<channel|>"

        /** Spaces and punctuation that can't start a reply (left over when a model begins with stray symbols). */
        fun trimLead(s: String): String = s.trimStart { it.isWhitespace() || it in ",，、;；:：" }

        /** The first word (after a mood tag) is complete. */
        private val LEAD_READY = Regex("""^(?:\[[^\]\n]*]\s*)?[^\s\[]+[\s,.!?…—–-]""")
        // (?iu): Java ignores case for Cyrillic only with the Unicode flag.
        private val PURR_START = Regex("""(?iu)^((?:\[[^\]\n]{1,24}]\s*)?)(?:м+р+|мур+|m+r+|purr+)(?![\p{L}])[\s,.!?…—–-]*""")
        private val PURR = Regex("""(?iu)(?<![\p{L}])(?:м+р+|мур+|m+r+|purr+)(?![\p{L}])""")

        /** [trimLead], and with [dropPurr] a leading «Мрр,» (after the mood tag) — unless the reply is nothing else. */
        fun cleanStart(s: String, dropPurr: Boolean): String {
            val t = trimLead(s)
            if (!dropPurr) return t
            val m = PURR_START.find(t) ?: return t
            val rest = t.substring(m.range.last + 1)
            if (rest.isBlank()) return t
            return m.groupValues[1] + rest.replaceFirstChar { it.uppercase() }
        }

        /** Chinese, Japanese and Korean letters. */
        fun isCjk(c: Char): Boolean {
            if (c.code < 0x2E80) return false
            return when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }

        /** Scripts a reply may always use: Latin, Cyrillic, Greek (formulas), digits and punctuation. */
        val BASE_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)

        /** «как это по-японски?», «переведи на арабский», "how do you write it in Chinese": another script is the point. */
        private val OTHER_LANGUAGE = Regex("""(?iu)перев[ео]д|переведи|по-\p{L}+ски|на\s+\p{L}+ск(?:ом|ий|ой)|иероглиф|алфавит|translat|""" +
            """\bin\s+(?:japanese|chinese|korean|arabic|hindi|hebrew|thai|greek|georgian|armenian)\b|how\s+(?:do\s+you|to)\s+(?:say|write|spell)""")

        /** The scripts the letters of [s] are written in. */
        fun scriptsOf(s: String): Set<Character.UnicodeScript> = s.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.toSet()

        /**
         * Drops letters of scripts not in [allowed] (CJK, Devanagari, Arabic… slipped in by a small model); full-width
         * punctuation (，。！) becomes ordinary, with a space before the next word.
         */
        fun stripForeign(s: String, allowed: Set<Character.UnicodeScript>): String {
            val out = StringBuilder(s.length)
            for (c in s) {
                val ch = when {
                    c in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                    c == '\u3002' -> '.'
                    c == '\u3001' -> ','
                    c == '\u3000' -> ' '
                    else -> c
                }
                if (ch in '\u3003'..'\u303F') continue
                // Letters and their marks («ैं» in «मैं») alike; emoji halves (surrogates) and unassigned code points stay.
                if (!ch.isSurrogate()) {
                    val script = Character.UnicodeScript.of(ch.code)
                    if (script !in allowed && script != Character.UnicodeScript.UNKNOWN) continue
                }
                if (out.isNotEmpty() && out.last() in ",.!?:;" && ch.isLetter()) out.append(' ')
                out.append(ch)
            }
            return out.toString()
        }

        /** Drops CJK letters; full-width punctuation (，。！) becomes ordinary, with a space before the next word. */
        fun stripCjk(s: String): String {
            val out = StringBuilder(s.length)
            for (c in s) {
                val ch = when {
                    c in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                    c == '\u3002' -> '.'
                    c == '\u3001' -> ','
                    c == '\u3000' -> ' '
                    else -> c
                }
                if (isCjk(ch) || ch in '\u3003'..'\u303F') continue
                if (out.isNotEmpty() && out.last() in ",.!?:;" && ch.isLetter()) out.append(' ')
                out.append(ch)
            }
            return out.toString()
        }

        /** A cautious guess: Russian is about 2.5 characters per token in small vocabularies (Qwen), English about 4. */
        fun estimateTokens(text: String, charsPerToken: Double = 2.5): Int = (text.length / charsPerToken).toInt() + 8

        /**
         * The dialogue that fits into [tokens]: the oldest turns go first; the person's last message and what the model
         * did for it (its tool calls and their results) always stay — a message longer than the room (a long shared
         * text) keeps its beginning and end. [charsPerToken]: measured on this model's prompts, or a cautious guess.
         */
        fun fit(request: LlmRequest, tokens: Int, charsPerToken: Double = 2.5): LlmRequest {
            val room = (tokens * 0.92).toInt()
            // Each text's own share; the turn's markup is counted per message below (and in the measure of a token).
            fun est(text: String) = if (text.isEmpty()) 0 else (text.length / charsPerToken).toInt() + 2
            val fixed = est(request.system) + est(request.profile) + est(request.situation) +
                (if (request.tools.isEmpty()) 0 else est(Gemma4Tools.declarations(request.tools)))
            fun size(messages: List<ChatMessage>) = fixed + messages.sumOf {
                when (it) {
                    is ChatMessage.User -> est(it.text)
                    is ChatMessage.Assistant -> est(it.text) + it.toolCalls.sumOf { c -> est(c.name + c.arguments.toString()) }
                    is ChatMessage.ToolResult -> est(it.content) + 12
                }
            } + 6 * messages.size
            // With Gemma 4's tools the guess (2.5 characters per token) went over the room after a call: everything was
            // dropped but the tool's result, and the model, seeing no dialogue, greeted the person anew («Привет! Я рада
            // познакомиться, Аня») right after setting their reminder (ARM evaluation, 0.14.0).
            val current = request.messages.indexOfLast { it is ChatMessage.User }.coerceAtLeast(0)
            val now = request.messages.subList(current, request.messages.size).toList()
            var older = request.messages.subList(0, current).toList()
            while (older.isNotEmpty() && size(older + now) > room) {
                // Drop the oldest turn; keep the dialogue starting with the person.
                older = older.drop(1)
                while (older.isNotEmpty() && older.first() !is ChatMessage.User) older = older.drop(1)
            }
            var messages = older + now
            val over = size(messages) - room
            val ask = messages.indexOfLast { it is ChatMessage.User }
            if (over > 0 && ask >= 0) {
                val text = (messages[ask] as ChatMessage.User).text
                val keep = (text.length - (over * charsPerToken).toInt() - 20).coerceAtLeast(200)
                if (keep < text.length) {
                    val head = text.take(keep * 3 / 5)
                    val tail = text.takeLast(keep - head.length)
                    messages = messages.toMutableList().also { it[ask] = ChatMessage.User("$head … $tail") }
                }
            }
            return request.copy(messages = messages)
        }
    }
}
