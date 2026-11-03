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
        assertEquals("sys", body["system"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        // user, assistant(tool_use), user(tool_result)
        assertEquals(3, msgs.size)
        assertEquals("tool_result", msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_schema", body["tools"]!!.jsonArray[0].jsonObject.keys.first { it == "input_schema" })
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
