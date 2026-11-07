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
    val stables = mutableListOf<Int>()
    override val info = "fake"
    override val contextSize = 4096
    override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
        prompts += prompt
        stables += stable
        val out = StringBuilder()
        for (p in pieces) { out.append(p); if (!onPiece(p)) break }
        return out.toString()
    }
    override fun cancel() = Unit
    override fun close() = Unit
}

class LocalLlmTest {
    /** ARM evaluation, Gemma 3 1B on «Привет! Как у тебя дела?»: after two empty lines, two more versions of the reply. */
    @Test fun aSecondTakeIsCutOff() = runTest {
        val engine = FakeEngine("[happy] Привет! Всё отлично, ты? ", "Рада, что мы общаемся!", " \n\n\n", "Я поняла тебя, ты хочешь узнать, как я?", " \n\n\nАх...")
        val heard = StringBuilder()
        val r = LocalLlmProvider({ engine }, ChatFormat.GEMMA, "local:test").stream(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет! Как у тебя дела?")))) { heard.append(it) }
        assertEquals("[happy] Привет! Всё отлично, ты? Рада, что мы общаемся!", r.text)
        assertFalse(heard.contains("Я поняла"), heard.toString())
        // One empty line is a paragraph, and empty lines before any words are not a second take.
        assertEquals(-1, LocalLlmProvider.sampleBreak("Первый абзац.\n\nВторой."))
        assertEquals(-1, LocalLlmProvider.sampleBreak("\n\n\nОтвет"))
        assertEquals(-1, LocalLlmProvider.sampleBreak("<|channel>мысли\n\n\nещё"))
        assertEquals(10, LocalLlmProvider.sampleBreak("Ответ тут.\n\n\nЕщё"))
    }

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
            "<|turn>user\nСейчас четверг.\n\nКак дела?<turn|>\n<|turn>model\n", ChatFormat.GEMMA4.render(request))
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
        // Gemma 4 opened its thought channel anyway (the opening tag may come in pieces): nothing of it is shown or kept.
        val g = LocalLlmProvider({ FakeEngine("<", "|channel>thought\nПользователь грустит, надо утешить.", "<channel|>", "[sad] Ох, обнимаю тебя.") }, ChatFormat.GEMMA4, "local:g")
        val heard = StringBuilder()
        assertEquals("[sad] Ох, обнимаю тебя.", g.stream(request) { heard.append(it) }.text)
        assertEquals("[sad] Ох, обнимаю тебя.", heard.toString())
        // A straight answer streams as it comes, piece by piece.
        val pieces = ArrayList<String>()
        val d = LocalLlmProvider({ FakeEngine("[happy] Привет, ", "Аня! Как ", "дела?", "<turn|>") }, ChatFormat.GEMMA4, "local:d")
        assertEquals("[happy] Привет, Аня! Как дела?", d.stream(request) { pieces += it }.text)
        assertTrue(pieces.size >= 2, pieces.toString())
    }

    @Test
    fun questionsAboutTheOwnerDontGoToTheSmallModel() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] Мрр, приятно познакомиться!")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.GEMMA, "local:test"))
            a.handle("Меня зовут Аня. Я учусь на врача, сейчас сессия.")
            val asked = engine.prompts.size
            val r = a.handle("Как меня зовут и на кого я учусь?")
            assertEquals("Мрр, помню: тебя зовут Аня, и ты учишься на врача.", r.text)
            assertEquals(asked, engine.prompts.size)  // answered from memory, the model wasn't asked
            // A question memory can't answer still goes to the model.
            a.handle("Где я живу?")
            assertEquals(asked + 1, engine.prompts.size)
        }
    }

    @Test
    fun aSmallModelIsRemindedOfTheLanguageAfterATranslation() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] I love winter evenings.")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.GEMMA, "local:test"))
            a.handle("Переведи на английский: «Я люблю зимние вечера»")
            a.handle("Что лучше на завтрак перед экзаменом: каша или омлет?")
            a.handle("Переведи ещё: «Я люблю снег»")
            assertFalse(engine.prompts[0].contains("Ответь по-русски"))
            // The last reply was English, the message Russian: a reminder next to the message only.
            val p = engine.prompts[1]
            assertTrue(p.substringAfterLast("<start_of_turn>user\n").startsWith("Ответь по-русски."), p.takeLast(200))
            // Still English: once more without the English turns (and the request they answered) in the history.
            assertTrue(engine.prompts[1].contains("I love winter evenings"))
            assertFalse(engine.prompts[2].contains("I love winter evenings"), engine.prompts[2])
            assertFalse(engine.prompts[2].contains("Переведи на английский"))
            assertFalse(engine.prompts[3].contains("Ответь по-русски"))  // another translation request: no reminder
            val c = ru.iskra.core.agent.PromptComposer()
            assertEquals("Answer in English.", c.languageNudge("And what about breakfast?", "Мрр, омлет лучше!"))
            assertEquals("", c.languageNudge("Как дела?", "Мрр, хорошо!"))
            assertEquals("", c.languageNudge("Ок", "I love winter evenings."))  // too short to tell
        }
    }

    @Test
    fun strayPunctuationBeforeTheFirstWordIsDropped() = runTest {
        // Gemma 3 4B began replies with stray symbols that the Chinese filter turned into ", , ,".
        val p = LocalLlmProvider({ FakeEngine("，", "很 ，", " , ", "[happy] Мрр, привет!") }, ChatFormat.GEMMA, "local:test")
        val heard = StringBuilder()
        assertEquals("[happy] Мрр, привет!", p.stream(request) { heard.append(it) }.text)
        assertEquals("[happy] Мрр, привет!", heard.toString())
        assertEquals("…ну ладно", LocalLlmProvider.trimLead(" …ну ладно"))
    }

    @Test
    fun aReplyBeginsWithMrrAtMostOnceInThree() = runTest {
        // Small models began every reply with «Мрр,», copying their own history.
        val p = LocalLlmProvider({ FakeEngine("[hap", "py] М", "рр, при", "вет!") }, ChatFormat.GEMMA, "local:test")
        val got = (1..4).map { val heard = StringBuilder(); p.stream(request) { heard.append(it) }.text to heard.toString() }
        assertEquals(listOf("[happy] Мрр, привет!", "[happy] Привет!", "[happy] Привет!", "[happy] Мрр, привет!"), got.map { it.first })
        assertEquals(got.map { it.first }, got.map { it.second })  // what's heard is what's kept
        assertEquals("[happy] Мрр!", LocalLlmProvider.cleanStart("[happy] Мрр!", true))  // a reply that is only a purr stays
        assertEquals("Мурка спит.", LocalLlmProvider.cleanStart("Мурка спит.", true))
        assertEquals("Okay!", LocalLlmProvider.cleanStart("Mrr... okay!", true))
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
            // (A reply without «мрр»: a leading one may be dropped from the history — see aReplyBeginsWithMrrAtMostOnceInThree.)
            val engine = FakeEngine("[happy] Хорошо, привет!")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test"))
            a.handle("меня зовут Аня")
            a.handle("как дела?")
            a.handle("что посоветуешь почитать?")
            val (p2, p3) = engine.prompts[1] to engine.prompts[2]
            // Everything the model saw (and wrote) last time is the start of the next prompt: only the new message is read.
            val before = p2 + "[happy] Хорошо, привет!"
            assertTrue(p3.startsWith(before), "the cache breaks at ${before.commonPrefixWith(p3).length} of ${before.length}")
            assertTrue(p2.contains("Человека зовут Аня"))
            // A long talk: the window of the dialogue moves in steps, so most messages still reuse the cache.
            repeat(12) { a.handle("ещё вопрос номер $it?") }
            val kept = engine.prompts.zipWithNext().count { (prev, next) -> next.startsWith(prev + "[happy] Хорошо, привет!") }
            assertTrue(kept >= 9, "the cache held in $kept of ${engine.prompts.size - 1} messages")
        }
    }

    @Test
    fun everyFormatTellsWhereTheNextPromptStillAgrees() {
        // Models that can't cut their cache back (Qwen3.5) save their state at `stable`: the next prompt must repeat it.
        val r1 = LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет")), situation = "Ты помнишь: Аня любит чай.")
        val r2 = r1.copy(messages = r1.messages + ChatMessage.Assistant("[happy] Мрр, привет!") + ChatMessage.User("Как дела?"), situation = "")
        val r3 = r2.copy(messages = r2.messages + ChatMessage.Assistant("[calm] Хорошо.") + ChatMessage.User("Что почитать?"), situation = "Ты помнишь: Аня любит фантастику.")
        for (f in ChatFormat.entries) {
            val (p1, p2, p3) = listOf(r1, r2, r3).map { f.prompt(it) }
            for ((a, b) in listOf(p1 to p2, p2 to p3)) {
                assertTrue(a.stable > 0, "$f: nothing stable")
                assertTrue(b.text.startsWith(a.text.take(a.stable)), "$f: the next prompt differs within the stable part")
            }
            // With a situation, the stable part ends right before it (it won't be in the next prompt).
            assertTrue(p1.text.substring(p1.stable).removePrefix("<|im_start|>system\n").removePrefix("<|start_header_id|>system<|end_header_id|>\n\n")
                .removePrefix("<|system|>\n").startsWith("Ты помнишь"), "$f: ${p1.text.substring(p1.stable)}")
            // Without one, the newest message is stable too: only the answer header follows it.
            assertTrue(p2.text.substring(0, p2.stable).endsWith("Как дела?" + mapOf(ChatFormat.GEMMA to "<end_of_turn>\n",
                ChatFormat.GEMMA4 to "<turn|>\n", ChatFormat.LLAMA3 to "<|eot_id|>", ChatFormat.PHI to "<|end|>\n", ChatFormat.MISTRAL to " [/INST]")
                .getOrDefault(f, "<|im_end|>\n")), "$f: ${p2.text.substring(p2.stable)}")
            assertEquals(f.render(r3), p3.text)
            // When the dialogue window moves on (the oldest turns leave), the instructions still start the prompt.
            val moved = f.prompt(r3.copy(messages = r3.messages.drop(2)))
            assertTrue(p3.head in 1..p3.stable && moved.text.startsWith(p3.text.take(p3.head)), "$f: head ${p3.head}")
            assertTrue(p3.text.take(p3.head).contains("Ты — Искра."), "$f: the instructions are in the head")
        }
    }

    @Test
    fun theModelHearsWhereToSaveItsState() = withLang(Lang.RU) {
        runTest {
            val engine = FakeEngine("[happy] Мрр, привет!")
            val a = agent(null, LocalLlmProvider({ engine }, ChatFormat.CHATML_NO_THINK, "local:test"))
            a.handle("как дела?")
            a.handle("что почитать?")
            val (s1, p2) = engine.stables[0] to engine.prompts[1]
            assertTrue(s1 > 0 && p2.startsWith(engine.prompts[0].take(s1)))
            // The empty <think> block is only in the newest prompt's header, so the state is saved before it.
            assertFalse(engine.prompts[0].take(s1).contains("<think>"))
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

    private fun agent(cloud: LlmProvider?, local: LlmProvider?, rules: Set<ru.iskra.core.persona.ModelRule> = emptySet()) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { cloud }, localFactory = { local }, random = Random(1), rules = { rules },
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
            assertEquals(2, engine.prompts.size)                                // only the two real questions reached the model
            // By default the model answers «ты человек?» itself; with «говорит, что она программа» on, a small model doesn't get it.
            assertEquals("Жила-была кошка…", a.handle("ты человек?").text)
            assertEquals(3, engine.prompts.size)
            val strict = agent(null, LocalLlmProvider({ engine }, ChatFormat.CHATML, "local:test"), setOf(ru.iskra.core.persona.ModelRule.AI_HONESTY))
            assertTrue(strict.handle("ты человек?").text.contains("программа"))
            assertEquals(3, engine.prompts.size)
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
