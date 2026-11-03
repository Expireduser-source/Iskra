package ru.iskra.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.llm.OpenAiCompatibleProvider
import ru.iskra.core.llm.OpenAiStreamParser
import ru.iskra.core.llm.ToolCall
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.voice.EnergyVad
import ru.iskra.core.voice.PcmCache
import ru.iskra.core.voice.PcmFormat
import ru.iskra.core.voice.ReplySink
import ru.iskra.core.voice.ReplyStream
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Collect : ReplySink {
    val emotions = mutableListOf<Emotion>()
    val parts = mutableListOf<String>()
    override fun emotion(e: Emotion) { emotions += e }
    override fun sentence(text: String, emotion: Emotion) { parts += text }
}

class ReplyStreamTest {
    private fun run(vararg deltas: String): Collect {
        val c = Collect(); val s = ReplyStream(c)
        deltas.forEach(s::feed); s.finish(); return c
    }

    @Test fun firstSentenceGoesOutBeforeTheReplyEnds() {
        val c = Collect(); val s = ReplyStream(c)
        "[happy] Привет! Я тут, рядом. Как прошёл день у тебя сегодня, расскажешь мне?".chunked(3).forEach(s::feed)
        assertEquals(listOf(Emotion.HAPPY), c.emotions)
        assertEquals("Привет!", c.parts.first(), "first chunk is out while the model is still writing")
        s.finish()
        assertEquals("Привет! Я тут, рядом. Как прошёл день у тебя сегодня, расскажешь мне?", c.parts.joinToString(" "))
    }

    @Test fun tagSplitAcrossDeltasIsStripped() {
        val c = run("[sa", "d] Ой", ", жаль.")
        assertEquals(listOf(Emotion.SAD), c.emotions)
        assertEquals(listOf("Ой, жаль."), c.parts)
    }

    @Test fun doesNotCutAtAbbreviationsNumbersAndInitials() {
        val c = run("Ок. Встреча в 7.30, т.е. через час, ул. Ленина, А. С. Пушкин рядом.\n1. Чай\n2. Хлеб")
        assertEquals("Ок.", c.parts[0])
        assertTrue(c.parts.any { it.contains("т.е. через час, ул. Ленина, А. С. Пушкин рядом.") }, c.parts.toString())
        assertTrue(c.parts.none { it == "1." || it == "т.е." || it == "А." }, c.parts.toString())
    }

    @Test fun laterSentencesAreMergedAndLongRunsCut() {
        val c = run("Да. Раз. Два. Три. Четыре. Пять. Шесть. Семь. Восемь. Девять. Десять. Одиннадцать. Двенадцать.")
        assertEquals("Да.", c.parts[0])
        assertTrue(c.parts.size in 2..3, c.parts.toString())
        val long = run("слово ".repeat(80))
        assertTrue(long.parts.size >= 2 && long.parts.all { it.length <= 220 }, long.parts.map { it.length }.toString())
    }

    @Test fun emojiAndActionsAreNotSpoken() {
        val c = run("[shy] *мурлычет* Спасибо 😊 ")
        assertEquals(listOf("Спасибо"), c.parts)
        assertEquals(listOf("Ну и ну.", "Ага!"), ReplyStream.split("Ну и ну. Ага!", Emotion.NEUTRAL).let { listOf(it[0], "Ага!") })
    }
}

class OpenAiStreamTest {
    private val server = MockWebServer().apply { start() }
    @AfterTest fun stop() = server.shutdown()
    private fun provider() = OpenAiCompatibleProvider(server.url("/v1").toString(), "sk-test", "m")
    private val req = LlmRequest("sys", listOf(ChatMessage.User("привет")))

