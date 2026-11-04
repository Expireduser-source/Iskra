package ru.iskra.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.Turn
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.llm.ToolCall
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.tools.ToolSchemas
import ru.iskra.core.tools.int
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeProvider(private val script: MutableList<(LlmRequest) -> LlmResponse>) : LlmProvider {
    override val id = "fake"
    val requests = mutableListOf<LlmRequest>()
    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return script.removeAt(0)(request)
    }
}

class RecordingTool(override val risk: Risk = Risk.SAFE) : Tool {
    val calls = mutableListOf<JsonObject>()
    override val spec = ToolSchemas.setTimer
    override fun describe(args: JsonObject) = "таймер на ${args.int("seconds")} с"
    override suspend fun execute(args: JsonObject): ToolOutcome { calls += args; return ToolOutcome(true, "таймер запущен") }
}

class AgentTest {
    private var now = 1_700_000_000_000L
    private val memory = MemoryManager(InMemoryMemoryStore())
    private val convo = InMemoryConversationStore()

    private fun agent(provider: LlmProvider?, tool: Tool = RecordingTool(), confirm: Boolean = true) = CompanionAgent(
        persona = Personas.ISKRA,
        memory = memory,
        conversation = convo,
        stateStore = InMemoryStateStore(),
        tools = ToolRegistry(listOf(tool)),
        policy = SafetyPolicy(gate = { _, _ -> confirm }, clock = { now }),
        providerFactory = { provider },
        clock = { now },
        random = Random(0),
    )

    @Test fun localCommandWorksOffline() = runTest {
        val tool = RecordingTool()
        val r = agent(null, tool).handle("таймер на 2 минуты")
        assertEquals(1, tool.calls.size)
        assertTrue(r.actions.single().outcome.ok)
        assertFalse(r.usedLlm)
    }

    @Test fun offlineChitChatExplainsMode() = runTest {
        val r = agent(null).handle("как дела?")
        assertTrue(r.text.contains("модел"), r.text)
    }

    @Test fun rememberCommandStoresMemory() = runTest {
        agent(null).handle("запомни: я работаю дизайнером")
        assertEquals("я работаю дизайнером", memory.all().single().text)
    }

    @Test fun modelToolLoopAndEmotionTag() = runTest {
        val provider = FakeProvider(mutableListOf(
            { LlmResponse("", listOf(ToolCall("c1", "set_timer", buildJsonObject { put("seconds", 600) }))) },
            { req ->
                val last = req.messages.last() as ChatMessage.ToolResult
                assertTrue(last.content.startsWith("OK"))
                LlmResponse("[happy] Поставила таймер на десять минут!", emptyList())
            },
        ))
        val tool = RecordingTool()
        val r = agent(provider, tool).handle("засеки мне десять минут на чай")
        assertEquals(Emotion.HAPPY, r.emotion)
        assertEquals("Поставила таймер на десять минут!", r.text)
        assertEquals(1, tool.calls.size)
        assertTrue(provider.requests[0].system.contains("Искра"))
        assertTrue(provider.requests[0].tools.any { it.name == "remember" })
    }

    @Test fun confirmToolDeniedIsNotExecuted() = runTest {
        val tool = RecordingTool(Risk.CONFIRM)
        val provider = FakeProvider(mutableListOf(
            { LlmResponse("", listOf(ToolCall("c1", "set_timer", buildJsonObject { put("seconds", 5) }))) },
            { LlmResponse("[sad] Ладно, не буду.", emptyList()) },
        ))
        val r = agent(provider, tool, confirm = false).handle("засеки 5 секунд")
        assertTrue(tool.calls.isEmpty())
        assertFalse(r.actions.single().outcome.ok)
    }

    @Test fun memoryIsInjectedAsData() = runTest {
        val a = agent(FakeProvider(mutableListOf({ LlmResponse("[neutral] ок", emptyList()) })))
        a.handle("запомни: у меня кот Барсик")
        val p = FakeProvider(mutableListOf({ LlmResponse("[curious] Как Барсик?", emptyList()) }))
        agent(p).handle("покорми кота напомни")
        assertTrue(p.requests[0].system.contains("<память_о_владельце>"))
        assertTrue(p.requests[0].system.contains("Барсик"))
    }

    @Test fun llmErrorGivesFriendlyMessage() = runTest {
        val p = FakeProvider(mutableListOf({ throw LlmException("HTTP 401", LlmException.Kind.AUTH) }))
        val r = agent(p).handle("привет")
        assertTrue(r.text.contains("Ключ"), r.text)
        assertEquals(Emotion.SAD, r.emotion)
    }

    @Test fun rateLimitStopsRunawayToolLoops() = runTest {
        val tool = RecordingTool()
        val call = { _: LlmRequest -> LlmResponse("", List(10) { ToolCall("c$it", "set_timer", buildJsonObject { put("seconds", 1) }) }) }
        val p = FakeProvider(mutableListOf(call, call, call, { LlmResponse("[sad] стоп", emptyList()) }))
        agent(p, tool).handle("спамь таймеры")
        assertTrue(tool.calls.size <= 6, "executed ${tool.calls.size}")
    }

