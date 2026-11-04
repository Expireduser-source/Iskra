package ru.iskra.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.MoodEngine
import ru.iskra.core.persona.MoodEvent
import ru.iskra.core.persona.PersonaSpec
import ru.iskra.core.persona.Sentiment
import ru.iskra.core.persona.StyleFeedback
import ru.iskra.core.skills.PocketSpark
import ru.iskra.core.tools.ActionRecord
import ru.iskra.core.tools.CommandParser
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolNames
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.tools.ToolSchemas
import ru.iskra.core.tools.int
import ru.iskra.core.tools.str
import ru.iskra.core.voice.ReplySink
import ru.iskra.core.voice.ReplyStream
import kotlin.random.Random

data class AgentReply(
    val text: String,
    val emotion: Emotion,
    val actions: List<ActionRecord> = emptyList(),
    val usedLlm: Boolean = false,
    val error: String? = null,
    val errorKind: LlmException.Kind? = null,
)

/**
 * The companion's decision loop:
 *   1. appraise the message (mood, style feedback)
 *   2. try deterministic local commands (instant, offline, free)
 *   3. otherwise ask the LLM with persona + memory + tools, executing tool calls through [SafetyPolicy]
 *   4. update mood/history, occasionally compress old history into episodic memory.
 */
class CompanionAgent(
    private val persona: PersonaSpec,
    private val memory: MemoryManager,
    private val conversation: ConversationStore,
    private val stateStore: CompanionStateStore,
    private val tools: ToolRegistry,
    private val policy: SafetyPolicy,
    /** Returns the currently configured provider, or null for offline mode. */
    private val providerFactory: () -> LlmProvider?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val composer: PromptComposer = PromptComposer(),
    private val deviceContext: () -> String = { "" },
    private val historyTurns: Int = 16,
    private val maxToolRounds: Int = 4,
    private val summarizeAfterTurns: Int = 60,
) {
    private val moodEngine = MoodEngine(persona.traits)
    private val mutex = Mutex()
    private val pocketSpark = PocketSpark(random)

    /**
     * After a key or balance error the cloud is skipped for [cloudPauseMs]: replies come at once from
     * the offline mode instead of waiting for the same error again. [resumeCloud] (new key saved) ends it.
     */
    @Volatile private var cloudPause: Pair<Long, LlmException.Kind>? = null
    private val cloudPauseMs = 5 * 60_000L

    @Volatile private var summaryRetryAtMs = 0L

    fun resumeCloud() { cloudPause = null; summaryRetryAtMs = 0L }

    init {
        if (tools[ToolNames.REMEMBER] == null) tools.register(RememberTool(memory, clock))
    }

    fun currentState(): CompanionState {
        val s = stateStore.load()
        return s.copy(mood = moodEngine.decay(s.mood, clock()))
    }

    /** Non-verbal interaction (touch on the avatar). Returns a short reaction line. */
    suspend fun react(event: MoodEvent): AgentReply = mutex.withLock {
        val now = clock()
        val s = stateStore.load()
        val mood = moodEngine.apply(s.mood, event, now)
        stateStore.save(s.copy(mood = mood))
        val line = when (event) {
            MoodEvent.Petted -> Lines.petted(random)
            MoodEvent.Dragged -> Lines.dragged(random)
            MoodEvent.Flung -> Lines.flung(random)
            else -> ""
        }
        val emotion = when (event) {
            MoodEvent.Petted -> Emotion.SHY
            MoodEvent.Flung -> Emotion.POUTY
            MoodEvent.Dragged -> Emotion.SURPRISED
            else -> mood.dominantEmotion()
        }
        AgentReply(line, emotion)
    }

    /**
     * [sink]: receives the model's reply while it is generated (emotion, then speakable chunks), so
     * the voice can start before the answer is complete. Local commands and errors do not use it.
     */
    suspend fun handle(userText: String, sink: ReplySink? = null): AgentReply = mutex.withLock {
        val text = userText.trim()
        if (text.isEmpty()) return@withLock AgentReply("", currentState().mood.dominantEmotion())
        require(text.length <= 4000) { "message too long" }

        val now = clock()
        var state = stateStore.load()
        if (state.lastUserMessageAtMs > 0) {
            val hours = (now - state.lastUserMessageAtMs) / 3_600_000.0
            if (hours >= 6) state = state.copy(mood = moodEngine.apply(state.mood, MoodEvent.Absence(hours), now))
        }
        state = state.copy(
            mood = moodEngine.apply(state.mood, MoodEvent.UserMessage(Sentiment.estimate(text)), now),
            lastUserMessageAtMs = now,
            totalUserMessages = state.totalUserMessages + 1,
        )
        StyleFeedback.detect(text, state.style)?.let { state = state.copy(style = it) }
        stateStore.save(state)

        val pause = cloudPause?.takeIf { now < it.first }
        val provider = if (pause != null) null else providerFactory()
        val local = handleLocally(text, provider != null)
        val reply = local ?: when {
            pause != null -> AgentReply(pausedLine(pause.second, (pause.first - now) / 60_000 + 1), Emotion.SAD)
            provider == null -> AgentReply(Lines.offline(random), Emotion.NEUTRAL)
            else -> askModel(provider, text, state, sink).also { r ->
                if (r.errorKind == LlmException.Kind.AUTH || r.errorKind == LlmException.Kind.BALANCE)
                    cloudPause = (clock() + cloudPauseMs) to r.errorKind
                // The owner's daily limit: no cloud until midnight (the meter refuses anyway, this just skips it).
                if (r.errorKind == LlmException.Kind.LIMIT) cloudPause = nextMidnight() to LlmException.Kind.LIMIT
            }
        }

        // Persist outcome.
        var after = stateStore.load()
        val successes = reply.actions.count { it.outcome.ok }
        val failures = reply.actions.size - successes
        repeat(successes) { after = after.copy(mood = moodEngine.apply(after.mood, MoodEvent.TaskSucceeded, clock())) }
        repeat(failures) { after = after.copy(mood = moodEngine.apply(after.mood, MoodEvent.TaskFailed, clock())) }
        if (reply.usedLlm) after = after.copy(mood = moodEngine.apply(after.mood, MoodEvent.Expressed(reply.emotion), clock()))
        stateStore.save(after)

        conversation.append(Turn(Turn.Role.USER, text, now))
        if (reply.text.isNotBlank()) conversation.append(Turn(Turn.Role.ASSISTANT, reply.text, clock(), reply.emotion))
        // Local commands stay free; an unsuccessful cloud reply must not trigger another request.
        if (provider != null && reply.usedLlm && reply.error == null) maybeSummarize(provider)
        reply
    }

    private fun nextMidnight(): Long = java.time.LocalDate.now().plusDays(1)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pausedLine(kind: LlmException.Kind, minutes: Long): String {
        if (kind == LlmException.Kind.LIMIT) return "Сегодняшний лимит облака исчерпан — до завтра я без модели. " +
            "Команды работают: будильник, таймер, заметки, напоминания, погода. Лимит меняется в «Настройки → Экономия»."
        val why = if (kind == LlmException.Kind.BALANCE) "на счёте сервиса закончились деньги" else "ключ API не подошёл"
        return "Облако пока недоступно: $why. Без него я умею команды — будильник, таймер, фонарик, приложения, " +
            "заметки, напоминания. Снова попробую через $minutes мин или сразу после нового ключа в настройках."
    }

    private suspend fun handleLocally(text: String, llmAvailable: Boolean): AgentReply? {
        if (PocketSpark.matches(text)) return AgentReply(pocketSpark.next(), Emotion.CURIOUS)
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), java.time.ZoneId.systemDefault())
        return when (val parsed = CommandParser.parse(text, now) ?: return null) {
            is CommandParser.Parsed.Remember -> {
                when (val r = memory.add(parsed.text, MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, clock())) {
                    is MemoryManager.AddResult.Added -> AgentReply(Lines.remembered(random, r.item.text), Emotion.HAPPY)
                    is MemoryManager.AddResult.Duplicate -> AgentReply("Я это уже помню: «${r.existing.text}».", Emotion.CURIOUS)
                    is MemoryManager.AddResult.Rejected -> AgentReply("Не стану это запоминать: ${r.reason}.", Emotion.POUTY)
                }
            }
            CommandParser.Parsed.ListMemory -> {
                val items = memory.all().sortedByDescending { it.importance }.take(15)
                if (items.isEmpty()) AgentReply("Пока ничего не помню. Скажи «запомни: …».", Emotion.NEUTRAL)
                else AgentReply("Вот что я помню:\n" + items.joinToString("\n") { "• ${it.text}" }, Emotion.HAPPY)
            }
            is CommandParser.Parsed.Tool -> {
                val record = policy.run(tools[parsed.name], parsed.name, parsed.args)
                // Fuzzy "открой X" that didn't match an app: let the model figure out what was meant.
                if (!record.outcome.ok && parsed.name == ToolNames.OPEN_APP && llmAvailable) return null
                val informational = tools[parsed.name]?.informational == true
                if (record.outcome.ok && informational) AgentReply(record.outcome.message, Emotion.HAPPY, listOf(record))
                else if (record.outcome.ok) AgentReply(Lines.actionDone(random, record.outcome.message), Emotion.HAPPY, listOf(record))
                else AgentReply(Lines.actionFailed(random, record.outcome.message), Emotion.SAD, listOf(record))
            }
        }
    }

    private suspend fun askModel(provider: LlmProvider, text: String, state: CompanionState, sink: ReplySink?): AgentReply {
        val now = clock()
        val memories = memory.relevant(text, limit = 8, nowMs = now)
        val system = composer.system(
            persona, state.mood, state.style, memories, now,
            toolsAvailable = tools.specs().isNotEmpty(), deviceContext = deviceContext(),
        )
        val messages = ArrayList<ChatMessage>()
        for (t in conversation.recent(historyTurns)) messages += when (t.role) {
            Turn.Role.USER -> ChatMessage.User(t.text)
            Turn.Role.ASSISTANT -> ChatMessage.Assistant(t.text)
        }
        // Providers require the dialogue to start with a user turn.
        while (messages.isNotEmpty() && messages.first() !is ChatMessage.User) messages.removeAt(0)
        messages += ChatMessage.User(text)

        val actions = mutableListOf<ActionRecord>()
        // Text the model already said in tool rounds ("Ставлю таймер…"): heard, so also shown.
        val said = StringBuilder()
        var emotion: Emotion? = null
        try {
            repeat(maxToolRounds) { round ->
                val offerTools = round < maxToolRounds - 1
                val request = LlmRequest(system = system, messages = messages, tools = if (offerTools) tools.specs() else emptyList())
                val response = if (sink == null) provider.complete(request) else {
                    val stream = ReplyStream(sink, state.mood.dominantEmotion())
                    provider.stream(request) { stream.feed(it) }.also { stream.finish() }
                }
                val parsed = ReplyParser.parse(response.text)
                if (parsed.emotion != null) emotion = parsed.emotion
                if (response.toolCalls.isEmpty() || !offerTools) {
                    val fallback = if (actions.isNotEmpty()) actions.joinToString("; ") { it.outcome.message } else "…"
                    val full = listOf(said.toString().trim(), parsed.text).filter { it.isNotBlank() }.joinToString(" ").trim()
                    return AgentReply(
                        text = full.ifBlank { fallback },
                        emotion = emotion ?: state.mood.dominantEmotion(),
                        actions = actions,
                        usedLlm = true,
                    )
                }
                if (parsed.text.isNotBlank()) said.append(parsed.text).append(' ')
                messages += ChatMessage.Assistant(response.text, response.toolCalls)
                for (call in response.toolCalls) {
                    val record = policy.run(tools[call.name], call.name, call.arguments)
                    actions += record
                    messages += ChatMessage.ToolResult(
                        callId = call.id, toolName = call.name,
                        content = (if (record.outcome.ok) "OK: " else "ОШИБКА: ") + record.outcome.message,
                        isError = !record.outcome.ok,
                    )
                }
            }
            return AgentReply("Я запуталась в действиях и остановилась.", Emotion.SAD, actions, usedLlm = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return AgentReply(e.userMessage(), Emotion.SAD, actions, error = e.message, errorKind = e.kind)
        }
    }

    /** Compresses the oldest part of the dialogue into an EPISODE memory so context stays small. */
    private suspend fun maybeSummarize(provider: LlmProvider) {
        if (conversation.count() < summarizeAfterTurns || clock() < summaryRetryAtMs) return
        val old = conversation.oldest(summarizeAfterTurns / 2)
        val transcript = old.joinToString("\n") { (if (it.role == Turn.Role.USER) "Владелец: " else "${persona.name}: ") + it.text.take(500) }
        try {
            val r = provider.complete(LlmRequest(
                system = "Сожми диалог в 1–3 предложения от третьего лица: только устойчиво полезное о владельце " +
                    "(планы, события, договорённости, предпочтения). Без паролей и чувствительных данных. " +
                    "Если ничего полезного нет — ответь ровно словом НЕТ.",
                messages = listOf(ChatMessage.User(transcript)),
                maxTokens = 200, temperature = 0.2,
            ))
            currentCoroutineContext().ensureActive()
            val summary = r.text.trim()
            val saved = when {
                summary.equals("НЕТ", ignoreCase = true) -> true
                summary.isEmpty() -> false
                else -> memory.add(summary.take(400), MemoryKind.EPISODE, MemorySource.SUMMARY, 2, clock()) !is MemoryManager.AddResult.Rejected
            }
            if (saved) {
                conversation.popOldest(old.size)
                summaryRetryAtMs = 0L
            } else {
                summaryRetryAtMs = clock() + cloudPauseMs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep source turns until a summary is saved; do not retry on every message.
            summaryRetryAtMs = clock() + cloudPauseMs
            if (e is LlmException && (e.kind == LlmException.Kind.AUTH || e.kind == LlmException.Kind.BALANCE))
                cloudPause = summaryRetryAtMs to e.kind
        }
    }
}

/** Lets the model store long-term facts. SAFE but always surfaced in the UI as an action chip. */
class RememberTool(private val memory: MemoryManager, private val clock: () -> Long) : Tool {
    override val spec = ToolSchemas.remember
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "запомнить: «${args.str("text").orEmpty()}»"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text") ?: return ToolOutcome(false, "нет текста")
        val kind = if (args.str("kind") == "preference") MemoryKind.PREFERENCE else MemoryKind.FACT
        return when (val r = memory.add(text, kind, MemorySource.MODEL_PROPOSED, args.int("importance") ?: 3, clock())) {
            is MemoryManager.AddResult.Added -> ToolOutcome(true, "запомнила «${r.item.text}»")
            is MemoryManager.AddResult.Duplicate -> ToolOutcome(true, "уже помню это")
            is MemoryManager.AddResult.Rejected -> ToolOutcome(false, r.reason)
        }
    }
}
