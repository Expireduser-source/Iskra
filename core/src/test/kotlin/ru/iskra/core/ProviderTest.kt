package ru.iskra.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.llm.AnthropicProvider
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.OpenAiCompatibleProvider
import ru.iskra.core.llm.ToolCall
import ru.iskra.core.tools.ToolSchemas
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProviderTest {
    private val server = MockWebServer().apply { start() }
    @AfterTest fun stop() = server.shutdown()

    private val req = LlmRequest(
        system = "sys",
        messages = listOf(
            ChatMessage.User("поставь таймер"),
            ChatMessage.Assistant("", listOf(ToolCall("t1", "set_timer", buildJsonObject { put("seconds", 60) }))),
            ChatMessage.ToolResult("t1", "set_timer", "OK"),
        ),
        tools = listOf(ToolSchemas.setTimer),
    )

    @Test fun openAiRoundTrip() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"choices":[{"message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"x","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\":120}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}
        """.trimIndent()))
        val p = OpenAiCompatibleProvider(server.url("/v1").toString(), "sk-test", "m")
        val r = p.complete(req)
        assertEquals("set_timer", r.toolCalls.single().name)
        assertEquals("120", r.toolCalls.single().arguments["seconds"]!!.jsonPrimitive.content)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val msgs = body["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("tool", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function", body["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicRoundTrip() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"content":[{"type":"text","text":"[happy] Готово"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":12,"output_tokens":3}}
        """.trimIndent()))
        val p = AnthropicProvider("key", "claude-haiku-5-5", baseUrl = server.url("/").toString())
        val r = p.complete(req)
        assertEquals("[happy] Готово", r.text)
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("key", recorded.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        // The stable instructions are a cached block; no sampling parameter (Claude Haiku 5.5 answers one with a 400).
        val system = body["system"]!!.jsonArray
        assertEquals("sys", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1, system.size)
        assertFalse("temperature" in body)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        // user, assistant(tool_use), user(tool_result)
        assertEquals(3, msgs.size)
        assertEquals("tool_result", msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_schema", body["tools"]!!.jsonArray[0].jsonObject.keys.first { it == "input_schema" })
    }

    @Test fun eachClaudeGenerationGetsWhatItAccepts() {
        fun body(model: String) = AnthropicProvider("key", model).buildBody(req.copy(situation = "Сейчас 15:00"))
        assertEquals("between_tools", body("claude-sonnet-5-5")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", body("claude-opus-5")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", body("claude-sonnet-5")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Can't be turned off: low effort instead.
        val opus = body("claude-opus-5-5")
        assertFalse("thinking" in opus)
        assertEquals("low", opus["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Older models don't think unless asked; nothing is sent.
        for (m in listOf("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-8")) {
            assertFalse("thinking" in body(m), m)
            assertFalse("output_config" in body(m), m)
        }
        // The situation follows the cached instructions, uncached.
        val system = body("claude-haiku-5-5")["system"]!!.jsonArray
        assertEquals("Сейчас 15:00", system[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse("cache_control" in system[1].jsonObject)
        for (m in listOf("claude-haiku-5-5", "claude-opus-5-5", "claude-3-5-haiku-latest")) assertFalse("temperature" in body(m), m)
    }

    /** Thinking blocks before a tool call go back unchanged; cached input is counted as it is paid for. */
    @Test fun thinkingBlocksAndCachedInput() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                        {"type":"tool_use","id":"t2","name":"set_timer","input":{"seconds":30}}],
             "stop_reason":"tool_use",
             "usage":{"input_tokens":100,"cache_creation_input_tokens":2000,"cache_read_input_tokens":0,"output_tokens":20}}
        """.trimIndent()))
        val p = AnthropicProvider("key", "claude-opus-5-5", baseUrl = server.url("/").toString())
        val r = p.complete(req)
        assertEquals(100 + 2500, r.inputTokens)
        assertEquals("sig-1", r.providerBlocks.single()["signature"]!!.jsonPrimitive.content)
        server.takeRequest()
        // The next round sends the thinking block back before the tool call.
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"[happy] Готово"}],
            "usage":{"input_tokens":10,"cache_read_input_tokens":2000,"output_tokens":3}}"""))
        val next = req.copy(messages = listOf(ChatMessage.User("таймер на 30 секунд"),
            ChatMessage.Assistant(r.text, r.toolCalls, r.providerBlocks), ChatMessage.ToolResult("t2", "set_timer", "OK")))
        assertEquals(10 + 200, p.complete(next).inputTokens)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = sent["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "tool_use"), assistant.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test fun errorsAreClassifiedAndDoNotLeakKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid key"}"""))
        val e = assertFailsWith<LlmException> {
            OpenAiCompatibleProvider(server.url("/").toString(), "sk-secret-123", "m").complete(req)
        }
        assertEquals(LlmException.Kind.AUTH, e.kind)
        assertFalse(e.message!!.contains("sk-secret-123"))
    }

    @Test fun missingKeyIsNotConfigured() = runTest {
        val e = assertFailsWith<LlmException> { OpenAiCompatibleProvider("https://x", "", "m").complete(req) }
        assertEquals(LlmException.Kind.NOT_CONFIGURED, e.kind)
    }
}
