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

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("system", request.fullSystem)
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
            for (b in blocks) {
                val o = b.jsonObject
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> sb.append(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "tool_use" -> calls += ToolCall(
                        id = o["id"]!!.jsonPrimitive.content,
                        name = o["name"]!!.jsonPrimitive.content,
                        arguments = (o["input"] as? JsonObject) ?: JsonObject(emptyMap()),
                    )
                }
            }
            val usage = root["usage"] as? JsonObject
            return LlmResponse(
                text = sb.toString(),
                toolCalls = calls,
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        } catch (e: Exception) {
            throw LlmException("Unexpected response shape", LlmException.Kind.BAD_RESPONSE, e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Anthropic Messages streaming (message_start, content_block_start/delta/stop, message_delta,
 * message_stop, ping, error). Pure, so it is tested without a network.
 */
class AnthropicStreamParser {
    private class Block(val type: String, val id: String?, val name: String?) { val json = StringBuilder() }

    private val text = StringBuilder()
    private val blocks = sortedMapOf<Int, Block>()
    private var inputTokens = 0
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
                outputTokens = u.int("output_tokens") ?: outputTokens
            }
            "content_block_start" -> {
                val cb = o["content_block"] as? JsonObject ?: return null
                val type = cb.str("type") ?: return null
                blocks[o.int("index") ?: blocks.size] = Block(type, cb.str("id"), cb.str("name"))
                if (type == "text") return cb.str("text")?.takeIf { it.isNotEmpty() }?.also { text.append(it) }
            }
            "content_block_delta" -> {
                val d = o["delta"] as? JsonObject ?: return null
                when (d.str("type")) {
                    "text_delta" -> return d.str("text")?.takeIf { it.isNotEmpty() }?.also { text.append(it) }
                    "input_json_delta" -> blocks[o.int("index") ?: 0]?.json?.append(d.str("partial_json").orEmpty())
                }
            }
            "message_delta" -> (o["usage"] as? JsonObject)?.let { u ->
                outputTokens = u.int("output_tokens") ?: outputTokens
                inputTokens = u.int("input_tokens") ?: inputTokens
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
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
