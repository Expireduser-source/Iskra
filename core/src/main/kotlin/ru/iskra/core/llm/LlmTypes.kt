package ru.iskra.core.llm

import ru.iskra.core.i18n.tr
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

    /**
     * Same as [complete], but hands the reply text to [onText] piece by piece as it arrives, so speech
     * can start before the whole answer is ready. [onText] may be called from a network thread.
     * Providers without streaming deliver the whole text at once.
     */
    suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse =
        complete(request).also { if (it.text.isNotEmpty()) onText(it.text) }
}

class LlmException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE, NOT_CONFIGURED, BALANCE, LIMIT }

    /** Message for the user in the current language. Never contains the API key. */
    fun userMessage(): String = when (kind) {
        Kind.AUTH -> tr("Ключ API не подошёл. Проверь его в настройках. $OFFLINE", "The API key didn't work. Check it in Settings. $OFFLINE")
        Kind.RATE_LIMIT -> tr("Сервис модели просит подождать (лимит запросов). Попробуй через минуту.", "The model service asks to wait (request limit). Try again in a minute.")
        Kind.NETWORK -> tr("Нет связи с сервисом модели. Проверь интернет. $OFFLINE", "No connection to the model service. Check the internet. $OFFLINE")
        Kind.SERVER -> tr("Сервис модели сейчас отвечает ошибкой. Попробуй позже.", "The model service is returning errors right now. Try again later.")
        Kind.BAD_RESPONSE -> tr("Модель ответила в непонятном формате.", "The model answered in a format I don't understand.")
        Kind.NOT_CONFIGURED -> tr("Облачная модель не настроена — я работаю в простом офлайн-режиме.", "The cloud model isn't set up — I'm working in simple offline mode.")
        Kind.BALANCE -> tr("На счёте сервиса модели закончились деньги или лимит. Пополни баланс. $OFFLINE", "The model service account is out of money or credits. Top it up. $OFFLINE")
        Kind.LIMIT -> tr("Сегодняшний лимит облака, который ты задал, исчерпан. До завтра отвечаю без модели. $OFFLINE", "The daily cloud limit you set is used up. Until tomorrow I answer without the model. $OFFLINE")
    }

    /** Errors that will repeat on the next try: no point in asking again right away. */
    val persistent get() = kind == Kind.AUTH || kind == Kind.BALANCE || kind == Kind.NOT_CONFIGURED || kind == Kind.LIMIT

    private companion object {
        val OFFLINE get() = tr("Пока работаю без облака: будильники, таймеры, фонарик, приложения, заметки и напоминания — как обычно.", "For now I work without the cloud: alarms, timers, the flashlight, apps, notes and reminders work as usual.")
    }
}
