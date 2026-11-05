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
    /** The stable part of the instructions (persona, rules, tools): the same from message to message. */
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 600,
    val temperature: Double = 0.8,
    /**
     * What changes every message (time, mood, memory for this message, reminders). Cloud providers append it to the
     * system prompt; an on-device model reads it with the last message so its cache of the dialogue stays valid.
     */
    val situation: String = "",
) {
    val fullSystem: String get() = if (situation.isBlank()) system else system + "\n\n" + situation
}

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/** Every model backend (cloud or on-device) implements this. */
interface LlmProvider {
    val id: String
    /** A model running on the phone itself: short prompt, no tools, free and private. */
    val onDevice: Boolean get() = false
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
    enum class Kind {
        AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE, NOT_CONFIGURED, BALANCE, LIMIT,
        /** OpenRouter: today's free messages are used up (the counter resets at midnight UTC). */
        FREE_QUOTA,
        /** OpenRouter: the account's privacy settings exclude every provider of this free model. */
        POLICY,
        /** The model no longer exists (free models come and go). */
        MODEL,
        /** The model can't call tools; the same request works without them. */
        NO_TOOLS,
    }

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
        Kind.FREE_QUOTA -> tr("Бесплатные сообщения на сегодня закончились. До завтра отвечаю без модели. $OFFLINE",
            "Today's free messages are used up. Until tomorrow I answer without the model. $OFFLINE")
        Kind.POLICY -> tr("OpenRouter не даёт эту бесплатную модель из-за настроек приватности аккаунта: открой openrouter.ai/settings/privacy " +
            "и разреши бесплатные модели (они могут учиться на переписке) — или выбери платную модель. $OFFLINE",
            "OpenRouter won't serve this free model because of your account's privacy settings: open openrouter.ai/settings/privacy " +
            "and allow free models (they may learn from the chat) — or choose a paid model. $OFFLINE")
        Kind.NO_TOOLS -> tr("Эта модель не умеет действия с телефоном — отвечаю без них.", "This model can't do phone actions — I answer without them.")
        Kind.MODEL -> tr("Эта модель больше недоступна. Нажми в настройках «Подключить бесплатно» ещё раз — я выберу другую. $OFFLINE",
            "This model is no longer available. Tap “Connect for free” in Settings again — I'll pick another one. $OFFLINE")
    }

    /** Errors that will repeat on the next try: no point in asking again right away. */
    val persistent get() = kind == Kind.AUTH || kind == Kind.BALANCE || kind == Kind.NOT_CONFIGURED || kind == Kind.LIMIT ||
        kind == Kind.FREE_QUOTA || kind == Kind.POLICY || kind == Kind.MODEL

    private companion object {
        val OFFLINE get() = tr("Пока работаю без облака: будильники, таймеры, фонарик, приложения, заметки и напоминания — как обычно.", "For now I work without the cloud: alarms, timers, the flashlight, apps, notes and reminders work as usual.")
    }
}
