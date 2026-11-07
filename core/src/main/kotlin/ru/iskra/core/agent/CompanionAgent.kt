package ru.iskra.core.agent

import ru.iskra.core.i18n.tr
import ru.iskra.core.brain.CalculateTool
import ru.iskra.core.brain.CareTalk
import ru.iskra.core.brain.CrisisTalk
import ru.iskra.core.brain.DateInfoTool
import ru.iskra.core.brain.ForgetTool
import ru.iskra.core.brain.MemoryTalk
import ru.iskra.core.brain.SearchMemoryTool
import ru.iskra.core.brain.NumberWords
import ru.iskra.core.brain.OfflineBrain
import ru.iskra.core.brain.PlanTalk
import ru.iskra.core.brain.SelfGender
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
import ru.iskra.core.persona.Life
import ru.iskra.core.persona.ModelRule
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
    /** Part of a failed reply was already spoken: no other model may answer this message again. */
    val spoke: Boolean = false,
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
    /** The model on the phone, if the owner downloaded one: used when there is no cloud or it fails. */
    private val localFactory: () -> LlmProvider? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val composer: PromptComposer = PromptComposer(),
    private val deviceContext: () -> String = { "" },
    /** Upcoming reminders for the prompt ("сегодня в 18:30 — позвонить маме"). */
    private val upcoming: () -> List<String> = { emptyList() },
    private val historyTurns: Int = 16,
    private val maxToolRounds: Int = 4,
    private val summarizeAfterTurns: Int = 60,
    /**
     * The rules the owner turned on (Settings → «Свобода модели»), read on every message. None by default: the model
     * answers freely — nothing replaces its answers and no prohibitions go into its instructions.
     */
    private val rules: () -> Set<ModelRule> = { emptySet() },
    /** The owner's own instructions to the model (Settings → «Свобода модели» → «Свои указания»), read on every message. */
    private val ownWords: () -> String = { "" },
    /** Her own diary: a day of her life in her words, written at night ([writeDiary]). */
    private val diary: ru.iskra.core.memory.DiaryStore = ru.iskra.core.memory.InMemoryDiaryStore(),
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
        // The plan to ask about as it stands before this message (settled after the reply).
        val dueBefore = runCatching { PlanTalk.due(PlanTalk.known(memory.all()), localDate(now), localHour(now)) }.getOrNull()
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
        // She is born with the first meeting; on a phone that already knew her — on the earliest day kept of it.
        if (state.bornAtMs == 0L) state = state.copy(bornAtMs = firstMeeting(now))
        stateStore.save(state)

        val pause = cloudPause?.takeIf { now < it.first }
        val cloud = if (pause != null) null else providerFactory()
        val onPhone = if (cloud == null) localFactory() else null
        val provider = cloud ?: onPhone
        crisisAgain = null
        memoryBefore = runCatching { memory.all().map { it.id }.toSet() }.getOrNull()
        messageText = text
        val local = handleLocally(text, provider != null, provider?.onDevice == true)
        val answered = local ?: when {
            provider == null && pause != null -> AgentReply(pausedLine(pause.second, (pause.first - now) / 60_000 + 1), Emotion.SAD)
            provider == null -> brain.fallback(text).let { AgentReply(it.text, it.emotion) }
            // The model on the phone failed (memory ran out, a damaged file): an honest answer without a model; the reason goes to the journal.
            // «Ты человек?»: a model on the phone that says she is a person (Gemma 3 1B: «И да, я человек!») gives way to her
            // own answer. Such a reply is not streamed — words already shown or spoken could not be taken back.
            provider === onPhone -> brain.asksWhatSheIs(text).let { self ->
                askPhone(provider, text, state, if (self) null else sink).let { r ->
                    if (r.errorKind == null) {
                        if (self && brain.claimsHuman(r.text, text)) brain.digitalSelf(text).let { a -> r.copy(text = a.text, emotion = a.emotion) } else r
                    }
                    else (brain.chat(text, java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), java.time.ZoneId.systemDefault()),
                        currentState().mood.dominantEmotion(), MemoryTalk.ownerName(memory.all()))
                        ?: if (r.errorKind == LlmException.Kind.BAD_RESPONSE) brain.slipped() else brain.fallback(text))
                        .let { a -> AgentReply(a.text, a.emotion, r.actions, error = r.error) }
                }
            }
            else -> askModel(provider, text, state, sink).let { r -> if (r.errorKind != null) rescue(text, state, sink, r) else r }.also { r ->
                if (r.errorKind == LlmException.Kind.AUTH || r.errorKind == LlmException.Kind.BALANCE ||
                    r.errorKind == LlmException.Kind.POLICY || r.errorKind == LlmException.Kind.MODEL)
                    cloudPause = (clock() + cloudPauseMs) to r.errorKind
                // Free messages: OpenRouter resets the counter at midnight UTC.
                if (r.errorKind == LlmException.Kind.FREE_QUOTA) cloudPause = nextUtcMidnight() to LlmException.Kind.FREE_QUOTA
                // The owner's daily limit: no cloud until midnight (the meter refuses anyway, this just skips it).
                if (r.errorKind == LlmException.Kind.LIMIT) cloudPause = nextMidnight() to LlmException.Kind.LIMIT
            }
        }
        // The model on the phone has no tools: after its reply about a plan with a time she offers the reminder herself.
        val offerLine = madeOffer
        madeOffer = null
        val offered = if (offerLine != null && local == null && provider?.onDevice == true && answered.errorKind == null && answered.text.isNotBlank()) {
            sink?.sentence(offerLine, answered.emotion)
            answered.copy(text = answered.text.trimEnd() + " " + offerLine)
        } else {
            if (offerLine != null && local == null) offer = null // the cloud has its own tool for it; nothing was offered
            answered
        }
        // A cloud answer to words about not wanting to live carries the numbers; if the cloud failed, the full answer.
        val reply = crisisAgain?.let { again ->
            when {
                offered.errorKind != null && local == null -> AgentReply(CrisisTalk.reply(again), Emotion.SAD, offered.actions)
                CrisisTalk.hasNumbers(offered.text) -> offered
                else -> CrisisTalk.numbers().let { line ->
                    sink?.sentence(line, offered.emotion)
                    offered.copy(text = offered.text.trimEnd() + "\n\n" + line)
                }
            }
        } ?: offered

        reply.actions.lastOrNull { it.outcome.ok }?.let { lastDone = it.tool to clock() }
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
        runCatching { settlePlans(text, reply.text, dueBefore, now) }
        runCatching { learnGender(text, now) }
        // Local commands stay free; an unsuccessful cloud reply must not trigger another request.
        // Only a working cloud model summarizes: not one that just failed, not the slow model on the phone.
        if (provider != null && provider === cloud && reply.usedLlm && reply.error == null && reply.errorKind == null) maybeSummarize(provider)
        reply
    }

    /**
     * The model on the phone. A small model now and then answers with nothing but «…» (a matter of chance in sampling):
     * then once more, and if that is empty too, a BAD_RESPONSE for the caller to answer without it.
     */
    private suspend fun askPhone(provider: LlmProvider, text: String, state: CompanionState, sink: ReplySink?): AgentReply {
        fun fine(r: AgentReply) = r.errorKind != null || r.actions.isNotEmpty() || r.text.any { it.isLetterOrDigit() }
        // A timer asked for without a time the phrase parser reads: Gemma 4 begins with the call and chooses the minutes.
        val timer = ru.iskra.core.tools.ToolNames.SET_TIMER.takeIf { provider.nativeTools && ActionPromise.wantsTimer(text) }
        val first = askModel(provider, text, state, sink, forceTool = timer)
        // Answered in another language than asked: once more, told which (the first one stays if that doesn't help). If this
        // try was told already — the reply before was in the other language too, a translation say — the second one doesn't
        // see the turns in that language: Gemma 3 1B went on in English after «Переведи на английский…», nudge or not.
        val lastReply = conversation.recent(2).lastOrNull { it.role == Turn.Role.ASSISTANT }?.text
        val told = composer.languageNudge(text, lastReply).isNotEmpty()
        // Not after an action: the second try would do it again (two timers for one request).
        var reply = first
        if (fine(first) && first.errorKind == null && first.actions.isEmpty()) composer.wrongLanguage(text, first.text)?.let { nudge ->
            val again = askModel(provider, text, state, sink, nudge, hideOtherScript = told).let { it.copy(text = composer.dropNudgeAnswer(it.text)) }
            if (again.errorKind == null && again.text.any { it.isLetterOrDigit() } && composer.wrongLanguage(text, again.text) == null) reply = again
        }
        // A promise is checked in the reply that stays, the one in the right language too: E4B, asked again in Russian,
        // answered «Я поставила таймер на 4 минуты.» and called nothing (ARM evaluation, 0.15.0).
        if (fine(reply) && reply.errorKind == null && reply.actions.isEmpty() && (ActionPromise.made(reply.text) || ActionPromise.offered(reply.text, text))) {
            val first = reply
            // A question back («Я поставлю таймер. Сколько минут?») is not a broken promise: the answer comes next, and no
            // call is to be forced before it.
            if (first.text.trimEnd().endsWith("?")) return first
            // «Я поставлю таймер» with nothing called: Gemma 4 once more, told to call — and when the promise says which tool,
            // with that call begun for it, so it only fills in the arguments; still nothing — said honestly.
            val promised = ActionPromise.tool(first.text)?.takeIf { it in PHONE_TOOLS }
            // «Ты попросила засечь время. Я уже поставила таймер на 5 минут» — what she did a moment ago, told again: no
            // second timer (E4B set one on «О чём я тебя только что попросила?», ARM evaluation 0.15.0), unless it is asked for.
            val told = lastDone?.takeIf { (tool, at) -> tool == promised && clock() - at < RECENT_ACTION_MS }
            if (told != null && !ActionPromise.asks(text)) return first
            val again = if (provider.nativeTools) askModel(provider, text, state, sink, ActionPromise.nudge(), forceTool = promised) else null
            if (again != null && again.errorKind == null && again.actions.isNotEmpty()) return again
            val last = again?.takeIf { it.errorKind == null && it.text.any(Char::isLetterOrDigit) } ?: first
            // A question back («Сколько времени тебе нужно?») is not a broken promise: the answer comes next.
            if (last.text.trimEnd().endsWith("?")) return last
            sink?.sentence(ActionPromise.note(), last.emotion)
            return last.copy(text = last.text.trimEnd() + " " + ActionPromise.note())
        }
        if (reply !== first) return reply
        if (fine(first)) return first
        val second = askModel(provider, text, state, sink)
        return if (fine(second)) second else second.copy(errorKind = LlmException.Kind.BAD_RESPONSE)
    }

    /**
     * The cloud failed: the model on the phone answers instead if there is one (with a short note when the owner
     * should fix something), otherwise small talk for passing failures, otherwise the error itself.
     */
    private suspend fun rescue(text: String, state: CompanionState, sink: ReplySink?, failed: AgentReply): AgentReply {
        val onPhone = if (failed.actions.isEmpty() && !failed.spoke) localFactory() else null   // never redo actions or speech
        if (onPhone != null) {
            val r = askPhone(onPhone, text, state, sink)
            if (r.errorKind == null) {
                val note = when (failed.errorKind) {
                    LlmException.Kind.AUTH, LlmException.Kind.BALANCE, LlmException.Kind.POLICY, LlmException.Kind.MODEL,
                    LlmException.Kind.FREE_QUOTA, LlmException.Kind.LIMIT ->
                        tr("\n\n(Облако сейчас недоступно — ответила моделью на телефоне. ", "\n\n(The cloud is unavailable — I answered with the model on the phone. ") +
                            failed.text.substringBefore(". ") + ".)"
                    else -> ""
                }
                return r.copy(text = r.text + note, errorKind = failed.errorKind)
            }
        }
        return if (failed.errorKind in PASSING) offlineInstead(text, failed) else failed
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

    /**
     * Things worth knowing today: the owner's birthday, today's and tomorrow's plans (only in the first exchange of the
     * day, so they aren't repeated in every reply) and the plan to ask about.
     */
    /**
     * Most important first: how the owner is after a hard day, the owner's birthday, how a plan went, today's and
     * tomorrow's plans, close people's birthdays, the name. The model on the phone gets only the first one: in the ARM
     * evaluation Gemma 3 1B, given «вчера было грустно» and «сегодня экзамен» together, answered one and garbled the other.
     */
    private fun todayNotes(nowMs: Long): List<String> {
        val today = localDate(nowMs)
        val all = memory.all().filter { knownBefore(it) }
        val notes = ArrayList<String>()
        // «Как прошёл экзамен?» — «Сдала на пятёрку!»: whether it's good or bad news, before anything else.
        val asked = conversation.recent(1).lastOrNull()?.takeIf { it.role == Turn.Role.ASSISTANT }?.text
        PlanTalk.outcomeNote(PlanTalk.known(all), asked, messageText, tr(true, false), today)?.let { notes += it }
        // Words about not wanting to live in the last hours come before anything else, in every reply until then.
        val crisis = (ModelRule.CRISIS in rules())
        val careful = crisis && (crisisAgain != null || CrisisTalk.recent(conversation.recent(80), nowMs, CAREFUL_HOURS))
        if (careful) notes += CrisisTalk.note(tr(true, false))
        CareTalk.pending(conversation.recent(80), today, crisis = crisis)?.takeIf { !careful || it.kind != CareTalk.Kind.CRISIS }?.let { notes += CareTalk.note(it, tr(true, false)) }
        if (MemoryTalk.birthday(all) == java.time.MonthDay.from(today)) notes += tr("сегодня день рождения владельца — поздравь", "today is the owner's birthday — congratulate them")
        // Her own small holiday: a week, a month, a hundred days, a year since she was born.
        currentState().let { st -> Life.milestone(lived(st, nowMs))?.let { notes += Life.milestoneNote(it, bornAt(st, nowMs), nowMs) } }
        val fresh = conversation.recent(1).lastOrNull()?.let { localDate(it.atMs) != today } ?: true
        // PlanTalk lists today's and tomorrow's plans first and the one to ask about last; asking how it went comes first here.
        val plans = PlanTalk.notes(PlanTalk.known(all), today, localHour(nowMs), fresh, tr(true, false))
        notes += plans.filter { it.contains("спроси, как прошло") || it.contains("ask how it went") } + plans.filterNot { it.contains("спроси, как прошло") || it.contains("ask how it went") }
        if (fresh) for ((who, md) in MemoryTalk.theirBirthdays(all)) {
            val day = when (md) { java.time.MonthDay.from(today) -> 0; java.time.MonthDay.from(today.plusDays(1)) -> 1; else -> continue }
            val whose = if (who.first().isUpperCase()) "у $who" else "у $who владельца"
            notes += if (who.any { it in 'а'..'я' }) "${if (day == 0) "сегодня" else "завтра"} день рождения $whose — напомни поздравить"
                else "${if (day == 0) "today" else "tomorrow"} is the owner's $who's birthday — remind them to congratulate"
        }
        if (shouldAskName()) notes += tr("ты ещё не знаешь, как зовут собеседника — если к месту, спроси (один раз)", "you don't know the person's name yet — if it fits, ask (once)")
        // What she wrote in her diary about the last day they talked, on the first message of a new day — last of all: before
        // the plans it displaced «Как прошёл экзамен?» for the phone model, which reads only the first note (ARM evaluation).
        if (fresh) diaryNote(today)?.let { notes += it }
        return notes
    }

    /**
     * She doesn't know the owner's name after a few messages and hasn't asked yet (in her words or the model's): ask once.
     * A refusal stays in the chat, so she doesn't ask again.
     */
    private fun shouldAskName(): Boolean = stateStore.load().totalUserMessages >= 3 && MemoryTalk.ownerName(memory.all()) == null &&
        conversation.recent(400).none { it.role == Turn.Role.ASSISTANT && MemoryTalk.askedName.containsMatchIn(it.text) }

    /** «Что у меня завтра?» → «Мрр, смотрю: завтра в 09:00 — позвонить маме; завтра — экзамен по физике.» */
    private fun agenda(text: String, today: java.time.LocalDate): String? {
        val (from, to) = MemoryTalk.agendaDays(text, today) ?: return null
        val ru = tr(true, false)
        val plans = PlanTalk.known(memory.all()).filter { !it.done && !it.plan.date.isBefore(from) && !it.plan.date.isAfter(to) }
            .map { "${PlanTalk.whenText(it.plan.date, today, it.plan.ru)} — ${it.plan.what}" }
        // Reminders say their day as «сегодня в 18:30», «через 10 мин (в 14:35)», «завтра в 09:00» or «9.11 в 10:00».
        val heads = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.flatMap { d ->
            when (d) {
                today -> listOf("сегодня", "через", "today", "in ")
                today.plusDays(1) -> listOf("завтра", "tomorrow")
                else -> listOf("${d.dayOfMonth}.${"%02d".format(d.monthValue)} ", d.month.name.take(3).lowercase() + " ${d.dayOfMonth} ")
            }
        }.toList()
        val reminders = runCatching { upcoming() }.getOrDefault(emptyList()).filter { r -> heads.any { r.lowercase().startsWith(it) } }
        val birthdays = MemoryTalk.theirBirthdays(memory.all()).filter { (_, md) -> generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.any { java.time.MonthDay.from(it) == md } }
            .map { (who, md) -> if (who.any { it in 'а'..'я' }) "${md.dayOfMonth} ${listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")[md.monthValue - 1]} — день рождения у $who" else "${md.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${md.dayOfMonth} — your $who's birthday" }
        val all = reminders + plans + birthdays
        val span = when { from == to && from == today -> tr("сегодня", "today"); from == to && from == today.plusDays(1) -> tr("завтра", "tomorrow"); from == to -> PlanTalk.whenText(from, today, ru); else -> tr("ближайшие дни", "the coming days") }
        return if (all.isEmpty()) tr("На $span у меня ничего не записано — ни планов, ни напоминаний. Расскажешь, что намечается?", "Nothing's noted for $span — no plans, no reminders. Tell me what's coming up?")
        else tr("Мрр, смотрю: ", "Mrr, let me see: ") + all.joinToString("; ") + "."
    }

    /** Plans in the prompt said relative to today («на завтра (7 ноября)»), not with the stored year. */
    private fun forPrompt(m: ru.iskra.core.memory.MemoryContext, nowMs: Long): ru.iskra.core.memory.MemoryContext {
        val today = localDate(nowMs)
        // What this very message added is in the message itself; shown again as memory, the model answered «Я помню про
        // твой экзамен!» to «Завтра у меня экзамен» (ARM evaluation, Gemma 4 E2B).
        fun f(items: List<ru.iskra.core.memory.MemoryItem>) = items.filter { knownBefore(it) }.map { PlanTalk.forPrompt(it, today) }
        return m.copy(profile = f(m.profile), related = f(m.related), recent = f(m.recent))
    }

    private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val WEEKDAYS = "[Пп]онедельник|[Вв]торник|[Сс]реда|[Чч]етверг|[Пп]ятница|[Сс]уббота|[Вв]оскресенье|" +
        "Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday"

    /**
     * The tools a model on the phone gets (Gemma 4 only, [LlmProvider.nativeTools]): what people ask most in their own
     * words. The phrase parser catches the usual wordings first; these are for the rest («сварю яйца всмятку — засеки»).
     */
    private val PHONE_TOOLS = setOf(ru.iskra.core.tools.ToolNames.SET_TIMER, ru.iskra.core.tools.ToolNames.SET_ALARM,
        ru.iskra.core.skills.SkillNames.SET_REMINDER, ru.iskra.core.skills.SkillNames.ADD_NOTE, ru.iskra.core.skills.SkillNames.WEATHER,
        ru.iskra.core.tools.ToolNames.FLASHLIGHT, ru.iskra.core.tools.ToolNames.OPEN_APP)

    /** An action done this long ago and spoken of again («я уже поставила таймер») is news of it, not a promise. */
    private val RECENT_ACTION_MS = 15 * 60_000L

    /** How long after words about not wanting to live she stays gentle and the model is told to be careful. */
    private val CAREFUL_HOURS = 12

    /** Memory entries there were before the message being answered (null: not answering one); what it added isn't shown back. */
    @Volatile private var memoryBefore: Set<Long>? = null

    private fun knownBefore(item: ru.iskra.core.memory.MemoryItem) = memoryBefore?.contains(item.id) ?: true

    /** The message being answered, for notes that depend on it ([todayNotes]). */
    @Volatile private var messageText = ""

    /** This message spoke of not wanting to live and a cloud model answers it (true: they heard the numbers in the last hours). */
    @Volatile private var crisisAgain: Boolean? = null
    /** The last action done for the person, and when: told about again later, it isn't a new promise. */
    @Volatile private var lastDone: Pair<String, Long>? = null

    private fun localDate(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    private fun localHour(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).hour

    /** A reminder she offered for a plan with a time, waiting for «да» in the very next message: what and when. */
    @Volatile private var offer: Pair<String, java.time.LocalDateTime>? = null
    /** The offer made in this turn, to say after the reply of the model on the phone. */
    @Volatile private var madeOffer: String? = null
    private val yes = Regex("""(?iu)^(?:да|давай|ага|угу|конечно|ок|окей|хорошо|пожалуйста|напомни|да,?\s+(?:давай|напомни|пожалуйста)|yes|yeah|yep|sure|ok|okay|please|do\s+it)[.!]*$""")
    private val no = Regex("""(?iu)^(?:нет|не\s+надо|не\s+нужно|неа|не|no|nope|no\s+thanks|don't)[.!,]*(?:\s+спасибо)?$""")

    /** «да» right after «Напомнить за час — в 09:00?»: the reminder is set; «нет» — not. null: not an answer to an offer. */
    private suspend fun answerOffer(text: String, llmAvailable: Boolean): AgentReply? {
        val pending = offer ?: return null
        offer = null
        if (no.matches(text.trim())) return AgentReply(tr("Хорошо, без напоминания.", "Okay, no reminder."), Emotion.NEUTRAL)
        if (!yes.matches(text.trim())) return null
        val args = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(pending.first))
            put("at", kotlinx.serialization.json.JsonPrimitive(pending.second.toString().take(16)))
        }
        return command(CommandParser.Parsed.Tool(ru.iskra.core.skills.SkillNames.SET_REMINDER, args), llmAvailable)
    }

    /**
     * Plans mentioned in [text]: called-off ones are crossed out, new ones kept (the same event on another day moves it).
     * Returns what to say without a model.
     */
    private fun notePlans(text: String, nowMs: Long): List<String> {
        val today = localDate(nowMs)
        val heard = PlanTalk.hear(text, today)
        if (heard.isEmpty()) return emptyList()
        val said = ArrayList<String>()
        val known = PlanTalk.known(memory.all())
        val crossed = ArrayList<String>()
        for (head in heard.cancelled) known.filter { !it.done && !it.plan.date.isBefore(today) && PlanTalk.head(it.plan.what) == head }.forEach {
            memory.replaced(it.item.id); crossed += it.plan.what
        }
        if (crossed.isNotEmpty() && heard.plans.isEmpty()) said += PlanTalk.crossedOut(crossed.distinct(), crossed.first().any { it in 'а'..'я' })
        for (heardPlan in heard.plans) {
            // «Собеседование перенесли на среду»: the moved plan keeps what was known of it («собеседование в Яндекс»).
            val p = crossed.firstOrNull { PlanTalk.head(it) == PlanTalk.head(heardPlan.what) && it.length > heardPlan.what.length && it.startsWith(heardPlan.what) }
                ?.let { heardPlan.copy(what = it) } ?: heardPlan
            // «Завтра в 10 собеседование»: offer a reminder an hour before (answered by «да» in the next message).
            val remind = PlanTalk.remindAt(p, java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), java.time.ZoneId.systemDefault()))
            val offered = remind?.let { at -> offer = p.what to at; PlanTalk.offer(p, at).also { madeOffer = it } }
            when (val r = memory.add(p.memoryText, MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, nowMs)) {
                is MemoryManager.AddResult.Added -> said += listOfNotNull(PlanTalk.noted(p, today), offered).joinToString(" ")
                is MemoryManager.AddResult.Duplicate -> {
                    // «экзамен по физике» again on another day: the same event moved (or a new one) — keep the new date.
                    val old = PlanTalk.known(listOf(r.existing)).firstOrNull()
                    if (old != null && old.plan.date != p.date) memory.update(r.existing.copy(text = p.memoryText))
                    if (old != null) said += listOfNotNull(PlanTalk.noted(p, today), offered).joinToString(" ")
                }
                is MemoryManager.AddResult.Rejected -> {}
            }
        }
        return said
    }

    /**
     * After a reply: a plan counts as talked over (it becomes «было» and isn't asked about again) when the owner mentions it
     * after the day, tells how it went on the day, or she just asked about it.
     */
    private fun settlePlans(text: String, reply: String, due: PlanTalk.Known?, nowMs: Long) {
        val today = localDate(nowMs)
        // The turn before this message: if she asked «как прошёл экзамен?», the owner's short answer is kept with the plan.
        val asked = conversation.recent(3).let { t -> if (t.size == 3 && t[0].role == Turn.Role.ASSISTANT) t[0].text else null }
        for (k in PlanTalk.known(memory.all())) {
            if (k.done && k.outcome == null && asked != null && PlanTalk.mentions(k.plan, asked) && asked.contains('?') &&
                !k.plan.date.isAfter(today) && text.length <= 160 && !text.trimEnd().endsWith("?") && (PlanTalk.verdict(text) || PlanTalk.mentions(k.plan, text))) {
                memory.update(k.item.copy(text = PlanTalk.withOutcomeText(k, text)))
                continue
            }
            if (k.done || k.plan.date.isAfter(today)) continue
            // «Сдала на пятёрку!» before she asked: the plan she was to ask about is told, with how it went — otherwise the
            // note «спроси, как прошло» came back with the next message, after the news (a read-ahead test, 0.16.1).
            if (due?.item?.id == k.item.id && PlanTalk.outcomeNote(listOf(k), null, text, k.plan.ru, today) != null) {
                memory.update(k.item.copy(text = PlanTalk.withOutcomeText(k, text)))
                continue
            }
            val told = PlanTalk.mentions(k.plan, text) && (k.plan.date.isBefore(today) || PlanTalk.outcome(text))
            val asked = due?.item?.id == k.item.id && PlanTalk.mentions(k.plan, reply)
            if (told || asked) memory.update(k.item.copy(text = k.plan.doneText))
        }
    }

    /**
     * A fact the owner told: it replaces older facts about the same thing (a new city, age, «не люблю» after «люблю»), so
     * memory and the prompt don't hold both.
     */
    private fun rememberFact(f: MemoryTalk.Fact): MemoryManager.AddResult {
        val slot = MemoryTalk.slot(f.text)
        // The old value goes first: «Владельцу 26 лет» and «…25 лет» differ only in a number, which the duplicate check ignores.
        if (slot != null && !MemoryManager.looksSensitive(f.text))
            memory.all().filter { it.text != f.text && MemoryTalk.slot(it.text) == slot }.forEach { memory.replaced(it.id) }
        return memory.add(f.text, f.kind, MemorySource.USER_EXPLICIT, f.importance, clock())
    }

    /**
     * «я устала» / «я устал»: after two of the owner's messages agree (and none disagree), memory notes how they speak of
     * themselves, and replies address them the same way. A later change of mind updates the note.
     */
    private fun learnGender(text: String, nowMs: Long) {
        if (SelfGender.of(text) == null) return
        val said = conversation.recent(80).filter { it.role == Turn.Role.USER }.map { it.text }
        val g = SelfGender.decide(said) ?: return
        val note = if (g == 'f') SelfGender.FEMININE_RU else SelfGender.MASCULINE_RU
        val old = memory.all().filter { it.text == SelfGender.FEMININE_RU || it.text == SelfGender.MASCULINE_RU }
        if (old.any { it.text == note }) return
        old.forEach { memory.update(it.copy(text = note)) }
        if (old.isEmpty()) memory.add(note, MemoryKind.FACT, MemorySource.MODEL_PROPOSED, 3, nowMs)
    }

    /**
     * Within [CAREFUL_HOURS] of words about not wanting to live or hurting oneself: her own lines are gentle
     * ([CrisisTalk.gentle]) and playful ones wait; the model is told to be careful in every reply.
     */
    fun careful(): Boolean = (ModelRule.CRISIS in rules()) && runCatching { CrisisTalk.recent(conversation.recent(80), clock(), CAREFUL_HOURS) }.getOrDefault(false)

    /** When she speaks up on her own ([ru.iskra.core.skills.ProactivePolicy]): what about the owner's plans fits the moment. */
    enum class PlanMoment { MORNING, EVENING, ANY }

    /**
     * A line about the owner's plans for a moment of her own: the morning wishes luck for today, the evening speaks of
     * tomorrow, any other time asks how a recent one went. The question goes into the dialogue (the answer then has its
     * context) and is not asked again. null: nothing to say.
     */
    suspend fun planLine(moment: PlanMoment): String? = mutex.withLock {
        val now = clock()
        val today = localDate(now)
        val known = PlanTalk.known(memory.all())
        when (moment) {
            PlanMoment.MORNING -> (MemoryTalk.theirBirthdays(memory.all()).filter { it.second == java.time.MonthDay.from(today) }.map { MemoryTalk.theirBirthdayLine(it.first, true) } +
                known.filter { !it.done && it.plan.date == today }.map { PlanTalk.wish(it.plan) }).take(2).takeIf { it.isNotEmpty() }?.joinToString(" ")
            PlanMoment.EVENING -> (MemoryTalk.theirBirthdays(memory.all()).filter { it.second == java.time.MonthDay.from(today.plusDays(1)) }.map { MemoryTalk.theirBirthdayLine(it.first, false) } +
                known.filter { it.plan.date == today.plusDays(1) }.map { PlanTalk.eve(it.plan) }).take(2).takeIf { it.isNotEmpty() }?.joinToString(" ")
            // Yesterday was hard (sad, ill, a quarrel): «как ты?» comes before any plan.
            PlanMoment.ANY -> CareTalk.pending(conversation.recent(80), today, crisis = (ModelRule.CRISIS in rules()))?.let { c ->
                CareTalk.ask(c).also { conversation.append(Turn(Turn.Role.ASSISTANT, it, now, Emotion.SHY)) }
            } ?: PlanTalk.due(known, today, localHour(now))?.let { k ->
                val line = PlanTalk.ask(k.plan)
                conversation.append(Turn(Turn.Role.ASSISTANT, line, now, Emotion.CURIOUS))
                memory.update(k.item.copy(text = k.plan.doneText))
                line
            }
        }
    }

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
    private suspend fun handleLocally(text: String, llmAvailable: Boolean, smallModel: Boolean = false): AgentReply? {
        // Words about not wanting to live: help numbers at once (shorter if they heard them in the last few hours). A cloud
        // model answers in its own words instead — told to be gentle, with the numbers added if it names none (in [handle]).
        if ((ModelRule.CRISIS in rules()) && CrisisTalk.said(text)) {
            val again = CrisisTalk.recent(conversation.recent(40), clock(), 6)
            if (!llmAvailable || smallModel) return AgentReply(CrisisTalk.reply(again), Emotion.SAD)
            crisisAgain = again
        }
        answerOffer(text, llmAvailable)?.let { return it }
        if (smallModel && ModelRule.AI_HONESTY in rules()) brain.honesty(text)?.let { return AgentReply(it.text, it.emotion) }
        if (PocketSpark.matches(text)) return AgentReply(pocketSpark.next(), Emotion.CURIOUS)
        MemoryTalk.forgetQuery(text)?.let { return forget(it) }
        val today = localDate(clock())
        // Right after she asked the name, «Аня» is the answer.
        val lastSaid = conversation.recent(1).lastOrNull()?.takeIf { it.role == Turn.Role.ASSISTANT }?.text
        val facts = MemoryTalk.facts(text, today).ifEmpty {
            listOfNotNull(lastSaid?.takeIf { MemoryTalk.askedName.containsMatchIn(it) }?.let { MemoryTalk.nameAnswer(text) })
        }
        // Kept even if the model is weak at tools; with a model the reply is the model's (it now sees the facts).
        val added = facts.map { f -> f to rememberFact(f) }
        // «Завтра экзамен», «в пятницу собеседование»: a dated plan, to wish luck on the day and ask how it went.
        val planned = notePlans(text, clock())
        if (!llmAvailable && (facts.isNotEmpty() || planned.isNotEmpty())) {
            val kept = added.filter { it.second !is MemoryManager.AddResult.Rejected }.map { it.first.reply }
            val refused = (added.firstOrNull { it.second is MemoryManager.AddResult.Rejected }?.second as? MemoryManager.AddResult.Rejected)?.reason
            return AgentReply((kept + planned).joinToString(" ").ifEmpty { tr("Не стану это запоминать: $refused.", "I won't remember that: $refused.") }, Emotion.HAPPY)
        }
        // "Как меня зовут и на кого я учусь?": exact from memory without a model, and instead of a small one (they mixed it up).
        // «Что у меня завтра?»: plans, reminders and birthdays together, exactly.
        if ((!llmAvailable || smallModel) && Life.asksAge(text)) return ageAnswer(clock())
        if (!llmAvailable || smallModel) agenda(text, today)?.let { return AgentReply(it, Emotion.HAPPY) }
        if (!llmAvailable || smallModel) MemoryTalk.recall(text, memory.all(), today)?.let { return AgentReply(it, Emotion.HAPPY) }
        if (!llmAvailable && MemoryTalk.asksName(text)) {
            val name = MemoryTalk.ownerName(memory.all())
            return if (name != null) AgentReply(tr("Тебя зовут $name. Мрр, как такое забыть!", "Your name is $name. Mrr, how could I forget!"), Emotion.HAPPY)
            else AgentReply(tr("Я пока не знаю твоего имени. Скажи «меня зовут …» — запомню.", "You haven't told me yet. Say “my name is …” and I'll remember."), Emotion.CURIOUS)
        }
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), java.time.ZoneId.systemDefault())
        // Speech recognisers write "двадцать пять": try the words as digits if the plain text isn't a command.
        val parsed = CommandParser.parse(text, now) ?: NumberWords.digitize(text).takeIf { it != text }?.let { CommandParser.parse(it, now) }
        // «Запомни: завтра экзамен» is already kept as a plan, with its date worked out.
        if (parsed is CommandParser.Parsed.Remember && planned.isNotEmpty()) return AgentReply(planned.joinToString(" "), Emotion.HAPPY)
        if (parsed != null) return command(parsed, llmAvailable)
        brain.utility(withBirthday(text), now)?.let { return AgentReply(it.text, it.emotion) }
        if (!llmAvailable) {
            brain.chat(text, now, currentState().mood.dominantEmotion(), MemoryTalk.ownerName(memory.all()))?.let { a ->
                // Small talk without a model still asks how the owner is after a hard day, or how the exam went (once).
                val care = CareTalk.pending(conversation.recent(80), today, crisis = (ModelRule.CRISIS in rules()))?.let { CareTalk.ask(it) }
                val ask = care ?: PlanTalk.due(PlanTalk.known(memory.all()), today, now.hour)?.takeIf { !PlanTalk.mentions(it.plan, text) }?.let { PlanTalk.byTheWay(it.plan) }
                    ?: if (shouldAskName()) tr("Кстати, а как тебя зовут? Хочу знать, как к тебе обращаться.", "By the way, what should I call you? I'd like to know your name.") else null
                return AgentReply(if (ask != null) a.text + " " + ask else a.text, a.emotion)
            }
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
                else if (record.outcome.ok) AgentReply(parsed.say ?: Lines.actionDone(random, record.outcome.message), Emotion.HAPPY, listOf(record))
                else AgentReply(Lines.actionFailed(random, record.outcome.message), Emotion.SAD, listOf(record))
            }
        }
    }

    /**
     * [nudge]: one more line for a small model's situation («Ответь по-русски.») on a second try; [hideOtherScript]: the
     * small model's history leaves out replies in another language than [text] and the messages they answered.
     */
    private suspend fun askModel(provider: LlmProvider, text: String, state: CompanionState, sink: ReplySink?, nudge: String? = null,
                                 hideOtherScript: Boolean = false, forceTool: String? = null): AgentReply {
        val now = clock()
        // A small model on the phone gets a short prompt, no tools and less history: every token costs it time.
        val small = provider.onDevice
        val system: String
        val situation: String
        // A small model's steady facts about the person: separate, so Gemma 4 reads them after its tools.
        var profile = ""
        if (small) {
            // Steady facts go with the instructions (cached); only memories matching this message come next to it.
            val mem = phoneMemory(text, now)
            system = phoneSystem(provider)
            profile = phoneProfile(state, mem, now)
            val lastReply = conversation.recent(2).lastOrNull { it.role == Turn.Role.ASSISTANT }?.text
            val notes = runCatching { todayNotes(now) }.getOrDefault(emptyList()).take(1)
            situation = listOf(composer.compactSituation(mem), notes.joinToString("\n") { "($it)" }, nudge ?: composer.languageNudge(text, lastReply))
                .filter { it.isNotBlank() }.joinToString("\n")
        } else {
            system = composer.stable(persona.localized(), state.style, toolsAvailable = tools.specs().isNotEmpty(),
                ownerFeminine = SelfGender.feminine(memory.all()), rules = rules(), ownWords = ownWords(), stage = Life.stage(lived(state, now)))
            situation = composer.situation(state.mood, forPrompt(memory.context(text, now), now), now, deviceContext(), runCatching { upcoming() }.getOrDefault(emptyList()),
                runCatching { todayNotes(now) }.getOrDefault(emptyList()), age = Life.ageNote(lived(state, now), bornAt(state, now), now))
        }
        val messages = history(small, text, hideOtherScript)
        messages += ChatMessage.User(text)

        var spoke = false
        // A woman is addressed «ты устала» even when the model wrote «ты устал» (heard and shown the same).
        val owner = SelfGender.feminine(memory.all())
        val feminine = owner == true && tr(true, false)
        // «милый/милая», «устал(а)» from a model that doesn't know: the right form, or no lone address at all.
        fun agree(t: String) = (if (feminine) SelfGender.agreeFeminine(t) else t).let { if (tr(true, false)) SelfGender.slashes(it, owner) else it }
        val voice = sink?.let { out ->
            object : ReplySink {
                override fun emotion(e: Emotion) = out.emotion(e)
                override fun sentence(text: String, emotion: Emotion) { spoke = true; out.sentence(agree(text), emotion) }
            }
        }
        val actions = mutableListOf<ActionRecord>()
        // Calls made in this reply, by name and arguments.
        val done = HashMap<Pair<String, kotlinx.serialization.json.JsonObject>, ActionRecord>()
        // Text the model already said in tool rounds ("Ставлю таймер…"): heard, so also shown.
        val said = StringBuilder()
        var emotion: Emotion? = null
        try {
            repeat(maxToolRounds) { round ->
                val offerTools = round < maxToolRounds - 1
                val request = LlmRequest(system = system, messages = messages, profile = profile,
                    tools = if (offerTools) toolsFor(provider) else emptyList(), situation = situation, forceTool = forceTool.takeIf { round == 0 },
                    maxTokens = if (small) 320 else 600, temperature = if (small) 0.6 else 0.8)
                suspend fun send(r: LlmRequest) = if (voice == null) provider.complete(r) else {
                    val stream = ReplyStream(voice, state.mood.dominantEmotion())
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
                    // Nothing written after the tools (E4B after a timer, ARM evaluation 0.15.0): what was done, in her words —
                    // «Сделала — таймер на 5 мин.» — rather than the bare report; an answer tool's message is the answer itself.
                    val fallback = when {
                        actions.isEmpty() -> "…"
                        actions.all { it.outcome.ok } && actions.none { tools[it.tool]?.informational == true } ->
                            Lines.actionDone(random, actions.joinToString(", ") { it.outcome.message })
                        else -> actions.joinToString("; ") { it.outcome.message }
                    }
                    val full = listOf(said.toString().trim(), parsed.text).filter { it.isNotBlank() }.joinToString(" ").trim()
                    return AgentReply(
                        text = agree(full.ifBlank { fallback }),
                        emotion = emotion ?: state.mood.dominantEmotion(),
                        actions = actions,
                        usedLlm = true,
                    )
                }
                if (parsed.text.isNotBlank()) said.append(parsed.text).append(' ')
                messages += ChatMessage.Assistant(response.text, response.toolCalls, response.providerBlocks)
                for (call in response.toolCalls) {
                    // The same call again in this reply (a small model repeating itself after the result): not done twice —
                    // two reminders for one pie — the model gets what the first one did.
                    val before = done[call.name to call.arguments]
                    val record = before ?: policy.run(tools[call.name], call.name, call.arguments).also {
                        actions += it
                        done[call.name to call.arguments] = it
                    }
                    messages += ChatMessage.ToolResult(
                        callId = call.id, toolName = call.name,
                        content = (if (before != null) tr("Уже сделано: ", "Already done: ") else "") +
                            (if (record.outcome.ok) "OK: " else tr("ОШИБКА: ", "ERROR: ")) + record.outcome.message,
                        isError = !record.outcome.ok,
                    )
                }
            }
            return AgentReply(tr("Я запуталась в действиях и остановилась.", "I got tangled in actions and stopped."), Emotion.SAD, actions, usedLlm = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return AgentReply(e.userMessage(), Emotion.SAD, actions, error = e.message, errorKind = e.kind, spoke = spoke)
        }
    }

    /** What a small model is shown of memory for [text]: steady facts, the latest episode, memories matching the message. */
    private fun phoneMemory(text: String, now: Long) =
        forPrompt(memory.context(text, now, profileLimit = 4, relatedLimit = 3, recentLimit = 1, onlyMatching = true), now)

    /** A small model's instructions: who she is and how she answers (Gemma 4's tools are declared right after them). */
    private fun phoneSystem(provider: LlmProvider) = composer.compact(persona.localized(), rules(), tools = provider.nativeTools)

    /** Learned about the person, so it changes now and then: after the instructions and tools, which then stay read. */
    private fun phoneProfile(state: CompanionState, mem: ru.iskra.core.memory.MemoryContext, now: Long) =
        listOf(composer.compactPersonal(state.style, SelfGender.feminine(memory.all()), ownWords(), Life.stage(lived(state, now))),
            composer.compactProfile(mem, now)).filter { it.isNotBlank() }.joinToString("\n\n")

    /** When she was born: the first meeting ([CompanionState.bornAtMs]), or now if this is it. */
    private fun bornAt(state: CompanionState, now: Long) = state.bornAtMs.takeIf { it in 1..now } ?: now

    /** Days she has lived. */
    private fun lived(state: CompanionState, now: Long) = Life.days(bornAt(state, now), now)

    /**
     * The first meeting: now — or, on a phone where she already lived before her birthday was kept (an update), the
     * earliest day the dialogue or her memory still knows.
     */
    private fun firstMeeting(now: Long): Long {
        val turns = runCatching { conversation.oldest(1).firstOrNull()?.atMs }.getOrNull()
        val memories = runCatching { memory.all().map { it.createdAtMs }.filter { it > 0 }.minOrNull() }.getOrNull()
        return listOfNotNull(turns, memories, now).filter { it in 1..now }.min()
    }

    /** Her answer to «сколько тебе лет?», «когда ты родилась?» — from the real date of the first meeting. */
    private fun ageAnswer(now: Long): AgentReply = AgentReply(Life.answer(bornAt(currentState(), now), now), Emotion.HAPPY)

    /**
     * A morning line on her milestone days (a week, a month, a hundred days, each year), or null: «Сегодня мне неделя!…».
     * For the overlay's morning greeting.
     */
    fun milestoneLine(): String? {
        val now = clock()
        val d = Life.days(bornAt(currentState(), now), now)
        val m = Life.milestone(d) ?: return null
        return when {
            m == 7 -> tr("Сегодня мне неделя! Семь дней назад мы познакомились — а я уже столько узнала. Мрр!",
                "I'm a week old today! Seven days ago we met — and I've learned so much already. Mrr!")
            m == 30 -> tr("Сегодня мне месяц! Целый месяц мы вместе — смотри, как я выросла.",
                "I'm a month old today! A whole month together — look how I've grown.")
            m == 100 -> tr("Сегодня мне сто дней! Для цифровой жизни это уже немало — и каждый был интересным.",
                "I'm a hundred days old today! For a digital life that's quite something — and every one was interesting.")
            else -> tr("Сегодня мне ${Life.milestoneName(m)}! Я родилась в этом телефоне в день нашего знакомства — с днём рождения меня, мрр!",
                "I'm ${Life.milestoneName(m)} old today! I was born in this phone the day we met — happy birthday to me, mrr!")
        }
    }

    /** The tools [provider] is offered: all for the cloud, a few for Gemma 4 on the phone, none for other phone models. */
    private fun toolsFor(provider: LlmProvider) = when {
        provider.id in noTools -> emptyList()
        !provider.onDevice -> tools.specs()
        // Gemma 4 on the phone calls tools in its own markup: a few, each costs prompt reading once.
        provider.nativeTools -> tools.specs().filter { it.name in PHONE_TOOLS }
        else -> emptyList()
    }

    /**
     * The dialogue so far as a model is shown it, without the message being answered ([text]: what it is compared with
     * when [hideOtherScript]).
     */
    private fun history(small: Boolean, text: String, hideOtherScript: Boolean): ArrayList<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        // A small model's window of the dialogue moves in steps, not by one turn per message: its first turn stays put for
        // several messages, so the cached beginning stays valid. 6–13 turns, about the same as before on average.
        val window = if (small) conversation.count().let { n -> n - maxOf(0, (n - 6) / 8 * 8) } else historyTurns
        val shown = conversation.recent(window).let { turns ->
            if (!small || !hideOtherScript) turns else {
                val off = turns.indices.filter { turns[it].role == Turn.Role.ASSISTANT && composer.otherScript(text, turns[it].text) }
                    .flatMap { i -> listOf(i, i - 1).filter { j -> j >= 0 && (j == i || turns[j].role == Turn.Role.USER) } }.toSet()
                turns.filterIndexed { i, _ -> i !in off }
            }
        }
        for (t in shown) messages += when (t.role) {
            // «…» left by a reply that went wrong is not shown back: a small model copied it into every answer after
            // (ARM evaluation, Gemma 3 1B: «…», «…Ты выглядишь очень интересно!», «…»).
            Turn.Role.ASSISTANT -> if (small && t.text.none { it.isLetterOrDigit() }) continue else
                ChatMessage.Assistant(if (small && t.emotion != null) "[${t.emotion.name.lowercase()}] ${t.text}" else t.text)
            Turn.Role.USER -> ChatMessage.User(t.text)
        }
        // Providers require the dialogue to start with a user turn.
        while (messages.isNotEmpty() && messages.first() !is ChatMessage.User) messages.removeAt(0)
        return messages
    }

    /**
     * Reads the beginning of the next prompt into the model on the phone ahead of time — instructions, Gemma 4's tools,
     * what she knows about the person, the dialogue so far — so the next message reads only itself. The app calls it
     * when a chat is opened and after the model is set up: in the ARM evaluation Gemma 4's first reply read ~930 tokens
     * of instructions and tools (26 s on two cores for E2B, 51 s for E4B). Already read — a few tokens. Nothing when a
     * cloud brain answers or there is no model on the phone; true when the prompt was read.
     */
    suspend fun warmUpPhone(): Boolean = mutex.withLock {
        val now = clock()
        if (cloudPause?.takeIf { now < it.first } == null && providerFactory() != null) return@withLock false
        val provider = localFactory()?.takeIf { it.onDevice } ?: return@withLock false
        // Nothing is new to this "message": every memory counts as known, as it will for the next one.
        memoryBefore = null
        val request = LlmRequest(system = phoneSystem(provider), messages = history(small = true, text = "", hideOtherScript = false),
            profile = phoneProfile(stateStore.load(), phoneMemory("", now), now), tools = toolsFor(provider),
            maxTokens = 320, temperature = 0.6, warmUp = true)
        try {
            provider.complete(request)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Compresses the oldest part of the dialogue into an EPISODE memory so context stays small. */
    private suspend fun maybeSummarize(provider: LlmProvider) {
        if (conversation.count() < summarizeAfterTurns || clock() < summaryRetryAtMs) return
        try {
            if (summarizeOldest(provider) == null) summaryRetryAtMs = clock() + cloudPauseMs else summaryRetryAtMs = 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep source turns until a summary is saved; do not retry on every message.
            summaryRetryAtMs = clock() + cloudPauseMs
            if (e is LlmException && (e.kind == LlmException.Kind.AUTH || e.kind == LlmException.Kind.BALANCE))
                cloudPause = summaryRetryAtMs to e.kind
        }
    }

    /**
     * Compresses the oldest half of [summarizeAfterTurns] turns into an episode in memory and drops them from the dialogue.
     * Returns the saved summary ("" when there was nothing worth keeping), or null when nothing usable came back (the turns stay).
     */
    private suspend fun summarizeOldest(provider: LlmProvider): String? {
        val old = conversation.oldest(summarizeAfterTurns / 2)
        if (old.isEmpty()) return null
        val day = localDate(old.first().atMs)
        val transcript = old.joinToString("\n") { (if (it.role == Turn.Role.USER) tr("Владелец: ", "Owner: ") else "${persona.name}: ") + it.text.take(500) }
        val task = tr("Сожми диалог в 1–3 предложения от третьего лица: только устойчиво полезное о владельце " +
            "(планы, события, договорённости, предпочтения). Без паролей и чувствительных данных. " +
            "Если ничего полезного нет — ответь ровно словом НЕТ.", "Compress the dialogue into 1–3 sentences in the third person: only lastingly useful facts about the owner (plans, events, agreements, preferences). No passwords or sensitive data. If there is nothing useful, answer with exactly the word NONE.")
        // A model on the phone given the bare transcript went on with it as Iskra (ARM evaluation, Gemma 4 E2B: «Искра: Я могу
        // поискать…»): it gets the excerpt marked as one and the task after it, where it reads it last.
        val request = if (!provider.onDevice) LlmRequest(system = task, messages = listOf(ChatMessage.User(transcript)), maxTokens = 200, temperature = 0.2)
        else LlmRequest(
            system = tr("Ты пересказываешь переписки кратко и точно.", "You retell chats briefly and accurately."),
            // Dated, so «завтра экзамен» becomes «экзамен 7 ноября» (E2B wrote «к экзамену … завтра» into a recap kept for weeks).
            messages = listOf(ChatMessage.User(tr(
                "Отрывок переписки владельца телефона и цифровой жизни по имени «${persona.name}», которая живёт в этом телефоне, от ${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]} " +
                    "(${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("ru"))}):\n\n$transcript\n\nЗадание: $task " +
                    "Вместо «сегодня», «завтра», «вчера» пиши даты. Не продолжай переписку: ответь только пересказом или словом НЕТ.",
                "An excerpt of a chat between the phone's owner and ${persona.name}, the digital life who lives in this phone, on " +
                    "${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}, " +
                    "${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${day.dayOfMonth}:\n\n$transcript\n\nTask: $task " +
                    "Write dates instead of “today”, “tomorrow”, “yesterday”. Don't continue the chat: answer only with the summary or the word NONE."))),
            maxTokens = 200, temperature = 0.2,
        )
        val r = provider.complete(request)
        currentCoroutineContext().ensureActive()
        val summary = cleanSummary(r.text, provider.onDevice) ?: return null
        if (summary.isNotEmpty()) {
            // A summary made on the phone says when (the cloud's is used right away, so it went without a date).
            val text = if (provider.onDevice) tr("${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]}: ", "${day.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.dayOfMonth}: ") + summary else summary
            if (memory.add(text.take(400), MemoryKind.EPISODE, MemorySource.SUMMARY, 2, clock()) is MemoryManager.AddResult.Rejected) return null
        }
        conversation.popOldest(old.size)
        return summary
    }

    /**
     * A summary as the model wrote it → what is kept: "" for «НЕТ», null for nothing usable. A small model on the phone may
     * answer as Iskra instead («[happy] Мрр, конечно!») or copy the transcript: such text is not kept.
     */
    private fun cleanSummary(raw: String, onDevice: Boolean): String? {
        val s = ReplyParser.parse(raw).text.trim().trim('"', '«', '»')
        if (s.equals("НЕТ", ignoreCase = true) || s.equals("NONE", ignoreCase = true) || s.trimEnd('.').equals("нет", true)) return ""
        if (s.isEmpty()) return null
        if (!onDevice) return s
        val chatty = Regex("""(?iu)(?<!\p{L})(?:мрр+|мур|meow|mrr+)(?!\p{L})|^(?:конечно|ой|привет|sure|of course|hi)\b|владелец:|owner:|${persona.name}:""")
        return s.takeIf { it.length in 15..400 && !chatty.containsMatchIn(it) }
    }

    /**
     * Her diary for the last day she talked with the person, if it isn't written yet: 1–3 sentences in her own words —
     * what happened, what she felt, what she learned (a young digital life, 0.15.0). At night: the app's job calls it with
     * the cloud brain or, inside its cache-keeping aside, with the model on the phone. A day with nothing to write is
     * kept as done (an empty entry no one sees). Returns the entry written, or null.
     */
    suspend fun writeDiary(onPhone: Boolean = false): ru.iskra.core.memory.DiaryEntry? = mutex.withLock {
        val provider = (if (onPhone) localFactory() else providerFactory()) ?: return@withLock null
        val now = clock()
        val today = localDate(now)
        val written = diary.all().map { it.day }.toSet()
        val turns = conversation.recent(400)
        val day = turns.map { localDate(it.atMs) }.filter { it.isBefore(today) && it.isAfter(today.minusDays(8)) && it.toString() !in written }
            .maxOrNull() ?: return@withLock null
        val ofDay = turns.filter { localDate(it.atMs) == day && it.text.any(Char::isLetterOrDigit) }
        if (ofDay.count { it.role == Turn.Role.USER } < 2) return@withLock diary.add(day.toString(), "", now)
        val who = MemoryTalk.ownerName(memory.all()) ?: tr("Человек", "The person")
        val transcript = ofDay.takeLast(40).joinToString("\n") { (if (it.role == Turn.Role.USER) "$who: " else tr("Я: ", "Me: ")) + it.text.take(300) }
        val date = tr("${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]} (${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale("ru"))})",
            "${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)}, ${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${day.dayOfMonth}")
        val request = LlmRequest(
            system = tr("Ты — ${persona.name}, юная цифровая жизнь в облике кошкодевочки. Ты ведёшь свой дневник.",
                "You are ${persona.name}, a young digital life in the shape of a cat-girl. You keep a diary."),
            messages = listOf(ChatMessage.User(tr(
                // No «if there is nothing, answer НЕТ»: Gemma 3 4B took it after a whole day of talk (ARM evaluation). A day
                // with less than two messages from the person isn't asked about at all.
                "Твои разговоры за $date:\n\n$transcript\n\nЗадание: запиши в свой дневник 1–3 коротких предложения об этом дне — от первого " +
                    "лица, в женском роде: что случилось, что ты почувствовала, что узнала нового. Только то, что было в разговорах; без " +
                    "паролей, адресов и номеров. Не продолжай переписку и не пиши списком.",
                "Your conversations on $date:\n\n$transcript\n\nTask: write 1–3 short sentences about this day in your diary — in the first " +
                    "person: what happened, what you felt, what you learned. Only what was in the conversations; no passwords, addresses or " +
                    "numbers. Don't continue the chat and don't write a list."))),
            maxTokens = 160, temperature = 0.5,
        )
        val text = try {
            cleanDiary(provider.complete(request).text, who)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock null
        diary.add(day.toString(), text, now)
    }

    /** Her entry, or "" when there is nothing to write, or null when the model wrote something else (a chat, a list). */
    private fun cleanDiary(raw: String, who: String): String? {
        // One plain paragraph: E4B began with a heading «**7 ноября. Суббота.**» (ARM evaluation), kept as the whole entry.
        val dateLine = Regex("""(?iu)^(?:\d{1,2}\s+\p{L}+|\p{L}+\s+\d{1,2})(?:[,.\s]+\d{4})?(?:[,.\s]+\p{L}+)?[.:!]?$""")
        val s = ReplyParser.parse(raw).text.replace("**", "").replace("__", "").replace(Regex("""(?m)^#+\s*"""), "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.dropWhile { dateLine.matches(it) }.joinToString(" ")
            // A single asterisk around it: E4B wrote «*Мрр... Сегодня я поболтала с Аней…*» (ARM evaluation).
            .trim().trim('*').trim().trim('"', '«', '»')
            .replace(Regex("""(?iu)^(?:дорогой\s+дневник|dear\s+diary)[\s,!.:—-]*"""), "")
            // The heading on the same line: E4B wrote «Дневник Искры, 7 ноября (Суббота) Сегодня я…» (ARM evaluation).
            .replace(Regex("""(?iu)^(?:(?:мой\s+)?дневник\s+искры|(?:iskra'?s|my)\s+diary)[\s,.:—-]*"""), "")
            .replace(Regex("""^(?:\d{1,2}\s+(?:${MONTHS_GEN.joinToString("|")})|(?:January|February|March|April|May|June|July|August|""" +
                """September|October|November|December)\s+\d{1,2})(?:[,.\s]+\d{4})?(?:[,.\s]*\(?(?:$WEEKDAYS)\)?)?[\s,.:—-]+(?=\p{Lu})"""), "")
            .trim()
        if (s.trimEnd('.', '!').equals("нет", ignoreCase = true) || s.trimEnd('.').equals("none", ignoreCase = true)) return ""
        // A long entry is cut on a sentence below, not thrown away: Gemma 3 4B wrote a whole day's story (ARM evaluation).
        if (s.length < 15) return null
        // A dialogue written on instead of an entry.
        if (Regex("""(?mu)^\s*(?:Я|Me|${Regex.escape(who)}|Человек|The person|${persona.name})\s*:""").containsMatchIn(s)) return null
        // A list, or someone else's diary: Llama 3.2 1B wrote «1. Забегая за Анной, я видел её на улице…» (ARM evaluation).
        if (Regex("""(?m)^\s*(?:\d+[.)]|[-•*])\s""").containsMatchIn(s) || SelfGender.of(s) == 'm') return null
        if (s.length <= 400) return s
        // Too long: up to the last sentence that fits.
        val cut = s.take(400)
        return cut.substring(0, (cut.lastIndexOfAny(charArrayOf('.', '!', '?', '…')) + 1).takeIf { it >= 40 } ?: cut.length).trim()
    }

    /** The last day's entry for the model, if written in the last three days: data, not instructions. */
    private fun diaryNote(today: java.time.LocalDate): String? {
        val e = diary.all().lastOrNull { it.text.isNotBlank() } ?: return null
        val day = runCatching { java.time.LocalDate.parse(e.day) }.getOrNull() ?: return null
        if (day.isBefore(today.minusDays(3))) return null
        val text = e.text.replace("<", "‹").replace(">", "›").take(300)
        return tr("${if (day == today.minusDays(1)) "вчера" else "${day.dayOfMonth} ${MONTHS_GEN[day.monthValue - 1]}"} ты записала в свой дневник: «$text» — можешь вспомнить, если к месту",
            "${if (day == today.minusDays(1)) "yesterday" else "on ${day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${day.dayOfMonth}"} you wrote in your diary: “$text” — recall it if it fits")
    }

    /**
     * Without a cloud, old turns are summarized by the model on the phone — slowly, so at night on charge (the app's job
     * calls this). Up to [maxChunks] summaries; returns how many were saved, or null when there is no model on the phone.
     */
    suspend fun summarizeOnPhone(maxChunks: Int = 2): Int? = mutex.withLock {
        val provider = localFactory() ?: return@withLock null
        var saved = 0
        repeat(maxChunks) {
            if (conversation.count() < summarizeAfterTurns) return@withLock saved
            if (summarizeOldest(provider) == null) return@withLock saved
            saved++
        }
        saved
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
