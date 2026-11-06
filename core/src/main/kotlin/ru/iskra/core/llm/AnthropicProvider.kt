package ru.iskra.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Messages API (native tool use). */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val client: OkHttpClient = Http.defaultClient,
) : LlmProvider {

    override val id: String = "anthropic:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (apiKey.isBlank()) throw LlmException("No API key", LlmException.Kind.NOT_CONFIGURED)
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(buildBody(request).toString().toRequestBody(JSON))
            .build()
        val (code, text) = Http.execute(client, httpRequest)
        Http.checkStatus(code, text)
        return parse(text)
    }

    /**
     * Server-sent events: text is handed to [onText] as it arrives, tool_use input is assembled from
     * input_json_delta pieces. A server that ignores "stream" and answers with plain JSON still works.
     */
    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse {
        if (apiKey.isBlank()) throw LlmException("No API key", LlmException.Kind.NOT_CONFIGURED)
        val body = JsonObject(buildBody(request) + ("stream" to JsonPrimitive(true))).toString()
        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val parser = AnthropicStreamParser()
        val plain = StringBuilder()
        Http.streamLines(client, httpRequest) { line ->
            if (parser.sawEvents || line.startsWith("event:") || line.startsWith("data:")) parser.onLine(line)?.let(onText)
            else if (plain.length < 1_000_000) plain.append(line).append('\n')
        }
        if (!parser.sawEvents) return parse(plain.toString()).also { if (it.text.isNotEmpty()) onText(it.text) }
        return parser.result()
    }

    /**
     * The request as the model accepts it. Current Claude models refuse sampling parameters (Claude Haiku 5.5, the default,
     * answers `temperature: 0.8` with a 400), so none is sent. Thinking is turned off where the model allows it — replies
     * are short and often spoken, so speed matters more — and kept low where it can't be turned off. The stable part of
     * the instructions is cached ([LlmRequest.system], tools before it); the per-message situation follows uncached.
     */
    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("system", buildJsonArray {
            if (request.system.isNotBlank()) add(buildJsonObject {
                put("type", "text"); put("text", request.system)
                put("cache_control", buildJsonObject { put("type", "ephemeral") })
            })
            if (request.situation.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", request.situation) })
        })
        when (thinkingFor(model)) {
            Thinking.OFF -> put("thinking", buildJsonObject { put("type", "disabled") })
            Thinking.BETWEEN_TOOLS -> put("thinking", buildJsonObject { put("type", "between_tools") })
            Thinking.LOW -> put("output_config", buildJsonObject { put("effort", "low") })
            Thinking.DEFAULT -> Unit
        }
        put("messages", encodeMessages(request.messages))
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
            for (t in request.tools) add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", t.parameters)
            })
        })
    }

    /** Consecutive tool results must be grouped into one "user" message of tool_result blocks. */
    private fun encodeMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var pendingResults = mutableListOf<JsonObject>()
        fun flush() {
            if (pendingResults.isNotEmpty()) {
                val blocks = pendingResults
                add(buildJsonObject { put("role", "user"); put("content", JsonArray(blocks)) })
                pendingResults = mutableListOf()
            }
        }
        for (m in messages) when (m) {
            is ChatMessage.ToolResult -> pendingResults += buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", m.callId)
                put("content", m.content)
                if (m.isError) put("is_error", true)
            }
            is ChatMessage.User -> {
                flush()
                add(buildJsonObject { put("role", "user"); put("content", m.text) })
            }
            is ChatMessage.Assistant -> {
                flush()
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        // Thinking blocks go back unchanged, before the text and the tool call they led to.
                        m.providerBlocks.forEach { add(it) }
                        if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
                        for (c in m.toolCalls) add(buildJsonObject {
                            put("type", "tool_use"); put("id", c.id); put("name", c.name); put("input", c.arguments)
                        })
                    })
                })
            }
        }
        flush()
    }

    internal fun parse(text: String): LlmResponse {
        try {
            val root = Json.parseToJsonElement(text).jsonObject
            val blocks = root["content"] as JsonArray
            val sb = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            val kept = mutableListOf<JsonObject>()
            for (b in blocks) {
                val o = b.jsonObject
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "thinking", "redacted_thinking" -> kept += o
                    "text" -> sb.append(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "tool_use" -> calls += ToolCall(
                        id = o["id"]!!.jsonPrimitive.content,
                        name = o["name"]!!.jsonPrimitive.content,
                        arguments = (o["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                    )
                }
            }
            val usage = root["usage"] as? JsonObject
            fun u(k: String) = usage?.get(k)?.jsonPrimitive?.intOrNull ?: 0
            return LlmResponse(
                text = sb.toString(),
                toolCalls = calls,
                inputTokens = paidInput(u("input_tokens"), u("cache_creation_input_tokens"), u("cache_read_input_tokens")),
                outputTokens = u("output_tokens"),
                providerBlocks = kept,
            )
        } catch (e: Exception) {
            throw LlmException("Unexpected response shape", LlmException.Kind.BAD_RESPONSE, e)
        }
    }

    internal enum class Thinking { OFF, BETWEEN_TOOLS, LOW, DEFAULT }

    internal companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * How each Claude generation takes thinking: Claude Haiku 5.5, Claude Opus 5 and Claude Sonnet 5 think by default
         * and accept `disabled`; Claude Sonnet 5.5 turns it off with `between_tools`; Claude Opus 5.5 and the Fable and
         * Mythos models can't turn it off — low effort instead; older models don't think unless asked.
         */
        fun thinkingFor(model: String): Thinking {
            val m = model.lowercase().removePrefix("anthropic.")
            return when {
                m.startsWith("claude-sonnet-5-5") -> Thinking.BETWEEN_TOOLS
                m.startsWith("claude-opus-5-5") || m.startsWith("claude-fable") || m.startsWith("claude-mythos") -> Thinking.LOW
                m.startsWith("claude-haiku-5-5") || m.startsWith("claude-sonnet-5") || m.startsWith("claude-opus-5") -> Thinking.OFF
                else -> Thinking.DEFAULT
            }
        }

        /** Input tokens as they are paid for: cache writes cost 1.25×, cache reads 0.1× of the input rate. */
        fun paidInput(uncached: Int, cacheWrite: Int, cacheRead: Int): Int =
            uncached + (cacheWrite * 1.25).toInt() + (cacheRead * 0.1).toInt()
    }
}

/**
 * Anthropic Messages streaming (message_start, content_block_start/delta/stop, message_delta,
 * message_stop, ping, error). Pure, so it is tested without a network.
 */
class AnthropicStreamParser {
    private class Block(val type: String, val id: String?, val name: String?, val start: JsonObject? = null) {
        val json = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
    }

    private val text = StringBuilder()
    private val blocks = sortedMapOf<Int, Block>()
    private var inputTokens = 0
    private var cacheWrite = 0
    private var cacheRead = 0
    private var outputTokens = 0
    var sawEvents = false; private set
    var done = false; private set

    /** Returns the text delta carried by [line], if any. */
    fun onLine(line: String): String? {
        val l = line.trim()
        if (l.startsWith("event:")) { sawEvents = true; return null }
        if (!l.startsWith("data:")) return null
        sawEvents = true
        val data = l.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        val o = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        when (o.str("type")) {
            "message_start" -> ((o["message"] as? JsonObject)?.get("usage") as? JsonObject)?.let { u ->
                inputTokens = u.int("input_tokens") ?: inputTokens
                cacheWrite = u.int("cache_creation_input_tokens") ?: cacheWrite
                cacheRead = u.int("cache_read_input_tokens") ?: cacheRead
                outputTokens = u.int("output_tokens") ?: outputTokens
            }
            "content_block_start" -> {
                val cb = o["content_block"] as? JsonObject ?: return null
                val type = cb.str("type") ?: return null
                blocks[o.int("index") ?: blocks.size] = Block(type, cb.str("id"), cb.str("name"), cb).also { b ->
                    if (type == "thinking") { b.thinking.append(cb.str("thinking").orEmpty()); b.signature = cb.str("signature") }
                }
                if (type == "text") return cb.str("text")?.takeIf { it.isNotEmpty() }?.also { text.append(it) }
            }
            "content_block_delta" -> {
                val d = o["delta"] as? JsonObject ?: return null
                when (d.str("type")) {
                    "text_delta" -> return d.str("text")?.takeIf { it.isNotEmpty() }?.also { text.append(it) }
                    "input_json_delta" -> blocks[o.int("index") ?: 0]?.json?.append(d.str("partial_json").orEmpty())
                    "thinking_delta" -> blocks[o.int("index") ?: 0]?.thinking?.append(d.str("thinking").orEmpty())
                    "signature_delta" -> blocks[o.int("index") ?: 0]?.let { it.signature = (it.signature.orEmpty() + d.str("signature").orEmpty()) }
                }
            }
            "message_delta" -> (o["usage"] as? JsonObject)?.let { u ->
                outputTokens = u.int("output_tokens") ?: outputTokens
                inputTokens = u.int("input_tokens") ?: inputTokens
                cacheWrite = u.int("cache_creation_input_tokens") ?: cacheWrite
                cacheRead = u.int("cache_read_input_tokens") ?: cacheRead
            }
            "message_stop" -> done = true
            "error" -> {
                val err = o["error"] as? JsonObject
                val type = err?.str("type").orEmpty()
                val msg = err?.str("message").orEmpty()
                val code = when (type) {
                    "authentication_error" -> 401
                    "permission_error" -> 403
                    "rate_limit_error" -> 429
                    "invalid_request_error" -> 400
                    else -> 500 // api_error, overloaded_error
                }
                throw LlmException("Stream error $type: ${msg.take(200)}", Http.kindFor(code, msg))
            }
        }
        return null
    }

    fun result(): LlmResponse = LlmResponse(
        text = text.toString(),
        toolCalls = blocks.values.filter { it.type == "tool_use" && !it.name.isNullOrBlank() }.map { b ->
            val raw = b.json.toString()
            val args = if (raw.isBlank()) JsonObject(emptyMap())
            else runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            ToolCall(id = b.id ?: "toolu_${b.name}", name = b.name!!, arguments = args)
        },
        inputTokens = AnthropicProvider.paidInput(inputTokens, cacheWrite, cacheRead),
        outputTokens = outputTokens,
        // Thinking blocks as they came, to send back with a tool call: thinking text and signature put together.
        providerBlocks = blocks.values.mapNotNull { b ->
            when (b.type) {
                "thinking" -> buildJsonObject {
                    put("type", "thinking"); put("thinking", b.thinking.toString())
                    b.signature?.let { put("signature", it) }
                }
                "redacted_thinking" -> b.start
                else -> null
            }
        },
    )

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
