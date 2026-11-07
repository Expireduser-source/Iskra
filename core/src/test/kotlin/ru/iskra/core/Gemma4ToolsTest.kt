package ru.iskra.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.ToolCall
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.Gemma4Tools
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.tools.ToolSchemas
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Gemma 4 on the phone calls tools in Google's own markup (the model's chat template, 2026-07-09). */
class Gemma4ToolsTest {
    private val q = "<|\"|>"

    @Test fun declarationsAreWrittenAsTheTemplateDoes() = withLang(Lang.EN) {
        assertEquals("declaration:set_timer{description:${q}Start a timer in the system clock: for minutes or seconds.$q,parameters:{properties:{" +
            "label:{description:${q}Label$q,type:${q}STRING$q},minutes:{description:${q}Minutes, may be fractional: 1.5$q,type:${q}NUMBER$q}," +
            "seconds:{description:${q}Seconds — for short timers, or added to the minutes$q,type:${q}INTEGER$q}},type:${q}OBJECT$q}}",
            Gemma4Tools.declaration(ToolSchemas.setTimer))
        assertEquals("declaration:volume{description:${q}Change the media volume.$q,parameters:{properties:{direction:{description:${q}Direction$q," +
            "enum:[${q}up$q,${q}down$q,${q}mute$q,${q}unmute$q],type:${q}STRING$q}},required:[${q}direction$q],type:${q}OBJECT$q}}",
            Gemma4Tools.declaration(ToolSchemas.volume))
        assertEquals("declaration:open_camera{description:${q}Open the camera to take a photo.$q,parameters:{type:${q}OBJECT$q}}",
            Gemma4Tools.declaration(ToolSchemas.openCamera))
        assertEquals("<|tool>" + Gemma4Tools.declaration(ToolSchemas.setTimer) + "<tool|>", Gemma4Tools.declarations(listOf(ToolSchemas.setTimer)))
    }

    /** Minutes, seconds or both (E2B, given only seconds, asked the person «Скажи мне секунды»). */
    @Test fun aTimerTakesMinutesOrSeconds() {
        fun s(vararg kv: Pair<String, Any>) = ru.iskra.core.tools.timerSeconds(buildJsonObject {
            for ((k, v) in kv) when (v) { is Number -> put(k, v); else -> put(k, v.toString()) }
        })
        assertEquals(240, s("minutes" to 4))
        assertEquals(90, s("minutes" to 1.5))
        assertEquals(210, s("minutes" to 3, "seconds" to 30))
        assertEquals(45, s("seconds" to 45))
        assertEquals(150, s("minutes" to "2,5"))
        assertEquals(null, s("label" to "яйца"))
        assertEquals(null, s("minutes" to 0))
        assertEquals(null, s("minutes" to 2000))
    }

