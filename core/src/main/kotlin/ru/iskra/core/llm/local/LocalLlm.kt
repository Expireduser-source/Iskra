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
    @JvmStatic external fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, minP: Float,
                                     repeatPenalty: Float, sink: TokenSink): String
    @JvmStatic external fun cancel(handle: Long)
    /** "qwen2 1.5B Q4_K - Medium · ctx 4096 · 4 threads". */
    @JvmStatic external fun info(handle: Long): String
}

/** A loaded on-device model. One generation at a time. */
interface LlamaEngine : AutoCloseable {
    /** Continues [prompt]; [onPiece] gets the text as it comes (return false to stop). Blocking. */
    fun generate(prompt: String, maxTokens: Int, temperature: Float, onPiece: (String) -> Boolean): String
    fun cancel()
    val info: String
}

class NativeLlamaEngine private constructor(private val handle: Long) : LlamaEngine {
    @Volatile private var closed = false
    override val info: String get() = if (closed) "" else LlamaNative.info(handle)

    override fun generate(prompt: String, maxTokens: Int, temperature: Float, onPiece: (String) -> Boolean): String {
        check(!closed) { "engine closed" }
        return LlamaNative.generate(handle, prompt, maxTokens, temperature, 0.9f, 0.05f, 1.1f) { onPiece(it) }
    }

    override fun cancel() { if (!closed) LlamaNative.cancel(handle) }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        LlamaNative.free(handle)
    }

    companion object {
        /** null if the file isn't a model llama.cpp can load (or memory ran out). */
        fun open(path: String, nCtx: Int, nThreads: Int): NativeLlamaEngine? =
            LlamaNative.load(path, nCtx, nThreads).takeIf { it != 0L }?.let(::NativeLlamaEngine)
    }
}

/** How a model family expects the dialogue to be written. */
enum class ChatFormat {
    /** Qwen2.5 and most others: <|im_start|>role … <|im_end|>. */
    CHATML,
    /** Qwen3: ChatML with thinking switched off (an empty <think></think> before the answer). */
    CHATML_NO_THINK,
    /** Gemma: <start_of_turn>user/model, no system role. */
    GEMMA;

    /** Renders the dialogue; the per-message situation goes right before the last message, so the rest stays cached. */
    fun render(request: LlmRequest): String {
        val turns = request.messages.mapNotNull {
            when (it) {
                is ChatMessage.User -> "user" to it.text
                is ChatMessage.Assistant -> if (it.text.isBlank()) null else "assistant" to it.text
                is ChatMessage.ToolResult -> null
            }
        }
        val sb = StringBuilder()
        when (this) {
            CHATML, CHATML_NO_THINK -> {
                sb.append("<|im_start|>system\n").append(request.system.trim()).append("<|im_end|>\n")
                turns.forEachIndexed { i, (role, text) ->
                    if (i == turns.lastIndex && request.situation.isNotBlank())
                        sb.append("<|im_start|>system\n").append(request.situation.trim()).append("<|im_end|>\n")
                    sb.append("<|im_start|>").append(role).append('\n').append(text.trim()).append("<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
                if (this == CHATML_NO_THINK) sb.append("<think>\n\n</think>\n\n")
            }
            GEMMA -> {
                turns.forEachIndexed { i, (role, text) ->
                    val head = buildString {
                        if (i == 0) append(request.system.trim()).append("\n\n")
                        if (i == turns.lastIndex && request.situation.isNotBlank()) append(request.situation.trim()).append("\n\n")
                    }
                    sb.append("<start_of_turn>").append(if (role == "assistant") "model" else "user").append('\n')
                        .append(head).append(text.trim()).append("<end_of_turn>\n")
                }
                sb.append("<start_of_turn>model\n")
            }
        }
        return sb.toString()
    }

    /** Text that means the model started writing the next turn itself. */
    val stops: List<String> get() = when (this) {
        CHATML, CHATML_NO_THINK -> listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
        GEMMA -> listOf("<end_of_turn>", "<start_of_turn>")
    }
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
) : LlmProvider {
    override val onDevice: Boolean get() = true
    private val lock = Mutex()

    override suspend fun complete(request: LlmRequest): LlmResponse = stream(request) {}

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse = lock.withLock {
        val e = engine() ?: throw LlmException("on-device model unavailable", LlmException.Kind.NOT_CONFIGURED)
        val prompt = format.render(request)
        val think = ThinkFilter()
        val raw = StringBuilder()
        val text = withContext(Dispatchers.IO) {
            val scope = currentCoroutineContext()
            suspendCancellableCoroutine<String> { cont ->
                cont.invokeOnCancellation { e.cancel() }
                val result = try {
                    e.generate(prompt, minOf(maxTokens, request.maxTokens), request.temperature.toFloat().coerceIn(0.1f, 1.2f)) { piece ->
                        raw.append(piece)
                        val stop = format.stops.firstOrNull { raw.contains(it) }
                        if (stop != null) return@generate false
                        val visible = think.feed(piece)
                        if (visible.isNotEmpty()) onText(visible)
                        scope.isActive
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWith(Result.failure(LlmException("on-device: ${t.message}", LlmException.Kind.SERVER, t)))
                    return@suspendCancellableCoroutine
                }
                if (cont.isActive) cont.resume(result)
            }
        }
        val cut = format.stops.mapNotNull { s -> text.indexOf(s).takeIf { it >= 0 } }.minOrNull()
        LlmResponse(ThinkFilter.strip(if (cut != null) text.substring(0, cut) else text), emptyList())
    }
}
