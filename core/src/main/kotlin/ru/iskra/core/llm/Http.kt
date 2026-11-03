package ru.iskra.core.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object Http {
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Executes the call, cancelling the HTTP request if the coroutine is cancelled. */
    suspend fun execute(client: OkHttpClient, request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(
                        LlmException("Network error: ${e.javaClass.simpleName}", LlmException.Kind.NETWORK, e)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = try { it.body?.string().orEmpty() } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(
                                LlmException("Read error", LlmException.Kind.NETWORK, e)
                            )
                            return
                        }
                        if (cont.isActive) cont.resume(it.code to body)
                    }
                }
            })
        }

    /**
     * Executes a streaming request and hands every line of the response body to [onLine] (on OkHttp's
     * thread) as soon as it arrives. Non-2xx responses are read whole and reported via [checkStatus].
     */
    suspend fun streamLines(client: OkHttpClient, request: Request, onLine: (String) -> Unit): Unit =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(
                        LlmException("Network error: ${e.javaClass.simpleName}", LlmException.Kind.NETWORK, e)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (it.code !in 200..299) checkStatus(it.code, it.body?.string().orEmpty())
                            val source = it.body?.source() ?: throw LlmException("Empty body", LlmException.Kind.BAD_RESPONSE)
                            while (cont.isActive) {
                                val line = source.readUtf8Line() ?: break
                                onLine(line)
                            }
                            if (cont.isActive) cont.resume(Unit)
                        } catch (e: LlmException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(LlmException("Read error", LlmException.Kind.NETWORK, e))
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private val balanceHints = Regex("insufficient_quota|quota_exceeded|insufficient[_ ]credits|insufficient[_ ]funds|credit balance|balance is too low|payment required", RegexOption.IGNORE_CASE)

    fun kindFor(code: Int, body: String): LlmException.Kind = when {
        code == 402 || (code in 400..499 && balanceHints.containsMatchIn(body)) -> LlmException.Kind.BALANCE
        code == 401 || code == 403 -> LlmException.Kind.AUTH
        code == 429 -> LlmException.Kind.RATE_LIMIT
        code in 500..599 -> LlmException.Kind.SERVER
        else -> LlmException.Kind.BAD_RESPONSE
    }

    fun checkStatus(code: Int, body: String) {
        if (code in 200..299) return
        // Body is truncated and never includes request headers, so keys cannot leak here.
        val snippet = body.take(300)
        val kind = kindFor(code, body)
        throw LlmException("HTTP $code: $snippet", kind)
    }
}
