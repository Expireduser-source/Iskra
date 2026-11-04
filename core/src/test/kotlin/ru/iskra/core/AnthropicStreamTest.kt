package ru.iskra.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.llm.AnthropicProvider
import ru.iskra.core.llm.AnthropicStreamParser
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 0.9.1: Anthropic replies stream sentence by sentence like the OpenAI-compatible ones. */
class AnthropicStreamTest {
    private val server = MockWebServer().apply { start() }
    private val req = LlmRequest(system = "s", messages = listOf(ChatMessage.User("привет")))

    @AfterTest fun stop() = server.shutdown()

    private fun sse(vararg events: Pair<String, String>) =
        events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }

    @Test fun textDeltasArriveAsTheyCome() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":42,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "ping" to """{"type":"ping"}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"[happy] Мрр! "}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Привет."}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )))
        val pieces = mutableListOf<String>()
        val r = AnthropicProvider("key", "claude-haiku-5-5", baseUrl = server.url("/").toString()).stream(req) { pieces += it }
        assertEquals(listOf("[happy] Мрр! ", "Привет."), pieces)
        assertEquals("[happy] Мрр! Привет.", r.text)
        assertEquals(42, r.inputTokens); assertEquals(9, r.outputTokens)
        val sent = server.takeRequest()
        assertTrue(sent.body.readUtf8().contains("\"stream\":true"))
        assertEquals("key", sent.getHeader("x-api-key"))
    }

    @Test fun toolUseInputIsAssembledFromJsonDeltas() {
        val p = AnthropicStreamParser()
        listOf(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Ставлю."}}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"set_timer","input":{}}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"minu"}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"tes\": 5}"}}""",
            """data: {"type":"content_block_stop","index":1}""",
            """data: {"type":"message_stop"}""",
        ).forEach { p.onLine(it) }
        val r = p.result()
        assertEquals("Ставлю.", r.text)
        assertEquals(1, r.toolCalls.size)
        assertEquals("toolu_1", r.toolCalls[0].id)
        assertEquals("set_timer", r.toolCalls[0].name)
        assertEquals("5", r.toolCalls[0].arguments["minutes"]!!.jsonPrimitive.content)
        assertTrue(p.done)
    }

    @Test fun errorEventsAreClassified() {
        val overloaded = assertFailsWith<LlmException> {
            AnthropicStreamParser().onLine("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals(LlmException.Kind.SERVER, overloaded.kind)
        val auth = assertFailsWith<LlmException> {
            AnthropicStreamParser().onLine("""data: {"type":"error","error":{"type":"authentication_error","message":"bad key"}}""")
        }
        assertEquals(LlmException.Kind.AUTH, auth.kind)
    }

    @Test fun lowCreditBeforeTheStreamIsBalance() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the API."}}"""))
        val e = assertFailsWith<LlmException> {
            AnthropicProvider("key", "m", baseUrl = server.url("/").toString()).stream(req) {}
        }
        assertEquals(LlmException.Kind.BALANCE, e.kind)
    }

    @Test fun plainJsonAnswerStillWorks() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Целиком."}],"usage":{"input_tokens":3,"output_tokens":2}}"""))
        val pieces = mutableListOf<String>()
        val r = AnthropicProvider("key", "m", baseUrl = server.url("/").toString()).stream(req) { pieces += it }
        assertEquals(listOf("Целиком."), pieces)
        assertEquals(2, r.outputTokens)
    }
}
