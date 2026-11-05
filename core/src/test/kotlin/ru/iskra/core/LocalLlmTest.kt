package ru.iskra.core

import kotlinx.coroutines.test.runTest
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.detect
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replays [pieces] as if a model generated them; records prompts. */
class FakeEngine(private vararg val pieces: String) : LlamaEngine {
    val prompts = mutableListOf<String>()
    override val info = "fake"
    override val contextSize = 4096
    override fun generate(prompt: String, maxTokens: Int, temperature: Float, onPiece: (String) -> Boolean): String {
        prompts += prompt
        val out = StringBuilder()
        for (p in pieces) { out.append(p); if (!onPiece(p)) break }
        return out.toString()
    }
    override fun cancel() = Unit
    override fun close() = Unit
}

class LocalLlmTest {
    private val request = LlmRequest(
        system = "Ты — Искра.",
        messages = listOf(ChatMessage.User("Привет"), ChatMessage.Assistant("Мрр, привет!"), ChatMessage.User("Как дела?")),
        situation = "Сейчас четверг.",
    )

    @Test
    fun chatTemplates() {
        assertEquals("<|im_start|>system\nТы — Искра.<|im_end|>\n<|im_start|>user\nПривет<|im_end|>\n<|im_start|>assistant\nМрр, привет!<|im_end|>\n" +
            "<|im_start|>system\nСейчас четверг.<|im_end|>\n<|im_start|>user\nКак дела?<|im_end|>\n<|im_start|>assistant\n", ChatFormat.CHATML.render(request))
        assertTrue(ChatFormat.CHATML_NO_THINK.render(request).endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertEquals("<start_of_turn>user\nТы — Искра.\n\nПривет<end_of_turn>\n<start_of_turn>model\nМрр, привет!<end_of_turn>\n" +
            "<start_of_turn>user\nСейчас четверг.\n\nКак дела?<end_of_turn>\n<start_of_turn>model\n", ChatFormat.GEMMA.render(request))
        assertEquals("<|turn>system\nТы — Искра.<turn|>\n<|turn>user\nПривет<turn|>\n<|turn>model\nМрр, привет!<turn|>\n" +
            "<|turn>user\nСейчас четверг.\n\nКак дела?<turn|>\n<|turn>model\n<|channel>thought\n<channel|>", ChatFormat.GEMMA4.render(request))
        // The part before the situation is the same as in the previous turn's prompt: the model's cache stays useful.
        val before = ChatFormat.CHATML.render(request.copy(messages = request.messages.take(1)))
        assertTrue(ChatFormat.CHATML.render(request).startsWith(before.substringBefore("<|im_start|>system\nСейчас")))
    }

    @Test
    fun providerStreamsWithoutThinkingAndStopsAtTheTurnEnd() = runTest {
        val engine = FakeEngine("<think>сейчас", " подумаю</think>", "Хорошо, ", "мрр!", "<|im_end|>", "<|im_start|>user\nлишнее")
        val p = LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test")
        val heard = StringBuilder()
        val r = p.stream(request) { heard.append(it) }
        assertEquals("Хорошо, мрр!", r.text)
        assertEquals("Хорошо, мрр!", heard.toString())
        assertTrue(r.toolCalls.isEmpty())
        assertTrue(p.onDevice)
    }

    @Test
    fun inventedTagsAndGemma4ThoughtsAreRemoved() = runTest {
        assertEquals("Мрр, всё хорошо.", ru.iskra.core.agent.ReplyParser.parse("[neutral] [mrр] Мрр, всё хорошо.").text)
        assertEquals("I love winter evenings.", ru.iskra.core.agent.ReplyParser.parse("[excited] [I love winter evenings.]").text)
        assertEquals("Hi Tom!", ru.iskra.core.agent.ReplyParser.parse("[neutral] [Mood: Curious] Hi Tom!").text)
        val g = LocalLlmProvider({ FakeEngine("Пользователь грустит, надо утешить.", "<channel|>", "[sad] Ох, обнимаю тебя.") }, ChatFormat.GEMMA4, "local:g")
        val heard = StringBuilder()
        assertEquals("[sad] Ох, обнимаю тебя.", g.stream(request) { heard.append(it) }.text)
        assertEquals("[sad] Ох, обнимаю тебя.", heard.toString())
    }

    @Test
    fun strayChineseIsDroppedUnlessThePersonWroteIt() = runTest {
        val p = LocalLlmProvider({ FakeEngine("[happy] Это очень 很好 хорошая идея", "，мрр!") }, ChatFormat.CHATML, "local:test")
        val heard = StringBuilder()
        assertEquals("[happy] Это очень хорошая идея, мрр!", p.stream(request) { heard.append(it) }.text)
        assertFalse(heard.any { LocalLlmProvider.isCjk(it) })
        val asked = request.copy(messages = listOf(ChatMessage.User("Как сказать «кошка» по-китайски? 猫?")))
        val cn = LocalLlmProvider({ FakeEngine("Это 猫 (māo).") }, ChatFormat.CHATML, "local:test")
        assertEquals("Это 猫 (māo).", cn.complete(asked).text)
    }

    @Test
    fun theNextMessageReusesTheWholeCachedDialogue() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] Мрр, привет!")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test"))
            a.handle("меня зовут Аня")
            a.handle("как дела?")
            a.handle("что посоветуешь почитать?")
            val (p2, p3) = engine.prompts[1] to engine.prompts[2]
            // Everything the model saw (and wrote) last time is the start of the next prompt: only the new message is read.
            val before = p2 + "[happy] Мрр, привет!"
            assertTrue(p3.startsWith(before), "the cache breaks at ${before.commonPrefixWith(p3).length} of ${before.length}")
            assertTrue(p2.contains("Человека зовут Аня"))
            // A long talk: the window of the dialogue moves in steps, so most messages still reuse the cache.
            repeat(12) { a.handle("ещё вопрос номер $it?") }
            val kept = engine.prompts.zipWithNext().count { (prev, next) -> next.startsWith(prev + "[happy] Мрр, привет!") }
            assertTrue(kept >= 9, "the cache held in $kept of ${engine.prompts.size - 1} messages")
        }
    }

    @Test
    fun formatFromTheModelFilesOwnTemplate() {
        // A minimal GGUF header: magic, version, 0 tensors, 2 key-values (a number, then the chat template).
        fun gguf(template: String): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            fun le32(v: Int) { for (i in 0 until 4) b.write((v shr (8 * i)) and 0xff) }
            fun le64(v: Long) { for (i in 0 until 8) b.write(((v shr (8 * i)) and 0xff).toInt()) }
            fun str(s: String) { val x = s.toByteArray(); le64(x.size.toLong()); b.write(x) }
            b.write("GGUF".toByteArray()); le32(3); le64(0); le64(2)
            str("general.alignment"); le32(4); le32(32)
            str("tokenizer.chat_template"); le32(8); str(template)
            return b.toByteArray()
        }
        fun detect(t: String) = ChatFormat.detect(ru.iskra.core.llm.local.Gguf.chatTemplate(gguf(t).inputStream()))
        assertEquals(ChatFormat.GEMMA, detect("{{'<start_of_turn>model\n'}}"))
        assertEquals(ChatFormat.GEMMA4, detect("{{- '<|turn>model\n' -}}"))
        assertEquals(ChatFormat.CHATML_NO_THINK, detect("<|im_start|>assistant {% if enable_thinking %}"))
        assertEquals(ChatFormat.CHATML, detect("<|im_start|>assistant"))
        assertEquals(ChatFormat.CHATML, ChatFormat.detect(null))
    }

    @Test
    fun longDialoguesAreTrimmedToTheContext() {
        val turns = (1..40).flatMap { listOf(ChatMessage.User("Вопрос номер $it, довольно длинный, чтобы занять место в контексте."), ChatMessage.Assistant("Ответ номер $it, тоже не короткий.")) }
        val r = LocalLlmProvider.fit(LlmRequest("Ты — Искра.", turns + ChatMessage.User("Последний вопрос")), 600)
        assertTrue(r.messages.size < turns.size)
        assertTrue(r.messages.first() is ChatMessage.User)
        assertEquals("Последний вопрос", (r.messages.last() as ChatMessage.User).text)
        // One huge shared text keeps its start and end.
        val huge = "Начало. " + "очень длинный текст ".repeat(400) + "Конец."
        val one = LocalLlmProvider.fit(LlmRequest("Ты — Искра.", listOf(ChatMessage.User(huge))), 1200)
        val kept = (one.messages.single() as ChatMessage.User).text
        assertTrue(kept.startsWith("Начало.") && kept.endsWith("Конец.") && kept.length < huge.length, kept.take(40))
        assertTrue(LocalLlmProvider.estimateTokens(kept) < 1200)
    }

    @Test
    fun aFailingPhoneModelGivesAnHonestAnswer() = withLang(Lang.RU) {
        runTest {
            val broken = LocalLlmProvider({ null }, ChatFormat.CHATML, "local:broken")
            val r = agent(null, broken).handle("почему небо голубое?")
            assertTrue(r.text.contains("мозг"), r.text)
            assertTrue(r.error != null)
        }
    }

    private class Failing(private val kind: LlmException.Kind) : LlmProvider {
        override val id = "cloud"
        var calls = 0
        override suspend fun complete(request: LlmRequest): LlmResponse { calls++; throw LlmException("x", kind) }
    }

    private fun agent(cloud: LlmProvider?, local: LlmProvider?) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { cloud }, localFactory = { local }, random = Random(1),
    )

    @Test
    fun withoutCloudThePhoneModelTalks() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] Жила-была кошка…")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test"))
            a.handle("меня зовут Аня")   // stored as "Владельца зовут Аня"; the small model reads "Человека зовут Аня"
            val r = a.handle("расскажи сказку")
            assertEquals("Жила-была кошка…", r.text)
            assertTrue(r.usedLlm)
            val prompt = engine.prompts.last()
            assertTrue(prompt.contains("Человека зовут Аня"), prompt)
            assertTrue(prompt.contains("коротко (1–3 предложения)"), prompt)  // the compact prompt
            assertTrue(prompt.contains("женском роде"))
            assertFalse(prompt.contains("Пример"))                              // small models copy examples
            assertFalse(prompt.contains("владел"))                              // plain words for a small model
            assertFalse(prompt.contains("Инструменты"))                       // no tools for a small model
            assertTrue(a.handle("сколько будет 2+2").text.endsWith("= 4"))    // exact answers stay local
            assertTrue(a.handle("ты человек?").text.contains("программа"))     // honesty is not left to a small model
            assertEquals(2, engine.prompts.size)                                // only the two real questions reached the model
        }
    }

    @Test
    fun phoneModelStepsInWhenTheCloudFails() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[neutral] Отвечаю сама.")
            val cloud = Failing(LlmException.Kind.NETWORK)
            val r = agent(cloud, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test")).handle("о чём думаешь?")
            assertEquals("Отвечаю сама.", r.text)
            assertNull(r.error)
            assertEquals(LlmException.Kind.NETWORK, r.errorKind)
        }
    }

    @Test
    fun usedUpFreeMessagesAreMentionedOnceThenThePhoneModelContinues() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] Ответ с телефона.")
            val cloud = Failing(LlmException.Kind.FREE_QUOTA)
            val a = agent(cloud, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test"))
            val first = a.handle("расскажи о себе подробно")
            assertTrue(first.text.startsWith("Ответ с телефона.") && first.text.contains("моделью на телефоне"), first.text)
            val second = a.handle("а ещё?")
            assertEquals("Ответ с телефона.", second.text)
            assertEquals(1, cloud.calls)   // paused until midnight UTC: no more cloud attempts
        }
    }
}
