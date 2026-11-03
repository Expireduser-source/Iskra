package ru.iskra.core.llm

import kotlinx.serialization.json.JsonObject

/** Provider-neutral chat message. Tool results are messages with role TOOL. */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage
    data class ToolResult(val callId: String, val toolName: String, val content: String, val isError: Boolean = false) : ChatMessage
}

/** JSON-schema described tool the model may call. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object for the arguments ("type":"object", "properties":...). */
    val parameters: JsonObject,
)

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class LlmRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 600,
    val temperature: Double = 0.8,
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/** Every model backend (cloud or on-device) implements this. */
interface LlmProvider {
    val id: String
    suspend fun complete(request: LlmRequest): LlmResponse
}

class LlmException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE, NOT_CONFIGURED }

    /** Message suitable for the user (Russian). Never contains the API key. */
    fun userMessage(): String = when (kind) {
        Kind.AUTH -> "Ключ API не подошёл. Проверь его в настройках."
        Kind.RATE_LIMIT -> "Сервис модели просит подождать (лимит запросов). Попробуй через минуту."
        Kind.NETWORK -> "Нет связи с сервисом модели. Проверь интернет."
        Kind.SERVER -> "Сервис модели сейчас отвечает ошибкой. Попробуй позже."
        Kind.BAD_RESPONSE -> "Модель ответила в непонятном формате."
        Kind.NOT_CONFIGURED -> "Облачная модель не настроена — я работаю в простом офлайн-режиме."
    }
}
