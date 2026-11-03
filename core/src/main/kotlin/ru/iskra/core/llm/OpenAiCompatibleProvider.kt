package ru.iskra.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Works with any service implementing the OpenAI Chat Completions API:
 * OpenAI, OpenRouter, DeepSeek, Mistral, Groq, Together, a self-hosted vLLM/Ollama behind HTTPS, etc.
 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = Http.defaultClient,
    /** Newer OpenAI models reject "max_tokens" and require "max_completion_tokens". */
    private val useMaxCompletionTokens: Boolean = false,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmProvider {

    override val id: String = "openai-compatible:$model"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        if (apiKey.isBlank()) throw LlmException("No API key", LlmException.Kind.NOT_CONFIGURED)
        val body = buildBody(request).toString()
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = Http.execute(client, httpRequest)
        Http.checkStatus(code, text)
        return parse(text)
    }

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put(if (useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", request.system) })
            for (m in request.messages) add(encode(m))
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in request.tools) add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    })
                })
            })
        }
    }

    private fun encode(m: ChatMessage): JsonObject = when (m) {
        is ChatMessage.User -> buildJsonObject { put("role", "user"); put("content", m.text) }
        is ChatMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", m.text)
            if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                for (c in m.toolCalls) add(buildJsonObject {
                    put("id", c.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", c.name)
                        put("arguments", c.arguments.toString())
                    })
                })
            })
        }
        is ChatMessage.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.callId)
            put("content", m.content)
        }
    }

    internal fun parse(text: String): LlmResponse {
        try {
            val root = Json.parseToJsonElement(text).jsonObject
            val message = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            val content = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val calls = (message["tool_calls"] as? JsonArray).orEmpty().map { el ->
                val o = el.jsonObject
                val fn = o["function"]!!.jsonObject
                val rawArgs = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = if (rawArgs.isBlank()) JsonObject(emptyMap())
                else runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_${fn["name"]!!.jsonPrimitive.content}",
                    name = fn["name"]!!.jsonPrimitive.content,
                    arguments = args,
                )
            }
            val usage = root["usage"] as? JsonObject
            return LlmResponse(
                text = content,
                toolCalls = calls,
                inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Unexpected response shape", LlmException.Kind.BAD_RESPONSE, e)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
