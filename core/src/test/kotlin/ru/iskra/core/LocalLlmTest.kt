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
            val r = a.handle("расскажи сказку")
            assertEquals("Жила-была кошка…", r.text)
            assertTrue(r.usedLlm)
            val prompt = engine.prompts.single()
            assertTrue(prompt.contains("Отвечай коротко"), prompt)            // the compact prompt
            assertFalse(prompt.contains("Инструменты"))                       // no tools for a small model
            assertTrue(a.handle("сколько будет 2+2").text.endsWith("= 4"))    // exact answers stay local
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
