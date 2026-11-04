package ru.iskra.core

import kotlinx.coroutines.test.runTest
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.usage.InMemoryUsageStore
import ru.iskra.core.usage.MeteredProvider
import ru.iskra.core.usage.TokenEstimate
import ru.iskra.core.usage.UsageLimits
import ru.iskra.core.usage.UsageMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.9.1: daily cloud usage, limits and prices; nothing about what was said is stored. */
class UsageTest {
    private var day = "2026-11-04"
    private var limits = UsageLimits()
    private val store = InMemoryUsageStore()
    private val meter = UsageMeter(store, { limits }, { day })
    private val req = LlmRequest(system = "Ты Искра.", messages = listOf(ChatMessage.User("привет, как дела?")))

    @Test fun reportedUsageIsCountedAndTheDayResets() = runTest {
        val p = MeteredProvider(FakeProvider(mutableListOf({ _ -> LlmResponse("Мрр!", emptyList(), 120, 15) })), meter)
        p.complete(req)
        val d = meter.current()
        assertEquals(1, d.llmRequests); assertEquals(135, d.tokens); assertFalse(d.estimated)
        day = "2026-11-05"
        assertEquals(0, meter.current().llmRequests)
    }

    @Test fun missingUsageIsEstimatedAndMarked() = runTest {
        val p = MeteredProvider(FakeProvider(mutableListOf({ _ -> LlmResponse("Привет-привет!", emptyList()) })), meter)
        p.complete(req)
        val d = meter.current()
        assertTrue(d.estimated)
        assertEquals(TokenEstimate.of(req).toLong(), d.inputTokens)
        assertTrue(meter.describe().contains("≈"))
    }

    @Test fun limitStopsTheCloudUntilTomorrow() = runTest {
        limits = UsageLimits(llmRequests = 2)
        val p = MeteredProvider(FakeProvider(MutableList(3) { { _: LlmRequest -> LlmResponse("ок", emptyList(), 10, 5) } }), meter)
        p.complete(req); p.complete(req)
        val e = assertFailsWith<LlmException> { p.complete(req) }
        assertEquals(LlmException.Kind.LIMIT, e.kind)
        assertTrue(e.persistent)
        day = "2026-11-05"
        p.complete(req) // a new day, a new budget
    }

    @Test fun warningOnceAt80Percent() {
        limits = UsageLimits(tokens = 1000)
        meter.recordLlm(700, 50, estimated = false)
        assertNull(meter.takeWarning())
        meter.recordLlm(40, 20, estimated = false) // 810 / 1000
        val w = assertNotNull(meter.takeWarning())
        assertTrue(w.contains("81 %"), w)
        assertNull(meter.takeWarning()) // once a day
    }

    @Test fun ttsCharsHaveTheirOwnLimit() {
        limits = UsageLimits(ttsChars = 100)
        assertTrue(meter.ttsAllowed())
        meter.recordTts(100)
        assertFalse(meter.ttsAllowed())
        assertTrue(meter.llmAllowed())
    }

    @Test fun costOnlyWithOwnersPrices() {
        meter.recordLlm(1_000_000, 500_000, estimated = false)
        meter.recordTts(200_000)
        assertNull(meter.cost())
        limits = UsageLimits(priceInPerM = 20.0, priceOutPerM = 80.0, priceTtsPerM = 1500.0, currency = "₽")
        assertEquals(20.0 + 40.0 + 300.0, meter.cost()!!, 1e-9)
        assertTrue(meter.describe().contains("360.00 ₽"), meter.describe())
    }

    @Test fun openAiStreamAsksForUsageWhenTheServiceSupportsIt() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Мрр.\"}}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":4}}\n\ndata: [DONE]\n\n"))
            val p = ru.iskra.core.llm.OpenAiCompatibleProvider(server.url("/v1").toString(), "sk-test", "m", streamUsage = true)
            val r = MeteredProvider(p, meter).stream(req) {}
            assertTrue(server.takeRequest().body.readUtf8().contains("\"include_usage\":true"))
            assertEquals(34, r.inputTokens + r.outputTokens)
            assertEquals(34, meter.current().tokens)
            assertFalse(meter.current().estimated)
        } finally { server.shutdown() }
    }

    @Test fun agentSaysTheLimitPlainlyAndSkipsTheCloud() = runTest {
        limits = UsageLimits(llmRequests = 1)
        var calls = 0
        val metered = MeteredProvider(FakeProvider(MutableList(5) { { _: LlmRequest -> calls++; LlmResponse("[happy] Мрр.", emptyList(), 10, 5) } }), meter)
        val agent = CompanionAgent(
            persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
            stateStore = InMemoryStateStore(), tools = ToolRegistry(emptyList()), policy = SafetyPolicy(gate = { _, _ -> true }),
            providerFactory = { metered }, random = Random(0),
        )
        agent.handle("как дела?")
        val limited = agent.handle("расскажи что-нибудь")
        assertEquals(LlmException.Kind.LIMIT, limited.errorKind)
        val next = agent.handle("а теперь?")
        assertTrue(next.text.contains("лимит", ignoreCase = true), next.text)
        assertEquals(1, calls)
    }
}
