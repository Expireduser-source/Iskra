package ru.iskra.core

import org.junit.Assume.assumeTrue
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.detect
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
        // The real file's header: the chat template is read past ~150k vocabulary strings.
        assertEquals(ChatFormat.CHATML, ChatFormat.detect(ru.iskra.core.llm.local.Gguf.chatTemplate(java.io.File(model!!))))
        assertNull(NativeLlamaEngine.open("/nonexistent.gguf", 512, 2))
        val e = NativeLlamaEngine.open(model, 512, 2)!!
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

    /**
     * A hybrid model (Qwen3.5's architecture: gated delta net + attention; ISKRA_LLAMA_HYBRID, a tiny one with random
     * weights) can't cut its state back. A checkpoint where the prompt stays the same keeps the dialogue cached anyway,
     * and the answers are the same as when everything is read anew.
     */
    @Test
    fun hybridModelKeepsTheDialogueThroughACheckpoint() {
        val hybrid = System.getenv("ISKRA_LLAMA_HYBRID")
        assumeTrue("ISKRA_LLAMA_LIB / ISKRA_LLAMA_HYBRID not set", lib != null && hybrid != null)
        System.load(lib!!)
        val f = ChatFormat.CHATML_NO_THINK
        val system = "Ты — Искра, игривая и заботливая кошкодевочка, которая живёт в этом телефоне. Тебя зовут Искра. " +
            "Ты девушка: о себе говори в женском роде. Отвечай тепло, живо и коротко, на языке собеседника. " +
            "Ты программа с ИИ и честно говоришь об этом. Не знаешь — так и скажи, не выдумывай факты."
        var request = LlmRequest(system, listOf(ChatMessage.User("Привет! Меня зовут Аня.")), situation = "Ты помнишь: Аня учится на врача.")
        val prompts = ArrayList<ru.iskra.core.llm.local.Prompt>()
        val answers = ArrayList<String>()
        val reads = ArrayList<Pair<Int, Int>>()
        NativeLlamaEngine.open(hybrid!!, 1024, 2)!!.use { e ->
            for (next in listOf("Как дела?", "Расскажи сказку.", "Спасибо!")) {
                val p = f.prompt(request)
                prompts += p
                answers += e.generate(p.text, 12, 0f, p.stable) { true }
                reads += e.lastRead()
                request = request.copy(messages = request.messages + ChatMessage.Assistant(answers.last().ifBlank { "мрр" }) + ChatMessage.User(next), situation = "")
            }
        }
        println("reads (prompt tokens, reused): $reads")
        assertEquals(0, reads[0].second)
        // Later messages reuse what was there up to the newest message: everything but the last exchange.
        for (i in 1 until reads.size) {
            val (n, reused) = reads[i]
            assertTrue(reused > n / 2 && n - reused < 80, "message ${i + 1}: only $reused of $n tokens reused")
        }
        // Without a checkpoint the state can't go back past the answer header: everything is read again.
        NativeLlamaEngine.open(hybrid, 1024, 2)!!.use { e ->
            e.generate(prompts[1].text, 4, 0f, 0) { true }
            e.generate(prompts[2].text, 4, 0f, 0) { true }
            assertEquals(0, e.lastRead().second)
        }
        // The same answers as reading each prompt from scratch.
        for ((i, p) in prompts.withIndex()) {
            val fresh = NativeLlamaEngine.open(hybrid, 1024, 2)!!.use { it.generate(p.text, 12, 0f, 0) { true } }
            assertEquals(fresh, answers[i], "message ${i + 1}")
        }
    }
}
