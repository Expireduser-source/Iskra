package ru.iskra.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.core.agent.AgentReply
import ru.iskra.core.agent.InteractionGate
import ru.iskra.core.agent.Turn
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.MoodEvent
import ru.iskra.core.voice.ReplySink
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

data class UiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val emotion: Emotion? = null,
    val actions: List<String> = emptyList(),
    val isError: Boolean = false,
)

/** App-wide chat state shared by the main screen, the quick-chat popup and the overlay. */
class ChatController(private val app: IskraApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing
    private var current: Job? = null
    private val interactions = InteractionGate()
    private var activeStream: StreamingReply? = null

    init { reload() }

    fun reload() {
        val token = interactions.snapshot() ?: return
        scope.launch {
            val turns = withContext(Dispatchers.IO) { app.conversation.recent(60) }
            if (interactions.isCurrent(token))
                _messages.value = turns.map { UiMessage(ids.getAndIncrement(), it.role == Turn.Role.USER, it.text, it.emotion) }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        if (t.length > 4000) {
            append(UiMessage(ids.getAndIncrement(), false, "Слишком длинное сообщение — до 4000 символов.", isError = true))
            return
        }
        val token = interactions.beginTurn() ?: return
        append(UiMessage(ids.getAndIncrement(), true, t))
        app.lastContactMs = System.currentTimeMillis()
        _busy.value = true
        CompanionBus.emit(AvatarCue.Thinking(true))
        // "End of command": the moment the typed message was sent or the final voice result arrived.
        val commandAt = SystemClock.elapsedRealtime()
        val stream = StreamingReply(ids.getAndIncrement(), commandAt, app.settings.load().ttsEnabled, token)
        activeStream = stream
        current = scope.launch {
            val reply: AgentReply = try {
                (withContext(Dispatchers.IO) {
                    interactions.run(token) { app.agent.handle(t, stream) }
                } ?: return@launch).also { r ->
                    val ms = SystemClock.elapsedRealtime() - commandAt
                    // Sizes and timings only — never the text.
                    if (r.error == null) Diag.i("timing", (if (r.usedLlm) "LLM ответ" else "Офлайн-ответ") + ": ${r.text.length} симв., $ms мс")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.e("chat", "Сбой при обработке сообщения", e)
                AgentReply("Что-то сломалось: ${e.message ?: e.javaClass.simpleName}", Emotion.SAD, error = e.toString())
            } finally {
                if (interactions.isCurrent(token)) {
                    _busy.value = false
                    CompanionBus.emit(AvatarCue.Thinking(false))
                }
            }
            if (interactions.isCurrent(token)) deliver(reply, commandAt, stream)
        }
    }

    /**
     * The reply while the model is still writing it: the first sentence is shown and spoken at once,
     * the rest follows. Callbacks come from a network thread and are moved to the main thread in order.
     */
    private inner class StreamingReply(val messageId: Long, val commandAt: Long, val voice: Boolean, val token: Long) : ReplySink {
        var started = false
        var voiceStopped = false
        private val shown = StringBuilder()

        override fun emotion(e: Emotion) {
            scope.launch {
                if (interactions.isCurrent(token)) CompanionBus.emit(AvatarCue.Feel(e))
            }
        }

        override fun sentence(text: String, emotion: Emotion) {
            scope.launch {
                if (!interactions.isCurrent(token)) return@launch
                if (!started) {
                    started = true
                    Diag.i("timing", "LLM: первая фраза через ${SystemClock.elapsedRealtime() - commandAt} мс")
                    CompanionBus.emit(AvatarCue.Thinking(false))
                    if (voice && !voiceStopped) app.speaker.beginStream(commandAt, emotion)
                }
                if (voice && !voiceStopped) app.speaker.enqueue(text, emotion)
                CompanionBus.emit(AvatarCue.Say(text)) // bubble shows what she is saying now, like subtitles
                shown.append(text).append(' ')
                upsert(UiMessage(messageId, false, shown.toString().trim(), emotion))
            }
        }
    }

    fun cancel() {
        if (interactions.snapshot() == null) return // erasure owns the busy state
        interactions.invalidate()
        current?.cancel()
        activeStream = null
        _busy.value = false
        CompanionBus.emit(AvatarCue.Thinking(false))
        app.speaker.stop()
    }

    /** Touch interactions with the overlay character. */
    fun react(event: MoodEvent) {
        val token = interactions.snapshot() ?: return
        app.lastContactMs = System.currentTimeMillis()
        scope.launch {
            val r = withContext(Dispatchers.IO) { interactions.run(token) { app.agent.react(event) } } ?: return@launch
            if (!interactions.isCurrent(token)) return@launch
            CompanionBus.emit(AvatarCue.Feel(r.emotion))
            if (r.text.isNotBlank()) CompanionBus.emit(AvatarCue.Say(r.text))
        }
    }

    private fun deliver(reply: AgentReply, commandAt: Long, stream: StreamingReply) {
        // The error is technical (HTTP code + service message); the reply text itself is not logged.
        reply.error?.let { Diag.e("llm", it.take(160)) }
        reply.actions.filter { !it.outcome.ok }.forEach { Diag.w("tool", "Действие ${it.tool} не выполнено") /* description may hold a number or query */ }
        if (reply.text.isBlank() && reply.actions.isEmpty()) { if (stream.started) app.speaker.endStream(); return }
        upsert(UiMessage(
            id = if (stream.started) stream.messageId else ids.getAndIncrement(), fromUser = false, text = reply.text, emotion = reply.emotion,
            actions = reply.actions.map { (if (it.outcome.ok) "✓ " else "✗ ") + it.description },
            isError = reply.error != null,
        ))
        CompanionBus.emit(AvatarCue.Feel(reply.emotion))
        if (!stream.started || reply.error != null) CompanionBus.emit(AvatarCue.Say(reply.text))
        val voice = app.settings.load().ttsEnabled && !stream.voiceStopped
        // The speaker emits Talk/StopTalking itself; without a voice the mouth moves briefly.
        val spoken = when {
            !voice -> false
            stream.started && reply.error == null -> { app.speaker.endStream(); true }
            else -> app.speaker.speak(reply.text, reply.emotion, commandAtMs = commandAt) // replaces a cut-off stream
        }
        if (!spoken && !stream.voiceStopped) CompanionBus.emit(AvatarCue.Talk(reply.text.take(80)))
        _replies.tryEmit(spoken)
        // Once a day at 80 % of the owner's cloud limit (and when it runs out): say it plainly.
        app.usage.takeWarning()?.let { w ->
            append(UiMessage(ids.getAndIncrement(), false, w))
            CompanionBus.emit(AvatarCue.Say(w))
            Diag.i("usage", "Предупреждение о лимите облака: ${(app.usage.fraction() * 100).toInt()} %")
        }
    }

    private val _replies = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Emits after each reply is delivered; the value is whether it is being spoken aloud. */
    val replies: SharedFlow<Boolean> = _replies

    fun stopSpeaking() {
        activeStream?.voiceStopped = true
        app.speaker.stop()
    }

    private fun append(m: UiMessage) {
        _messages.value = (_messages.value + m).takeLast(200)
    }

    /** Replaces the message with the same id (a reply growing while it streams) or appends it. */
    private fun upsert(m: UiMessage) {
        val list = _messages.value
        val i = list.indexOfFirst { it.id == m.id }
        _messages.value = if (i < 0) (list + m).takeLast(200) else list.toMutableList().also { it[i] = m }
    }

    /** Blocks new interactions and waits for active writes before clearing their data. */
    fun clearData(erase: suspend () -> Unit, onComplete: () -> Unit = {}) {
        if (!interactions.beginReset()) return
        _clearing.value = true
        val previous = current
        previous?.cancel()
        activeStream = null
        _busy.value = true
        CompanionBus.emit(AvatarCue.Thinking(false))
        app.speaker.stop()
        scope.launch {
            try {
                interactions.resetWhenIdle {
                    previous?.join()
                    erase()
                    _messages.value = emptyList()
                }
                onComplete()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.e("memory", "Не удалось очистить данные", e)
                append(UiMessage(ids.getAndIncrement(), false, "Не удалось стереть все данные. Попробуй ещё раз.", isError = true))
            } finally {
                _busy.value = false
                _clearing.value = false
            }
        }
    }

    fun clearHistory(onComplete: () -> Unit = {}) = clearData(erase = {
        withContext(Dispatchers.IO) { app.conversation.clear() }
    }, onComplete = onComplete)
}
