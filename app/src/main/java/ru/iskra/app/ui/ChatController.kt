package ru.iskra.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.core.agent.AgentReply
import ru.iskra.core.agent.Turn
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.MoodEvent
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
    private var current: Job? = null

    init { reload() }

    fun reload() {
        scope.launch {
            val turns = withContext(Dispatchers.IO) { app.conversation.recent(60) }
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
        append(UiMessage(ids.getAndIncrement(), true, t))
        _busy.value = true
        CompanionBus.emit(AvatarCue.Thinking(true))
        current = scope.launch {
            val reply: AgentReply = try {
                withContext(Dispatchers.IO) { app.agent.handle(t) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentReply("Что-то сломалось: ${e.message ?: e.javaClass.simpleName}", Emotion.SAD, error = e.toString())
            } finally {
                _busy.value = false
                CompanionBus.emit(AvatarCue.Thinking(false))
            }
            deliver(reply)
        }
    }

    fun cancel() {
        current?.cancel()
        _busy.value = false
        CompanionBus.emit(AvatarCue.Thinking(false))
        app.speaker.stop()
    }

    /** Touch interactions with the overlay character. */
    fun react(event: MoodEvent) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { app.agent.react(event) }
            CompanionBus.emit(AvatarCue.Feel(r.emotion))
            if (r.text.isNotBlank()) CompanionBus.emit(AvatarCue.Say(r.text))
        }
    }

    private fun deliver(reply: AgentReply) {
        if (reply.text.isBlank() && reply.actions.isEmpty()) return
        append(UiMessage(
            id = ids.getAndIncrement(), fromUser = false, text = reply.text, emotion = reply.emotion,
            actions = reply.actions.map { (if (it.outcome.ok) "✓ " else "✗ ") + it.description },
            isError = reply.error != null,
        ))
        CompanionBus.emit(AvatarCue.Feel(reply.emotion))
        CompanionBus.emit(AvatarCue.Say(reply.text))
        val spoken = app.settings.load().ttsEnabled && app.speaker.speak(reply.text)
        CompanionBus.emit(AvatarCue.Talk(if (spoken) reply.text else reply.text.take(80)))
    }

    private fun append(m: UiMessage) {
        _messages.value = (_messages.value + m).takeLast(200)
    }

    fun clearHistory() {
        scope.launch {
            withContext(Dispatchers.IO) { app.conversation.clear() }
            _messages.value = emptyList()
        }
    }
}
