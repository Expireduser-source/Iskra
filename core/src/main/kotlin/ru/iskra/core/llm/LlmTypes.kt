package ru.iskra.core.llm

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.JsonObject

/** Provider-neutral chat message. Tool results are messages with role TOOL. */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    /**
     * [providerBlocks]: blocks the provider must get back unchanged with this turn — Claude's `thinking` blocks before a
     * tool call; opaque to everything else.
     */
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList(),
                         val providerBlocks: List<kotlinx.serialization.json.JsonObject> = emptyList()) : ChatMessage
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
    /**
     * A small model's steady facts about the person: after the instructions — and after Gemma 4's tool declarations, so a
     * newly learned fact doesn't make it read the tools again (23 s per new fact in the 0.14.0 evaluation) — and before
     * the dialogue.
     */
    val profile: String = "",
    /**
     * A tool the model must call now (one of [tools]): a model on the phone that promised «я поставлю напоминание» and
     * didn't call it gets the beginning of that call written for it and fills in the arguments (Gemma 4). Others ignore it.
     */
    val forceTool: String? = null,
    /**
     * Only read the prompt into an on-device model's cache, ahead of the next message ([ru.iskra.core.agent.CompanionAgent.warmUpPhone]):
     * nothing is written or shown. Others ignore it.
     */
    val warmUp: Boolean = false,
    /**
     * A task beside the dialogue (her diary, a recap): an on-device model doesn't learn from it how long its tokens are —
     * a diary's prompt measured differently, and the morning's dialogue was fitted with other turns than the night had
     * read ahead, so the first reply of the day read the whole dialogue again (Gemma 4, ARM evaluation 0.16.1).
     */
    val outsideDialogue: Boolean = false,
) {
    /** The instructions with the steady facts, for a format that has nothing between them. */
    val systemWithProfile: String get() = if (profile.isBlank()) system.trim() else system.trim() + "\n\n" + profile.trim()
    val fullSystem: String get() = listOf(system, profile, situation).filter { it.isNotBlank() }.joinToString("\n\n")
}

data class LlmResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    /** Input as it is paid for: with a prompt cache, writes count 1.25× and reads 0.1× (Anthropic's rates). */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** To send back with this turn if the model calls a tool ([ChatMessage.Assistant.providerBlocks]). */
    val providerBlocks: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)

/** Every model backend (cloud or on-device) implements this. */
interface LlmProvider {
    val id: String
    /** A model running on the phone itself: short prompt, free and private. */
    val onDevice: Boolean get() = false
    /** A model on the phone that calls tools in its own markup (Gemma 4); other phone models get none. */
    val nativeTools: Boolean get() = false
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
        Kind.LIMIT -> tr("Сегодняшний лимит облака из настроек исчерпан. До завтра отвечаю без модели. $OFFLINE", "The daily cloud limit you set is used up. Until tomorrow I answer without the model. $OFFLINE")
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
