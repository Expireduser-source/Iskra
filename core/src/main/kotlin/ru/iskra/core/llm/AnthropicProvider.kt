package ru.iskra.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("system", request.system)
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