    @Test fun parserAssemblesTextAndToolCallFragments() {
        val p = OpenAiStreamParser()
        val lines = listOf(
            ": OPENROUTER PROCESSING",
            """data: {"choices":[{"delta":{"role":"assistant","content":"[happy] "}}]}""",
            """data: {"choices":[{"delta":{"content":"Ставлю"}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"set_timer","arguments":"{\"sec"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"onds\":60}"}}]}}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5}}""",
            "data: [DONE]",
        )
        val text = lines.mapNotNull(p::onLine)
        assertEquals(listOf("[happy] ", "Ставлю"), text)
        val r = p.result()
        assertEquals("[happy] Ставлю", r.text)
        assertEquals(ToolCall("c1", "set_timer", kotlinx.serialization.json.buildJsonObject { put("seconds", kotlinx.serialization.json.JsonPrimitive(60)) }), r.toolCalls.single())
        assertEquals(12, r.inputTokens)
    }

    @Test fun streamsOverHttpAndAsksForEvents() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Привет! \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Как дела?\"}}]}\n\ndata: [DONE]\n\n"))
        val got = mutableListOf<String>()
        val r = provider().stream(req) { got += it }
        assertEquals(listOf("Привет! ", "Как дела?"), got)
        assertEquals("Привет! Как дела?", r.text)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"stream\":true"), body)
    }

    @Test fun serverWithoutStreamingStillWorks() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Целиком."}}]}"""))
        val got = mutableListOf<String>()
        assertEquals("Целиком.", provider().stream(req) { got += it }.text)
        assertEquals(listOf("Целиком."), got)
    }

    @Test fun emptyBalanceAndQuotaAreRecognised() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient credits"}}"""))
        val e = assertFailsWith<LlmException> { provider().stream(req) { } }
        assertEquals(LlmException.Kind.BALANCE, e.kind)
        assertTrue(e.persistent && e.userMessage().contains("офлайн") || e.userMessage().contains("без облака"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(LlmException.Kind.BALANCE, assertFailsWith<LlmException> { provider().complete(req) }.kind)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        assertEquals(LlmException.Kind.RATE_LIMIT, assertFailsWith<LlmException> { provider().complete(req) }.kind)
    }

    @Test fun errorInsideTheStreamIsReported() = runTest {
        server.enqueue(MockResponse().setBody("data: {\"error\":{\"code\":402,\"message\":\"no credits\"}}\n\n"))
        assertEquals(LlmException.Kind.BALANCE, assertFailsWith<LlmException> { provider().stream(req) { } }.kind)
    }
}

class StreamingAgentTest {
    private class StreamingFake(private val rounds: MutableList<LlmResponse>) : LlmProvider {
        override val id = "fake-stream"
        override suspend fun complete(request: LlmRequest) = rounds.removeAt(0)
        override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): LlmResponse {
            val r = rounds.removeAt(0)
            r.text.chunked(4).forEach(onText)
            return r
        }
    }

    @Test fun agentSpeaksWhileStreamingAndShowsWhatWasSaid() = runTest {
        val provider = StreamingFake(mutableListOf(
            LlmResponse("[happy] Сейчас поставлю. ", listOf(ToolCall("c1", "set_timer", kotlinx.serialization.json.buildJsonObject { put("seconds", kotlinx.serialization.json.JsonPrimitive(60)) }))),
            LlmResponse("Готово, таймер на минуту запущен!", emptyList()),
        ))
        val agent = CompanionAgent(
            Personas.ISKRA, MemoryManager(InMemoryMemoryStore()), InMemoryConversationStore(), InMemoryStateStore(),
            ToolRegistry(listOf(RecordingTool())), SafetyPolicy(gate = { _, _ -> true }), { provider }, random = Random(0),
        )
        val sink = Collect()
        val r = agent.handle("засеки минуту на чай", sink)
        assertEquals(listOf("Сейчас поставлю.", "Готово, таймер на минуту запущен!"), sink.parts)
        assertEquals("Сейчас поставлю. Готово, таймер на минуту запущен!", r.text)
        assertEquals(Emotion.HAPPY, r.emotion)
        assertTrue(r.usedLlm && r.actions.single().outcome.ok)
    }
}

class PcmCacheTest {
    private val dir = Files.createTempDirectory("pcm").toFile()

