package ru.iskra.core

import kotlinx.coroutines.test.runTest
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.ReplyParser
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.FallbackProvider
import ru.iskra.core.llm.FreeModel
import ru.iskra.core.llm.Http
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.llm.OpenRouterConnect
import ru.iskra.core.llm.ThinkFilter
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.voice.ReplySink
import ru.iskra.core.voice.ReplyStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Scripted(override val id: String, private val script: MutableList<() -> LlmResponse>) : LlmProvider {
    var calls = 0
    val requests = mutableListOf<LlmRequest>()
    override suspend fun complete(request: LlmRequest): LlmResponse { calls++; requests += request; return script.removeAt(0)() }
    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse = complete(request).also { onText(it.text) }
}

private fun fail(kind: LlmException.Kind): () -> LlmResponse = { throw LlmException("x", kind) }
private fun ok(text: String): () -> LlmResponse = { LlmResponse(text, emptyList()) }

class ResilienceTest {
    @Test
    fun busyFreeModelHandsOverAndIsRememberedForAWhile() = runTest {
        var t = 0L
        val a = Scripted("a", mutableListOf(fail(LlmException.Kind.RATE_LIMIT), ok("a2")))
        val b = Scripted("b", mutableListOf(ok("b1"), ok("b2")))
        val p = FallbackProvider(listOf(a, b), retryDelayMs = 0, clock = { t })
        assertEquals("b1", p.complete(LlmRequest("s", emptyList())).text)
        assertEquals("b2", p.complete(LlmRequest("s", emptyList())).text)   // sticky: b first now
        assertEquals(1, a.calls)
        t += 11 * 60_000
        assertEquals("a2", p.complete(LlmRequest("s", emptyList())).text)   // later the first choice again
    }

    @Test
    fun keyAndQuotaErrorsAreNotHandedOver() = runTest {
        val a = Scripted("a", mutableListOf(fail(LlmException.Kind.FREE_QUOTA)))
        val b = Scripted("b", mutableListOf(ok("b")))
        assertFailsWith<LlmException> { FallbackProvider(listOf(a, b), retryDelayMs = 0).complete(LlmRequest("s", emptyList())) }
        assertEquals(0, b.calls)
    }

    @Test
    fun singleProviderRetriesOnceAfterAHiccup() = runTest {
        val a = Scripted("a", mutableListOf(fail(LlmException.Kind.SERVER), ok("fine")))
        assertEquals("fine", FallbackProvider(listOf(a), retryDelayMs = 0).complete(LlmRequest("s", emptyList())).text)
        val b = Scripted("b", mutableListOf(fail(LlmException.Kind.AUTH), ok("never")))
        assertFailsWith<LlmException> { FallbackProvider(listOf(b), retryDelayMs = 0).complete(LlmRequest("s", emptyList())) }
        assertEquals(1, b.calls)
    }

    @Test
    fun nothingIsRetriedAfterTextWasSpoken() = runTest {
        val half = object : LlmProvider {
            override val id = "half"
            override suspend fun complete(request: LlmRequest) = LlmResponse("", emptyList())
            override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse {
                onText("Начало…"); throw LlmException("cut", LlmException.Kind.SERVER)
            }
        }
        val b = Scripted("b", mutableListOf(ok("другой ответ")))
        val heard = StringBuilder()
        assertFailsWith<LlmException> { FallbackProvider(listOf(half, b), retryDelayMs = 0).stream(LlmRequest("s", emptyList())) { heard.append(it) } }
        assertEquals("Начало…", heard.toString())
        assertEquals(0, b.calls)
    }

    @Test
    fun thinkingIsNeitherShownNorSpoken() {
        assertEquals("Привет!", ThinkFilter.strip("<think>Пользователь здоровается…</think>\n\nПривет!"))
        assertEquals("Ответ", ThinkFilter.strip("рассуждения без открывающего тега</think>Ответ"))
        assertEquals("", ThinkFilter.strip("<think>не дописала"))
        assertEquals(Emotion.HAPPY, ReplyParser.parse("<think>hmm</think>[happy] Мрр!").emotion)

        val f = ThinkFilter()
        val out = listOf("<th", "ink>секрет", "ные мысли</thi", "nk>[happy] При", "вет!").joinToString("") { f.feed(it) } + f.finish()
        assertEquals("[happy] Привет!", out)

        val said = mutableListOf<String>()
        var emotion: Emotion? = null
        val s = ReplyStream(object : ReplySink {
            override fun emotion(e: Emotion) { emotion = e }
            override fun sentence(text: String, emotion: Emotion) { said += text }
        })
        listOf("<think>", "Надо ответить коротко.", "</think>", "[excited] Ура! Получилось.").forEach(s::feed)
        s.finish()
        assertEquals(Emotion.EXCITED, emotion)
        assertEquals("Ура! Получилось.", said.joinToString(" "))
    }

    @Test
    fun errorKinds() {
        assertEquals(LlmException.Kind.NO_TOOLS, Http.kindFor(404, """{"error":{"message":"No endpoints found that support tool use."}}"""))
        assertEquals(LlmException.Kind.MODEL, Http.kindFor(404, """{"error":{"message":"No endpoints found for x/y:free."}}"""))
    }

    @Test
    fun standInsComeFromOtherVendors() {
        val models = listOf(
            FreeModel("deepseek/deepseek-chat-v3:free", "DeepSeek V3", 64_000, true),
            FreeModel("deepseek/deepseek-chat:free", "DeepSeek", 64_000, true),
            FreeModel("qwen/qwen3-235b-a22b:free", "Qwen3", 40_000, true),
            FreeModel("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3", 128_000, true),
            FreeModel("deepseek/deepseek-r1:free", "R1", 64_000, true),
        )
        val first = OpenRouterConnect.pick(models)!!.id
        assertEquals("deepseek/deepseek-chat-v3:free", first)
        assertEquals(listOf("qwen/qwen3-235b-a22b:free", "meta-llama/llama-3.3-70b-instruct:free"), OpenRouterConnect.standIns(models, first))
    }

    private fun agent(provider: LlmProvider) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { provider }, random = Random(1),
    )

    @Test
    fun modelWithoutToolsStillAnswers() = runTest {
        val p = Scripted("p", mutableListOf(fail(LlmException.Kind.NO_TOOLS), ok("[happy] Привет без инструментов"), ok("[happy] И снова")))
        val a = agent(p)
        assertEquals("Привет без инструментов", a.handle("расскажи что-нибудь").text)
        assertTrue(p.requests[0].tools.isNotEmpty())
        assertTrue(p.requests[1].tools.isEmpty())
        a.handle("ещё")
        assertTrue(p.requests[2].tools.isEmpty())   // remembered: no failing round trip again
    }

    @Test
    fun smallTalkSurvivesACloudFailure() = withLang(Lang.RU) {
        runTest {
            val r = agent(Scripted("p", mutableListOf(fail(LlmException.Kind.NETWORK)))).handle("привет")
            assertNull(r.error)
            assertEquals(LlmException.Kind.NETWORK, r.errorKind)
            assertTrue(r.text.isNotBlank() && !r.text.contains("интернет"), r.text)
        }
    }
}
