package ru.iskra.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636, S256): the app proves it started the login without ever holding a password or a client secret. */
object Pkce {
    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** A free model on OpenRouter and whether it can call tools (Iskra's phone actions need them). */
data class FreeModel(val id: String, val name: String, val contextLength: Int, val tools: Boolean)

/**
 * "Connect for free": the owner signs in on openrouter.ai (Google or e-mail, free), OpenRouter hands the
 * app a personal key through PKCE, and Iskra uses a free model. No key to copy; the key is the owner's
 * and can be revoked on openrouter.ai/keys at any time.
 */
class OpenRouterConnect(
    private val client: OkHttpClient = Http.defaultClient,
    private val base: String = "https://openrouter.ai",
) {
    /** Where the owner signs in. Without [callbackUrl] OpenRouter shows a code to paste into the app. */
    fun authUrl(challenge: String, callbackUrl: String?): String = buildString {
        append(base.trimEnd('/')).append("/auth?")
        if (callbackUrl != null) append("callback_url=").append(URLEncoder.encode(callbackUrl, "UTF-8")).append('&')
        append("code_challenge=").append(challenge).append("&code_challenge_method=S256")
    }

    /** The code from the callback (or pasted by the owner) → the owner's API key. */
    suspend fun exchange(code: String, verifier: String): String {
        val body = buildJsonObject {
            put("code", code.trim())
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody(JSON)
        val (status, text) = Http.execute(client, Request.Builder().url(base.trimEnd('/') + "/api/v1/auth/keys").post(body).build())
        Http.checkStatus(status, text)
        val key = (Json.parseToJsonElement(text).jsonObject["key"] as? JsonPrimitive)?.contentOrNull
        if (key.isNullOrBlank()) throw LlmException("No key in the answer", LlmException.Kind.BAD_RESPONSE)
        return key
    }

    /** Free models right now (the list changes often, so it is asked every time instead of hard-coding one). */
    suspend fun freeModels(): List<FreeModel> {
        val (status, text) = Http.execute(client, Request.Builder().url(base.trimEnd('/') + "/api/v1/models").get().build())
        Http.checkStatus(status, text)
        return parseFree(text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun parseFree(json: String): List<FreeModel> {
            val data = Json.parseToJsonElement(json).jsonObject["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val pricing = o["pricing"] as? JsonObject
                fun price(k: String) = (pricing?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val free = id.endsWith(":free") || (price("prompt") == 0.0 && price("completion") == 0.0)
                if (!free) return@mapNotNull null
                val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                FreeModel(
                    id = id,
                    name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: id,
                    contextLength = (o["context_length"] as? JsonPrimitive)?.intOrNull ?: 0,
                    tools = "tools" in params,
                )
            }
        }

        /** Families that speak Russian and English well and answer quickly; reasoning models are slow for chat. */
        private val preferred = listOf("deepseek-chat", "deepseek/deepseek-v3", "qwen3", "qwen-2.5-72b", "llama-3.3-70b", "gemma-3-27b",
            "mistral-small", "gpt-oss", "glm", "kimi", "llama", "gemma", "qwen", "mistral", "deepseek")
        private val slow = Regex("""(-r1|r1-|:thinking|thinking|reason|distill|vision|-vl|coder|math|embed|guard)""")

        /** The best free model for a companion: tool calling, a known good family, enough context. */
        fun pick(models: List<FreeModel>): FreeModel? = ranked(models).firstOrNull()

        /**
         * Free models from best to worst for a companion. The runners-up become quiet stand-ins when the first is
         * busy — preferably from other families, which run on other servers.
         */
        fun ranked(models: List<FreeModel>): List<FreeModel> {
            fun rank(m: FreeModel): Int = preferred.indexOfFirst { m.id.lowercase().contains(it) }.let { if (it < 0) preferred.size else it }
            val usable = models.filter { it.contextLength == 0 || it.contextLength >= 16_000 }
            val fast = usable.filter { !slow.containsMatchIn(it.id.lowercase()) }.ifEmpty { usable }
            return fast.sortedWith(compareBy<FreeModel>({ !it.tools }, { rank(it) }, { -it.contextLength }))
        }

        /** [count] stand-ins for [primary]: the best of other vendors first ("qwen/…" after "deepseek/…"). */
        fun standIns(models: List<FreeModel>, primary: String, count: Int = 2): List<String> {
            val rest = ranked(models).filter { it.id != primary }
            val vendor = { id: String -> id.substringBefore('/') }
            val otherVendors = rest.filter { vendor(it.id) != vendor(primary) }.distinctBy { vendor(it.id) }
            return (otherVendors + rest).map { it.id }.distinct().take(count)
        }
    }
}

/**
 * The browser comes back to http://localhost:<port>/callback?code=… after the sign-in; this tiny server on
 * the phone's own loopback address reads the code and shows a "you can go back" page. Nothing outside the
 * phone can reach it (bound to 127.0.0.1), and it answers one sign-in only.
 */
class LoopbackCallback(private val donePage: (ok: Boolean) -> String) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val callbackUrl: String get() = "http://localhost:$port/callback"

    /** Waits for the browser; returns the code, or null on timeout, cancel ([close]) or an error from OpenRouter. */
    suspend fun awaitCode(timeoutMs: Long = 10 * 60_000L): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!server.isClosed && System.currentTimeMillis() < deadline) {
            server.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(1, 60_000).toInt()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { return@withContext null }
            socket.use { s ->
                s.soTimeout = 10_000
                val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                val query = Regex("""^GET /callback\?(\S*) HTTP""").find(line)?.groupValues?.get(1)
                val params = query?.split('&')?.mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
                }?.toMap()
                val code = params?.get("code")?.takeIf { it.isNotBlank() }
                val page = if (query != null) donePage(code != null) else "Iskra"
                val bytes = page.toByteArray(Charsets.UTF_8)
                runCatching {
                    s.getOutputStream().apply {
                        write(("HTTP/1.1 ${if (query != null) 200 else 404} OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes); flush()
                    }
                }
                if (query != null) return@withContext code
            }
        }
        null
    }

    override fun close() { runCatching { server.close() } }
}