    @Test fun roundTripAndKeyHidesText() {
        val c = PcmCache(dir)
        val k = c.key("ElevenLabs", "v1", "m", "happy", "Готово!")
        assertTrue(k.matches(Regex("[0-9a-f]{32}")))
        assertEquals(k, c.key("ElevenLabs", "v1", "m", "happy", "  готово! "))
        assertNull(c.get(k))
        c.put(k, PcmFormat(24000), ByteArray(4800) { it.toByte() })
        val (fmt, data) = c.get(k)!!
        assertEquals(24000, fmt.sampleRate); assertEquals(4800, data.size); assertEquals(7, data[7].toInt())
        assertTrue(dir.listFiles()!!.none { it.name.contains("Готово") })
    }

    @Test fun evictsOldestAboveLimitAndDropsBrokenFiles() {
        val c = PcmCache(dir, maxBytes = 25_000)
        repeat(4) { i -> c.put("k$i", PcmFormat(16000), ByteArray(10_000)); java.io.File(dir, "k$i.pcm").setLastModified(1_000L * i) }
        c.put("k9", PcmFormat(16000), ByteArray(10_000))
        assertTrue(c.sizeBytes() <= 25_000)
        assertNull(c.get("k0")); assertTrue(c.get("k9") != null)
        java.io.File(dir, "bad.pcm").writeText("garbage")
        assertNull(c.get("bad")); assertTrue(!java.io.File(dir, "bad.pcm").exists())
        assertTrue(!PcmCache.cacheable("x".repeat(200)) && PcmCache.cacheable("Таймер поставлен."))
    }
}

class EnergyVadTest {
    @Test fun silenceSkipsSpeechWakesWithHangover() {
        val v = EnergyVad()
        repeat(50) { assertEquals(EnergyVad.Decision.SILENCE, v.update(120.0 + it % 3 * 10)) }
        assertEquals(EnergyVad.Decision.START, v.update(2500.0))
        assertEquals(EnergyVad.Decision.SPEECH, v.update(1800.0))
        val after = (0 until 12).map { v.update(110.0) }
        assertEquals(9, after.count { it == EnergyVad.Decision.SPEECH }, after.toString()) // 900 ms hangover
        assertTrue(EnergyVad.Decision.END in after)
        assertEquals(4, v.preRollChunks)
    }

    @Test fun steadyNoiseRaisesTheFloorSoItIsNotSpeech() {
        val v = EnergyVad()
        val decisions = (0 until 600).map { v.update(if (it < 300) 150.0 else 600.0) } // a fan switched on
        val late = decisions.takeLast(100)
        assertTrue(late.all { it == EnergyVad.Decision.SILENCE }, "floor ${v.noiseFloor}")
        assertEquals(EnergyVad.Decision.START, v.update(4000.0))
        assertEquals(0.0, EnergyVad.rms(ShortArray(10), 10))
    }
}

class CloudPauseTest {
    @Test fun afterBalanceErrorTheCloudIsSkippedUntilResumed() = runTest {
        var calls = 0
        val provider = object : LlmProvider {
            override val id = "broke"
            override suspend fun complete(request: LlmRequest): LlmResponse {
                calls++; throw LlmException("HTTP 402", LlmException.Kind.BALANCE)
            }
        }
        var now = 1_000_000L
        val agent = CompanionAgent(
            Personas.ISKRA, MemoryManager(InMemoryMemoryStore()), InMemoryConversationStore(), InMemoryStateStore(),
            ToolRegistry(emptyList()), SafetyPolicy(gate = { _, _ -> true }), { provider }, clock = { now }, random = Random(0),
        )
        val first = agent.handle("расскажи сказку")
        assertEquals(LlmException.Kind.BALANCE, first.errorKind)
        val second = agent.handle("ну расскажи")
        assertEquals(1, calls, "no second request while paused")
        assertTrue(second.text.contains("закончились деньги"), second.text)
        now += 6 * 60_000L
        agent.handle("а теперь?")
        assertEquals(2, calls)
        agent.resumeCloud(); agent.handle("ещё раз")
        assertEquals(3, calls)
    }
}
