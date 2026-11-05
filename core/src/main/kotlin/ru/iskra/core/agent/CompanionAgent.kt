package ru.iskra.core.agent

import ru.iskra.core.i18n.tr
import ru.iskra.core.brain.CalculateTool
import ru.iskra.core.brain.DateInfoTool
import ru.iskra.core.brain.ForgetTool
import ru.iskra.core.brain.MemoryTalk
import ru.iskra.core.brain.SearchMemoryTool
import ru.iskra.core.brain.NumberWords
import ru.iskra.core.brain.OfflineBrain
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
    /** Upcoming reminders for the prompt ("сегодня в 18:30 — позвонить маме"). */
    private val upcoming: () -> List<String> = { emptyList() },
    private val historyTurns: Int = 16,
    private val maxToolRounds: Int = 4,
    private val summarizeAfterTurns: Int = 60,
) {
    private val moodEngine = MoodEngine(persona.traits)
    private val mutex = Mutex()
    private val pocketSpark = PocketSpark(random)
    private val brain = OfflineBrain(random)

    /**
     * After a key or balance error the cloud is skipped for [cloudPauseMs]: replies come at once from
     * the offline mode instead of waiting for the same error again. [resumeCloud] (new key saved) ends it.
     */
    @Volatile private var cloudPause: Pair<Long, LlmException.Kind>? = null
    private val cloudPauseMs = 5 * 60_000L

    @Volatile private var summaryRetryAtMs = 0L

    fun resumeCloud() { cloudPause = null; summaryRetryAtMs = 0L }

    init {
        // Memory and exact-answer tools every agent has; network tools (Wikipedia, rates, weather) come from the app.
        listOf(RememberTool(memory, clock), SearchMemoryTool(memory), ForgetTool(memory), CalculateTool(), DateInfoTool(clock))
            .forEach { if (tools[it.spec.name] == null) tools.register(it) }
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
            provider == null -> brain.fallback(text).let { AgentReply(it.text, it.emotion) }
            else -> askModel(provider, text, state, sink).let { r -> if (r.errorKind in PASSING) offlineInstead(text, r) else r }.also { r ->
                if (r.errorKind == LlmException.Kind.AUTH || r.errorKind == LlmException.Kind.BALANCE ||
                    r.errorKind == LlmException.Kind.POLICY || r.errorKind == LlmException.Kind.MODEL)
                    cloudPause = (clock() + cloudPauseMs) to r.errorKind
                // Free messages: OpenRouter resets the counter at midnight UTC.
                if (r.errorKind == LlmException.Kind.FREE_QUOTA) cloudPause = nextUtcMidnight() to LlmException.Kind.FREE_QUOTA
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

    /**
     * A passing cloud failure (no internet, a busy server): small talk still gets a real answer. Key, balance
     * and quota errors are not hidden — the owner needs to know; the following messages go offline anyway.
     */
    private fun offlineInstead(text: String, failed: AgentReply): AgentReply {
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), java.time.ZoneId.systemDefault())
        val chat = brain.chat(text, now, currentState().mood.dominantEmotion(), MemoryTalk.ownerName(memory.all())) ?: return failed
        return AgentReply(chat.text, chat.emotion, failed.actions, errorKind = failed.errorKind)
    }

    private val PASSING = setOf(LlmException.Kind.NETWORK, LlmException.Kind.RATE_LIMIT, LlmException.Kind.SERVER, LlmException.Kind.BAD_RESPONSE)

    /** Provider ids whose model refused tools: their requests go without them from now on. */
    private val noTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun nextMidnight(): Long = java.time.LocalDate.now().plusDays(1)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun nextUtcMidnight(): Long = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun pausedLine(kind: LlmException.Kind, minutes: Long): String {
        if (kind == LlmException.Kind.FREE_QUOTA) return tr("Бесплатные сообщения на сегодня закончились — до завтра я без модели. " +
            "Команды работают: будильник, таймер, заметки, напоминания, погода.",
            "Today's free messages are used up — until tomorrow I'm without the model. Commands work: alarm, timer, notes, reminders, weather.")
        if (kind == LlmException.Kind.POLICY || kind == LlmException.Kind.MODEL) return LlmException("", kind).userMessage()
        if (kind == LlmException.Kind.LIMIT) return tr("Сегодняшний лимит облака исчерпан — до завтра я без модели. " +
            "Команды работают: будильник, таймер, заметки, напоминания, погода. Лимит меняется в «Настройки → Экономия».", "Today's cloud limit is used up — until tomorrow I'm without the model. Commands work: alarm, timer, notes, reminders, weather. The limit is in “Settings → Economy”.")
        val why = if (kind == LlmException.Kind.BALANCE) tr("на счёте сервиса закончились деньги", "the service account is out of money") else tr("ключ API не подошёл", "the API key didn't work")
        return tr("Облако пока недоступно: $why. Без него я умею команды — будильник, таймер, фонарик, приложения, " +
            "заметки, напоминания. Снова попробую через $minutes мин или сразу после нового ключа в настройках.", "The cloud is unavailable for now: $why. Without it I can do commands — alarm, timer, flashlight, apps, notes, reminders. I'll try again in $minutes min or right after a new key in Settings.")
    }

    /**
     * Everything that needs no model: safety, memory commands, phone commands, exact answers (sums, units, dates),
     * and — only when there is no model — small talk. null: ask the model.
     */
    private suspend fun handleLocally(text: String, llmAvailable: Boolean): AgentReply? {
        brain.crisis(text)?.let { return AgentReply(it.text, it.emotion) }
        if (PocketSpark.matches(text)) return AgentReply(pocketSpark.next(), Emotion.CURIOUS)
        MemoryTalk.forgetQuery(text)?.let { return forget(it) }
        MemoryTalk.fact(text)?.let { f ->
            // Kept even if the model is weak at tools; with a model the reply is the model's (it now sees the fact).
            val r = memory.add(f.text, MemoryKind.FACT, MemorySource.USER_EXPLICIT, f.importance, clock())
            if (!llmAvailable) return AgentReply(if (r is MemoryManager.AddResult.Rejected) tr("Не стану это запоминать: ${r.reason}.", "I won't remember that: ${r.reason}.") else f.reply, Emotion.HAPPY)
        }
        if (!llmAvailable && MemoryTalk.asksName(text)) {
            val name = MemoryTalk.ownerName(memory.all())
            return if (name != null) AgentReply(tr("Тебя зовут $name. Мрр, как такое забыть!", "Your name is $name. Mrr, how could I forget!"), Emotion.HAPPY)
            else AgentReply(tr("Ты мне ещё не говорил. Скажи «меня зовут …» — запомню.", "You haven't told me yet. Say “my name is …” and I'll remember."), Emotion.CURIOUS)
        }
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), java.time.ZoneId.systemDefault())
        // Speech recognisers write "двадцать пять": try the words as digits if the plain text isn't a command.
        val parsed = CommandParser.parse(text, now) ?: NumberWords.digitize(text).takeIf { it != text }?.let { CommandParser.parse(it, now) }
        if (parsed != null) return command(parsed, llmAvailable)
        brain.utility(withBirthday(text), now)?.let { return AgentReply(it.text, it.emotion) }
        if (!llmAvailable) {
            brain.chat(text, now, currentState().mood.dominantEmotion(), MemoryTalk.ownerName(memory.all()))
                ?.let { return AgentReply(it.text, it.emotion) }
        }
        return null
    }

    /** "сколько дней до моего дня рождения" → the date memory holds. */
    private fun withBirthday(text: String): String {
        val mine = Regex("""(?iu)моего\s+дня\s+рождения|мой\s+день\s+рождения|my\s+birthday""")
        if (!mine.containsMatchIn(text)) return text
        val re = Regex("""(?iu)(?:день\s+рождения\s+владельца|the\s+owner's\s+birthday):?\s+(.+)""")
        val date = memory.all().firstNotNullOfOrNull { re.find(it.text)?.groupValues?.get(1) }
            ?.replace(Regex("""\s+\d{4}(?:\s+(?:года|г\.?))?\s*$"""), "")?.trim() ?: return text
        return mine.replace(text, date)
    }

    private fun forget(query: String): AgentReply {
        if (query.isEmpty()) return AgentReply(tr("Всю память голосом стирать не буду — это слишком важно. На вкладке «Память» можно удалить любую запись или стереть всё.",
            "I won't erase all memory by voice — it's too important. In the “Memory” tab you can delete any entry or erase everything."), Emotion.POUTY)
        val found = memory.match(query)
        val best = found.firstOrNull()?.takeIf { it.second >= 0.5 }
            ?: return AgentReply(tr("Не нашла в памяти ничего про «$query».", "I found nothing about “$query” in my memory."), Emotion.CURIOUS)
        val ties = found.filter { it.second == best.second }
        if (ties.size > 1 && best.second < 1.0) return AgentReply(tr("Нашла несколько похожих записей:\n", "I found several similar entries:\n") +
            ties.take(3).joinToString("\n") { "• ${it.first.text}" } + tr("\nСкажи точнее, какую забыть.", "\nTell me which one to forget."), Emotion.THINKING)
        memory.delete(best.first.id)
        return AgentReply(tr("Забыла: «${best.first.text}».", "Forgotten: “${best.first.text}”."), Emotion.NEUTRAL)
    }

    private suspend fun command(parsed: CommandParser.Parsed, llmAvailable: Boolean): AgentReply? {
        return when (parsed) {
            is CommandParser.Parsed.Remember -> {
                when (val r = memory.add(parsed.text, MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, clock())) {
                    is MemoryManager.AddResult.Added -> AgentReply(Lines.remembered(random, r.item.text), Emotion.HAPPY)
                    is MemoryManager.AddResult.Duplicate -> AgentReply(tr("Я это уже помню: «${r.existing.text}».", "I already remember that: “${r.existing.text}”."), Emotion.CURIOUS)
                    is MemoryManager.AddResult.Rejected -> AgentReply(tr("Не стану это запоминать: ${r.reason}.", "I won't remember that: ${r.reason}."), Emotion.POUTY)
                }
            }
            CommandParser.Parsed.ListMemory -> {
                val items = memory.all().sortedByDescending { it.importance }.take(15)
                if (items.isEmpty()) AgentReply(tr("Пока ничего не помню. Скажи «запомни: …».", "I don't remember anything yet. Say “remember: …”."), Emotion.NEUTRAL)
                else AgentReply(tr("Вот что я помню:\n", "Here's what I remember:\n") + items.joinToString("\n") { "• ${it.text}" }, Emotion.HAPPY)
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
        val system = composer.stable(persona.localized(), state.style, toolsAvailable = tools.specs().isNotEmpty())
        val situation = composer.situation(state.mood, memory.context(text, now), now, deviceContext(),
            runCatching { upcoming() }.getOrDefault(emptyList()))
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
                val request = LlmRequest(system = system, messages = messages,
                    tools = if (offerTools && provider.id !in noTools) tools.specs() else emptyList(), situation = situation)
                suspend fun send(r: LlmRequest) = if (sink == null) provider.complete(r) else {
                    val stream = ReplyStream(sink, state.mood.dominantEmotion())
                    provider.stream(r) { stream.feed(it) }.also { stream.finish() }
                }
                val response = try {
                    send(request)
                } catch (e: LlmException) {
                    // The model can't call tools: the same request without them still gets an answer.
                    if (e.kind != LlmException.Kind.NO_TOOLS || request.tools.isEmpty()) throw e
                    noTools += provider.id
                    send(request.copy(tools = emptyList()))
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
                        content = (if (record.outcome.ok) "OK: " else tr("ОШИБКА: ", "ERROR: ")) + record.outcome.message,
                        isError = !record.outcome.ok,
                    )
                }
            }
            return AgentReply(tr("Я запуталась в действиях и остановилась.", "I got tangled in actions and stopped."), Emotion.SAD, actions, usedLlm = true)
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
        val transcript = old.joinToString("\n") { (if (it.role == Turn.Role.USER) tr("Владелец: ", "Owner: ") else "${persona.name}: ") + it.text.take(500) }
        try {
            val r = provider.complete(LlmRequest(
                system = tr("Сожми диалог в 1–3 предложения от третьего лица: только устойчиво полезное о владельце " +
                    "(планы, события, договорённости, предпочтения). Без паролей и чувствительных данных. " +
                    "Если ничего полезного нет — ответь ровно словом НЕТ.", "Compress the dialogue into 1–3 sentences in the third person: only lastingly useful facts about the owner (plans, events, agreements, preferences). No passwords or sensitive data. If there is nothing useful, answer with exactly the word NONE."),
                messages = listOf(ChatMessage.User(transcript)),
                maxTokens = 200, temperature = 0.2,
            ))
            currentCoroutineContext().ensureActive()
            val summary = r.text.trim()
            val saved = when {
                summary.equals("НЕТ", ignoreCase = true) || summary.equals("NONE", ignoreCase = true) -> true
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
    override val spec get() = ToolSchemas.remember
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("запомнить: «${args.str("text").orEmpty()}»", "remember: “${args.str("text").orEmpty()}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text") ?: return ToolOutcome(false, tr("нет текста", "no text"))
        val kind = if (args.str("kind") == "preference") MemoryKind.PREFERENCE else MemoryKind.FACT
        return when (val r = memory.add(text, kind, MemorySource.MODEL_PROPOSED, args.int("importance") ?: 3, clock())) {
            is MemoryManager.AddResult.Added -> ToolOutcome(true, tr("запомнила «${r.item.text}»", "remembered “${r.item.text}”"))
            is MemoryManager.AddResult.Duplicate -> ToolOutcome(true, tr("уже помню это", "I already remember this"))
            is MemoryManager.AddResult.Rejected -> ToolOutcome(false, r.reason)
        }
    }
}
