package ru.iskra.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.llm.FreeModel
import ru.iskra.core.llm.Http
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LoopbackCallback
import ru.iskra.core.llm.OpenRouterConnect
import ru.iskra.core.llm.Pkce
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.10: "Connect for free" through OpenRouter's PKCE sign-in. */
class OpenRouterConnectTest {

    @Test fun pkceMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun authUrlWithAndWithoutCallback() {
        val c = OpenRouterConnect(base = "https://openrouter.ai")
        assertEquals("https://openrouter.ai/auth?callback_url=http%3A%2F%2Flocalhost%3A4242%2Fcallback&code_challenge=abc&code_challenge_method=S256",
            c.authUrl("abc", "http://localhost:4242/callback"))
        assertEquals("https://openrouter.ai/auth?code_challenge=abc&code_challenge_method=S256", c.authUrl("abc", null))
    }

    @Test fun codeIsExchangedForTheOwnersKey() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"key":"sk-or-v1-0123","user_id":"u1"}"""))
            val key = OpenRouterConnect(base = server.url("/").toString()).exchange(" code-1 ", "verifier-1")
            assertEquals("sk-or-v1-0123", key)
            val req = server.takeRequest()
            assertEquals("/api/v1/auth/keys", req.path)
            val body = req.body.readUtf8()
            assertTrue(""""code":"code-1"""" in body && """"code_verifier":"verifier-1"""" in body && """"code_challenge_method":"S256"""" in body, body)

            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid code"}}"""))
            assertFailsWith<LlmException> { OpenRouterConnect(base = server.url("/").toString()).exchange("x", "y") }
        } finally { server.shutdown() }
    }

    @Test fun picksAFastFreeModelThatCanUseTools() {
        val json = """{"data":[
            {"id":"openai/gpt-4.1","name":"GPT","context_length":1000000,"pricing":{"prompt":"0.000002","completion":"0.000008"},"supported_parameters":["tools"]},
            {"id":"deepseek/deepseek-r1:free","name":"R1","context_length":163840,"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
            {"id":"meta-llama/llama-3.3-70b-instruct:free","name":"Llama","context_length":131072,"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","temperature"]},
            {"id":"google/gemma-3-27b-it:free","name":"Gemma","context_length":96000,"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"]},
            {"id":"deepseek/deepseek-chat-v3-0324:free","name":"DeepSeek V3","context_length":163840,"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
            {"id":"tiny/model:free","name":"Tiny","context_length":4096,"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]}
        ]}"""
        val free = OpenRouterConnect.parseFree(json)
        assertEquals(5, free.size) // the paid one is skipped
        assertEquals("deepseek/deepseek-chat-v3-0324:free", OpenRouterConnect.pick(free)!!.id)
        // Without DeepSeek V3: Llama (tools) beats Gemma (no tools); the reasoning R1 is a last resort.
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", OpenRouterConnect.pick(free.filter { it.id != "deepseek/deepseek-chat-v3-0324:free" })!!.id)
        assertEquals("deepseek/deepseek-r1:free", OpenRouterConnect.pick(listOf(FreeModel("deepseek/deepseek-r1:free", "R1", 163840, true)))!!.id)
        assertNull(OpenRouterConnect.pick(emptyList()))
    }

    @Test fun theBrowserBringsTheCodeToThePhone() = runBlocking {
        LoopbackCallback { ok -> if (ok) "<h1>Done</h1>" else "<h1>Error</h1>" }.use { cb ->
            assertTrue(cb.callbackUrl.startsWith("http://localhost:"))
            // The server waits on another thread: this thread blocks on the sockets below.
            val code = async(kotlinx.coroutines.Dispatchers.IO) { cb.awaitCode(10_000) }
            // A browser may ask for the favicon first.
            Socket("127.0.0.1", cb.port).use { s -> s.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getInputStream().readBytes() }
            val page = Socket("127.0.0.1", cb.port).use { s ->
                s.getOutputStream().write("GET /callback?code=abc%2D123 HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                String(s.getInputStream().readBytes())
            }
            assertEquals("abc-123", code.await())
            assertTrue(page.startsWith("HTTP/1.1 200") && page.contains("<h1>Done</h1>"), page)
        }
    }

    @Test fun openRouterErrorsAreExplained() {
        assertEquals(LlmException.Kind.FREE_QUOTA, Http.kindFor(429, """{"error":{"message":"Rate limit exceeded: free-models-per-day. Add 10 credits"}}"""))
        assertEquals(LlmException.Kind.RATE_LIMIT, Http.kindFor(429, """{"error":{"message":"Rate limit exceeded: free-models-per-min"}}"""))
        assertEquals(LlmException.Kind.POLICY, Http.kindFor(404, """{"error":{"message":"No endpoints found matching your data policy (Free model publication)"}}"""))
        assertEquals(LlmException.Kind.MODEL, Http.kindFor(400, """{"error":{"message":"some/model:free is not a valid model ID"}}"""))
        assertTrue(LlmException("x", LlmException.Kind.FREE_QUOTA).persistent)
        assertTrue(LlmException("x", LlmException.Kind.FREE_QUOTA).userMessage().contains("Бесплатные сообщения"))
    }
}
