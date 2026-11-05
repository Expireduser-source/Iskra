package ru.iskra.core

import org.junit.Assume.assumeTrue
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.NativeLlamaEngine
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real llama.cpp through the JNI bridge, on this computer. Runs when ISKRA_LLAMA_LIB points to a host build of
 * app/src/main/cpp (libiskra_llama.so) and ISKRA_LLAMA_MODEL to a GGUF model; skipped otherwise.
 */
class NativeLlamaTest {
    private val lib: String? = System.getenv("ISKRA_LLAMA_LIB")
    private val model: String? = System.getenv("ISKRA_LLAMA_MODEL")

    @Test
    fun llamaCppThroughTheBridge() {
        assumeTrue("ISKRA_LLAMA_LIB / ISKRA_LLAMA_MODEL not set", lib != null && model != null)
        System.load(lib!!)
        assertNull(NativeLlamaEngine.open("/nonexistent.gguf", 512, 2))
        val e = NativeLlamaEngine.open(model!!, 512, 2)!!
        e.use {
            assertTrue(e.info.contains("ctx 512"), e.info)
            val prompt = ChatFormat.CHATML.render(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет! Как дела? 🐱"))))
            val pieces = mutableListOf<String>()
            val t0 = System.nanoTime()
            val out = e.generate(prompt, 24, 0.8f) { pieces += it; true }
            val firstMs = (System.nanoTime() - t0) / 1e6
            assertEquals(out, pieces.joinToString(""))
            println("first: ${pieces.size} pieces, %.0f ms: ${out.take(60)}".format(firstMs))

            // The next turn starts with the previous prompt and reply: only the new part is computed.
            val prompt2 = prompt + out + "<|im_end|>\n<|im_start|>user\nЕщё!<|im_end|>\n<|im_start|>assistant\n"
            val t1 = System.nanoTime()
            e.generate(prompt2, 1, 0.8f) { true }
            println("second turn (cached prefix): %.0f ms".format((System.nanoTime() - t1) / 1e6))

            var n = 0
            e.generate(prompt2, 50, 0.8f) { n++; n < 3 }
            assertEquals(3, n)   // the sink said stop

            // Cancel from another thread while generating.
            var got = 0
            val worker = thread { e.generate(prompt2, 400, 0.8f) { got++; Thread.sleep(2); true } }
            Thread.sleep(150)
            e.cancel()
            worker.join(5_000)
            assertTrue(!worker.isAlive && got < 400, "cancel stopped it after $got pieces")

            // Too long for the context: a clear error, not a crash.
            assertFailsWith<IllegalArgumentException> { e.generate("слово ".repeat(600), 32, 0.8f) { true } }
            // And it still works afterwards, from scratch.
            assertTrue(e.generate(prompt, 4, 0.8f) { true }.isNotEmpty())
        }
    }
}