    private fun seedHistory(): List<Turn> {
        convo.clear()
        return List(58) { i ->
            Turn(if (i % 2 == 0) Turn.Role.USER else Turn.Role.ASSISTANT, "Реплика $i", now - 60_000 + i)
                .also(convo::append)
        }
    }

    @Test fun authAndBalanceErrorsNeverStartSummaryOrDeleteHistory() = runTest {
        for (kind in listOf(LlmException.Kind.AUTH, LlmException.Kind.BALANCE)) {
            val original = seedHistory()
            val p = FakeProvider(mutableListOf({ throw LlmException("unavailable", kind) }))
            val a = agent(p)
            val reply = a.handle("привет")
            assertEquals(kind, reply.errorKind)
            assertEquals(1, p.requests.size)
            assertEquals(original, convo.oldest(58))
            assertEquals(60, convo.count())
            a.handle("как дела?")
            assertEquals(1, p.requests.size, "Cloud pause must also suppress summarization")
        }
    }

    @Test fun failedSummaryPreservesHistoryAndBacksOffBeforeRetry() = runTest {
        val original = seedHistory()
        val p = FakeProvider(mutableListOf(
            { LlmResponse("[happy] Привет!", emptyList()) },
            { throw LlmException("offline", LlmException.Kind.NETWORK) },
            { LlmResponse("[happy] Я тут.", emptyList()) },
            { LlmResponse("[happy] Слушаю.", emptyList()) },
            { LlmResponse("Владелец планирует поездку в Казань.", emptyList()) },
        ))
        val a = agent(p)
        a.handle("привет")
        assertEquals(original, convo.oldest(58))
        assertEquals(60, convo.count())
        a.handle("как дела?")
        assertEquals(3, p.requests.size, "A failed summary must not be retried on each message")
        assertEquals(62, convo.count())
        now += 5 * 60_000
        a.handle("поговорим?")
        assertEquals(5, p.requests.size)
        assertEquals(34, convo.count())
        assertEquals(original.drop(30), convo.oldest(28))
        assertEquals("Владелец планирует поездку в Казань.", memory.all().single().text)
    }

    @Test fun canceledSummaryPreservesSourceTurns() = runTest {
        val original = seedHistory()
        val p = FakeProvider(mutableListOf(
            { LlmResponse("[happy] Привет!", emptyList()) },
            { throw CancellationException("Stopped by owner") },
        ))
        assertFailsWith<CancellationException> { agent(p).handle("привет") }
        assertEquals(original, convo.oldest(58))
        assertEquals(60, convo.count())
        assertTrue(memory.all().isEmpty())
    }

    @Test fun explicitNoUsefulFactsCompactsHistoryWithoutInventingMemory() = runTest {
        seedHistory()
        val p = FakeProvider(mutableListOf(
            { LlmResponse("[happy] Привет!", emptyList()) },
            { LlmResponse("НЕТ", emptyList()) },
        ))
        agent(p).handle("привет")
        assertEquals(30, convo.count())
        assertTrue(memory.all().isEmpty())
    }

    @Test fun emptyOrRejectedSummaryCannotDiscardHistory() = runTest {
        for (summary in listOf("", "Мой пароль qwerty123")) {
            val original = seedHistory()
            val p = FakeProvider(mutableListOf(
                { LlmResponse("[happy] Привет!", emptyList()) },
                { LlmResponse(summary, emptyList()) },
            ))
            agent(p).handle("привет")
            assertEquals(original, convo.oldest(58))
            assertEquals(60, convo.count())
            assertTrue(memory.all().isEmpty())
        }
    }

    @Test fun summaryAuthFailurePausesFurtherCloudCalls() = runTest {
        val original = seedHistory()
        val p = FakeProvider(mutableListOf(
            { LlmResponse("[happy] Привет!", emptyList()) },
            { throw LlmException("unauthorized", LlmException.Kind.AUTH) },
        ))
        val a = agent(p)
        a.handle("привет")
        val reply = a.handle("как дела?")
        assertFalse(reply.usedLlm)
        assertEquals(2, p.requests.size)
        assertEquals(original, convo.oldest(58))
    }

    @Test fun localCommandsAndPocketSparkStayFreeEvenWhenSummaryIsDue() = runTest {
        seedHistory()
        val p = FakeProvider(mutableListOf())
        val a = agent(p)
        a.handle("таймер на 2 минуты")
        val spark = a.handle("удиви меня")
        assertEquals(Emotion.CURIOUS, spark.emotion)
        assertTrue(spark.text.isNotBlank())
        assertFalse(spark.usedLlm)
        assertTrue(p.requests.isEmpty())
        assertEquals(62, convo.count())
    }

}