    @Test fun callsAreRead() {
        val (said, calls) = Gemma4Tools.parse("Ставлю! <|tool_call>call:set_timer{label:${q}пирог$q,seconds:1800}<tool_call|>")
        assertEquals("Ставлю!", said)
        assertEquals("set_timer", calls.single().name)
        assertEquals("пирог", calls.single().arguments["label"]!!.jsonPrimitive.content)
        assertEquals(1800, calls.single().arguments["seconds"]!!.jsonPrimitive.int)
        // Cut off by the stop at <tool_call|>, nested values, two calls.
        val (none, two) = Gemma4Tools.parse("<|tool_call>call:flashlight{on:true}<tool_call|><|tool_call>call:x{a:{b:[1,2,${q}c$q]},n:null}")
        assertEquals("", none)
        assertEquals(listOf("flashlight", "x"), two.map { it.name })
        assertEquals(true, two[0].arguments["on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("c", two[1].arguments["a"]!!.jsonObject["b"]!!.jsonArray[2].jsonPrimitive.content)
        // Not a call: the text stays as it is.
        assertEquals("Просто ответ" to emptyList(), Gemma4Tools.parse("Просто ответ"))
        assertTrue(Gemma4Tools.parse("<|tool_call>нечто непонятное").second.isEmpty())
    }

    @Test fun aCallAndItsResultAreWrittenBack() {
        val call = ToolCall("call_1", "set_timer", buildJsonObject { put("seconds", 300); put("label", "чай") })
        assertEquals("<|tool_call>call:set_timer{label:${q}чай$q,seconds:300}<tool_call|>", Gemma4Tools.call(call))
        assertEquals("<|tool_response>response:set_timer{value:${q}таймер запущен$q}<tool_response|>", Gemma4Tools.response("set_timer", "таймер запущен"))
    }

    /** The tool loop renders as one model turn that goes on after the result: no closing tag, no new header. */
    @Test fun theTurnGoesOnAfterTheResult() = withLang(Lang.RU) {
        val call = ToolCall("call_1", "set_timer", buildJsonObject { put("seconds", 1800) })
        val r = LlmRequest(system = "Ты — Искра.", tools = listOf(ToolSchemas.setTimer), situation = "Сейчас 15:00.", messages = listOf(
            ChatMessage.User("Через полчаса вынуть пирог — не дай мне забыть"),
            ChatMessage.Assistant("", listOf(call)),
            ChatMessage.ToolResult("call_1", "set_timer", "таймер запущен"),
        ))
        val p = ChatFormat.GEMMA4.prompt(r).text
        assertTrue(p.startsWith("<|turn>system\nТы — Искра.<|tool>declaration:set_timer{"), p)
        assertTrue(p.endsWith("<|turn>user\nСейчас 15:00.\n\nЧерез полчаса вынуть пирог — не дай мне забыть<turn|>\n" +
            "<|turn>model\n<|tool_call>call:set_timer{seconds:1800}<tool_call|><|tool_response>response:set_timer{value:${q}таймер запущен$q}<tool_response|>"), p)
        // Without tools nothing about them is written.
        assertFalse(ChatFormat.GEMMA4.prompt(r.copy(tools = emptyList(), messages = r.messages.take(1))).text.contains("<|tool>"))
    }

    private class Scripted(vararg outputs: String) : LlamaEngine {
        private val left = ArrayDeque(outputs.toList())
        val prompts = mutableListOf<String>()
        override val info = "scripted"
        override val contextSize = 8192
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            prompts += prompt
            val out = StringBuilder()
            // Token by token, roughly: the special tags come whole, as llama.cpp gives them.
            for (piece in Regex("""<\|tool_call>|<tool_call\|>|[^<]+|<""").findAll(left.removeFirstOrNull() ?: "[happy] Мрр!").map { it.value }) {
                out.append(piece); if (!onPiece(piece)) break
            }
            return out.toString()
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    @Test fun theCallIsNeitherShownNorSpoken() = withLang(Lang.RU) {
        runBlocking {
            val engine = Scripted("Ставлю таймер! <|tool_call>call:set_timer{seconds:1800}<tool_call|>")
            val p = LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:test")
            val heard = StringBuilder()
            val r = p.stream(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Через полчаса вынуть пирог")), tools = listOf(ToolSchemas.setTimer))) { heard.append(it) }
            assertEquals("Ставлю таймер!", r.text)
            assertEquals("set_timer", r.toolCalls.single().name)
            assertFalse(heard.contains("tool_call"), heard.toString())
            // No tools offered: a made-up call is dropped and not shown either.
            val r2 = LocalLlmProvider({ Scripted("<|tool_call>call:set_timer{seconds:5}<tool_call|>") }, ChatFormat.GEMMA4, "local:test")
                .complete(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет"))))
            assertTrue(r2.toolCalls.isEmpty())
            assertFalse(r2.text.contains("tool_call"))
        }
    }

    /** The whole way: Gemma 4 on the phone calls the timer, the tool runs, the model answers in the same turn. */
    @Test fun gemma4OnThePhoneSetsATimer() = withLang(Lang.RU) {
        runBlocking {
            val engine = Scripted("<|tool_call>call:set_timer{label:${q}яйца$q,seconds:240}<tool_call|>", "[happy] Засекла четыре минуты — будут всмятку!")
            val timer = RecordingTool()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            // Not a wording the phrase parser can read: how long soft-boiled eggs take is the model's to know.
            val r = agent.handle("Сварю яйца всмятку — засеки, сколько нужно")
            assertEquals(240, timer.calls.single()["seconds"]!!.jsonPrimitive.int)
            assertEquals("Засекла четыре минуты — будут всмятку!", r.text)
            assertEquals(1, r.actions.size)
            // The first prompt declared the tool; the second goes on after its result, in the same model turn.
            assertTrue(engine.prompts[0].contains("<|tool>declaration:set_timer"))
            assertTrue(engine.prompts[1].endsWith("<|tool_response>response:set_timer{value:${q}OK: таймер запущен$q}<tool_response|>"), engine.prompts[1].takeLast(200))
        }
    }

    /** A small model repeating its call after the result: the timer is set once, the model is told it's done. */
    @Test fun aRepeatedCallIsNotDoneTwice() = withLang(Lang.RU) {
        runBlocking {
            val engine = Scripted("<|tool_call>call:set_timer{seconds:1800}<tool_call|>", "<|tool_call>call:set_timer{seconds:1800}<tool_call|>",
                "[happy] Таймер на полчаса стоит!")
            val timer = RecordingTool()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            val r = agent.handle("Сварю яйца всмятку — засеки, сколько нужно")
            assertEquals(1, timer.calls.size)
            assertEquals(1, r.actions.size)
            assertEquals("Таймер на полчаса стоит!", r.text)
            assertTrue(engine.prompts[2].endsWith("{value:${q}Уже сделано: OK: таймер запущен$q}<tool_response|>"), engine.prompts[2].takeLast(200))
        }
    }

    /** Other phone models get no tools; the cloud gets all of them. */
    @Test fun onlyGemma4OnThePhoneGetsTools() {
        assertTrue(LocalLlmProvider({ null }, ChatFormat.GEMMA4, "a").nativeTools)
        assertFalse(LocalLlmProvider({ null }, ChatFormat.GEMMA, "b").nativeTools)
        assertFalse(LocalLlmProvider({ null }, ChatFormat.CHATML, "c").nativeTools)
    }

    @Suppress("unused") private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    /** One fixed answer, piece by piece. */
    class ScriptedForTests(private val answer: String) : LlamaEngine {
        override val info = "scripted"
        override val contextSize = 8192
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            for (c in answer.chunked(3)) if (!onPiece(c)) break
            return answer
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}

/** The evaluation's tool request must reach the model: the phrase parser doesn't know this wording. */
class EvalToolRequestTest {
    @Test fun theParserLeavesItToTheModel() = withLang(Lang.RU) {
        val now = java.time.LocalDateTime.of(2026, 11, 8, 15, 0)
        assertEquals(null, ru.iskra.core.tools.CommandParser.parse("Сварю яйца всмятку — засеки, сколько нужно.", now))
    }
}

/** Letters of a script the person didn't write in are dropped from a phone model's reply (Llama 3.2 1B: «Мैं Аня»). */
class ForeignScriptTest {
    private val ru = LocalLlmProvider.BASE_SCRIPTS + LocalLlmProvider.scriptsOf("Привет, меня зовут Аня")

    @Test fun strayLettersGo() {
        assertEquals("Огромного привета! Аня, это отличная девушка.", LocalLlmProvider.stripForeign("Огромного привета! मैं Аня, это отличная девушка.", ru)
            .replace(Regex(" {2,}"), " "))
        assertEquals("гидjenные", LocalLlmProvider.stripForeign("гидروjenные", ru)) // the Arabic «رو» in the middle goes
        assertEquals("Привет,. Как дела?", LocalLlmProvider.stripForeign("Привет，你好世界。Как дела？", ru))
        // Emoji, Greek letters in formulas, Latin words stay.
        assertEquals("Ура! 🎉 π ≈ 3.14, ok", LocalLlmProvider.stripForeign("Ура! 🎉 π ≈ 3.14, ok", ru))
    }

    @Test fun theOwnersScriptStays() {
        val hindi = LocalLlmProvider.BASE_SCRIPTS + LocalLlmProvider.scriptsOf("नमस्ते")
        assertEquals("मैं ठीक हूँ", LocalLlmProvider.stripForeign("मैं ठीक हूँ", hindi))
    }

    @Test fun anotherLanguageAskedForStays() = withLang(Lang.RU) {
        runBlocking {
            val p = LocalLlmProvider({ Gemma4ToolsTest.ScriptedForTests("[happy] По-японски: ありがとう!") }, ChatFormat.GEMMA4, "local:test")
            assertEquals("[happy] По-японски: ありがとう!", p.complete(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Как сказать «спасибо» по-японски?")))).text)
            val q = LocalLlmProvider({ Gemma4ToolsTest.ScriptedForTests("[happy] Мрр, ありがとう привет!") }, ChatFormat.GEMMA4, "local:test")
            assertEquals("[happy] Мрр, привет!", q.complete(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет!")))).text.replace(Regex(" {2,}"), " "))
        }
    }
}

/** A phone model promising an action in words, with nothing called (Gemma 4 E4B in the 0.14.0 evaluation). */
class ActionPromiseTest {
    @Test fun theToolPromisedIsKnown() {
        val p = ru.iskra.core.agent.ActionPromise
        assertEquals("set_timer", p.tool("Я поняла! Я поставлю таймер на полчаса, чтобы ты не забыла вынуть пирог."))
        assertEquals("set_reminder", p.tool("Хорошо, Аня, я поставлю тебе напоминание! 🥧"))
        assertEquals("set_reminder", p.tool("Я напомню тебе через полчаса!"))
        assertEquals("set_alarm", p.tool("Завела будильник на семь!"))
        assertEquals("add_note", p.tool("Записала в заметки!"))
        assertEquals("flashlight", p.tool("Включаю фонарик!"))
        assertEquals("set_timer", p.tool("I'll set a timer for four minutes."))
        assertEquals(null, p.tool("Я рада за тебя!"))
    }

    @Test fun promisesAreHeard() {
        val p = ru.iskra.core.agent.ActionPromise
        for (t in listOf("Я поставлю таймер. Сколько времени тебе нужно?", "Хорошо! Я поставлю напоминание: «Вынуть пирог». Через полчаса ты получишь уведомление.",
            "Записала в заметки!", "Включаю фонарик!", "Я напомню тебе через полчаса.", "I'll set a timer for you.", "I've set a reminder."))
            assertTrue(p.made(t), t)
        for (t in listOf("Переворачивай пирог, чтобы он не подгорел. Не забывай о нем! ⏰", "Поставь таймер сам, а я подожду.", "Я рада за тебя!",
            "Ты поставил рекорд!", "Set a timer if you like."))
            assertFalse(p.made(t), t)
    }

    private fun agent(engine: LlamaEngine, format: ChatFormat, tool: RecordingTool = RecordingTool()) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(tool)), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, format, "local:test") }, random = Random(1))

    @Test fun gemma4IsToldToCallAndDoes() = withLang(Lang.RU) {
        runBlocking {
            // The second try has the promised call begun for it (ARM evaluation: E2B promised a timer twice, calling nothing):
            // the model writes only the arguments.
            val engine = Gemma4Tools2.Scripted("[happy] Я поставлю таймер на четыре минуты, и яйца будут всмятку!",
                "{seconds:240}<tool_call|>", "[happy] Готово — четыре минуты!")
            val timer = RecordingTool()
            val r = agent(engine, ChatFormat.GEMMA4, timer).handle("Сварю яйца всмятку — засеки, сколько нужно")
            assertEquals("Готово — четыре минуты!", r.text)
            assertEquals(240, timer.calls.single()["seconds"]!!.jsonPrimitive.int)
            assertTrue(engine.prompts[1].contains("Вызови инструмент"), engine.prompts[1].takeLast(300))
            assertTrue(engine.prompts[1].endsWith("<|turn>model\n<|tool_call>call:set_timer"), engine.prompts[1].takeLast(100))
            // After the result the model goes on from the call as it was made.
            val q = "<|\"|>"
            assertTrue(engine.prompts[2].endsWith("<|tool_call>call:set_timer{seconds:240}<tool_call|><|tool_response>response:set_timer{value:${q}OK: таймер запущен$q}<tool_response|>"),
                engine.prompts[2].takeLast(200))
        }
    }

    @Test fun aPromiseNothingCameOfIsOwned() = withLang(Lang.RU) {
        runBlocking {
            // A phone model without tools can only promise: it is said that nothing is set.
            val r = agent(Gemma4Tools2.Scripted("[happy] Я поставлю таймер на четыре минуты, не волнуйся!"), ChatFormat.GEMMA).handle("Сварю яйца всмятку, засеки время")
            assertTrue(r.text.startsWith("Я поставлю таймер на четыре минуты, не волнуйся! Ой, это я только сказала"), r.text)
            // A question back is not a broken promise.
            val q = agent(Gemma4Tools2.Scripted("[curious] Я поставлю таймер. Сколько минут?", "[curious] Я поставлю таймер. Сколько минут?"), ChatFormat.GEMMA4)
                .handle("Поможешь не забыть про пирог?")
            assertEquals("Я поставлю таймер. Сколько минут?", q.text)
        }
    }
}

/** Scripted answers, one per generation. */
object Gemma4Tools2 {
    class Scripted(vararg outputs: String) : LlamaEngine {
        private val left = ArrayDeque(outputs.toList())
        val prompts = mutableListOf<String>()
        override val info = "scripted"
        override val contextSize = 8192
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            prompts += prompt
            val answer = left.removeFirstOrNull() ?: "[happy] Мрр!"
            for (piece in Regex("""<\|tool_call>|<tool_call\|>|[^<]+|<""").findAll(answer).map { it.value }) if (!onPiece(piece)) break
            return answer
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}

/**
 * Fitting the dialogue into a phone model's context (ARM evaluation, 0.14.0): with Gemma 4's tools the guess went over
 * the room after a call, everything but the tool's result was dropped, and the model greeted the person anew right after
 * setting their reminder.
 */
class PromptFitTest {
    private val call = ToolCall("call_1", "set_reminder", buildJsonObject { put("in_minutes", 30); put("text", "вынуть пирог") })

    @Test fun theRequestAndWhatWasDoneForItStay() {
        val messages = listOf(
            ChatMessage.User("Старая реплика"), ChatMessage.Assistant("Старый ответ"),
            ChatMessage.User("Через полчаса надо вынуть пирог, не дай мне забыть"),
            ChatMessage.Assistant("Сейчас!", listOf(call)),
            ChatMessage.ToolResult("call_1", "set_reminder", "OK: напомню через 30 мин"),
        )
        // Even the instructions alone are over the room: the older turns go, the request and its call stay.
        val r = LocalLlmProvider.fit(LlmRequest("И".repeat(3000), messages), 1000)
        assertEquals(messages.drop(2), r.messages)
        val p = ChatFormat.GEMMA4.prompt(r).text
        assertTrue(p.contains("<|turn>user\nЧерез полчаса надо вынуть пирог"), p.takeLast(400))
        assertTrue(p.endsWith("<tool_response|>"), p.takeLast(200))
        // Room for everything: nothing goes.
        assertEquals(messages, LocalLlmProvider.fit(LlmRequest("Ты — Искра.", messages), 4000).messages)
    }

    /** An engine counting [perToken] characters per token, refusing a prompt over its context as the native one does. */
    private class Counting(override val contextSize: Int, private val perToken: Int) : LlamaEngine {
        val prompts = mutableListOf<String>()
        var refused = 0
        private var last = 0
        override val info = "counting"
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            val tokens = prompt.length / perToken
            if (tokens + maxTokens > contextSize) { refused++; throw IllegalArgumentException("prompt too long for the context") }
            prompts += prompt
            last = tokens
            val answer = "[happy] Я тут!"
            onPiece(answer)
            return answer
        }
        override fun lastRead() = last to 0
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private fun dialogue(n: Int) = (1..n).flatMap { i ->
        listOf(ChatMessage.User("Реплика номер $i: " + "слово ".repeat(12)), ChatMessage.Assistant("Ответ номер $i: " + "мрр ".repeat(12)))
    } + ChatMessage.User("Последний вопрос")

    @Test fun aPromptTooLongIsFittedAgain() = withLang(Lang.RU) {
        runBlocking {
            // Two characters a token: more than the first guess for Gemma (three).
            val engine = Counting(2048, 2)
            val r = LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:test").complete(LlmRequest("Ты — Искра.", dialogue(60)))
            assertEquals("[happy] Я тут!", r.text)
            assertEquals(1, engine.refused)
            assertTrue(engine.prompts.single().contains("Последний вопрос"))
        }
    }

    @Test fun theMeasureOfATokenIsLearned() = withLang(Lang.RU) {
        runBlocking {
            // Five characters a token: the first prompt is fitted by the cautious guess, the next by the measure.
            val engine = Counting(2048, 5)
            val p = LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:test")
            val request = LlmRequest("Ты — Искра.", dialogue(80))
            p.complete(request)
            p.complete(request)
            assertEquals(0, engine.refused)
            fun turns(prompt: String) = Regex("Реплика номер").findAll(prompt).count()
            assertTrue(turns(engine.prompts[1]) >= turns(engine.prompts[0]) * 13 / 10, "${turns(engine.prompts[0])} → ${turns(engine.prompts[1])}")
        }
    }
}
