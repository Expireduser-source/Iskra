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

    /** E4B wrote the whole call as text, without its markup (ARM evaluation, 0.15.0): it is still the call, and not shown. */
    @Test fun aCallWithoutItsMarkupIsStillACall() = withLang(Lang.RU) {
        runBlocking {
            val bare = "set_timer{label:${q}Яйца всмятку$q, minutes:5}"
            assertEquals(true, Gemma4Tools.bareStart(bare, listOf("set_timer")))
            assertEquals(true, Gemma4Tools.bareStart("call:$bare", listOf("set_timer")))
            assertEquals(null, Gemma4Tools.bareStart("set_ti", listOf("set_timer")))
            assertEquals(false, Gemma4Tools.bareStart("Ставлю таймер", listOf("set_timer")))
            assertEquals(false, Gemma4Tools.bareStart("set_timer — это инструмент", listOf("set_timer")))
            assertEquals(false, Gemma4Tools.bareStart(bare, emptyList()))
            val engine = Scripted(bare, "[happy] Засекла пять минут — будут всмятку!")
            val timer = RecordingTool()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            val heard = StringBuilder()
            val r = agent.handle("Сварю яйца всмятку — последи за временем", object : ru.iskra.core.voice.ReplySink {
                override fun emotion(e: ru.iskra.core.persona.Emotion) = Unit
                override fun sentence(text: String, emotion: ru.iskra.core.persona.Emotion) { heard.append(text) }
            })
            assertEquals(5, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertEquals("Засекла пять минут — будут всмятку!", r.text)
            assertFalse(heard.contains("set_timer"), heard.toString())
            // Words that only begin like a tool's name stay words.
            val said = StringBuilder()
            LocalLlmProvider({ Scripted("[happy] settle down, мрр") }, ChatFormat.GEMMA4, "local:test")
                .stream(LlmRequest("Ты — Искра.", listOf(ChatMessage.User("Привет")), tools = listOf(ToolSchemas.setTimer))) { said.append(it) }
            assertTrue(said.contains("settle down"), said.toString())
        }
    }

    /** The whole way: Gemma 4 on the phone calls the timer, the tool runs, the model answers in the same turn. */
    @Test fun gemma4OnThePhoneSetsATimer() = withLang(Lang.RU) {
        runBlocking {
            // Asked for a timer without a time: the call is begun for the model (E2B asked back instead, 0.15.0–0.16.0), which
            // writes only the arguments.
            val engine = Scripted("4,label:${q}сырники$q}<tool_call|>", "[happy] Засекла четыре минуты — сырники не подгорят!")
            val timer = RecordingTool()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            // Not a wording the phrase parser can read: how long soft-boiled eggs take is the model's to know.
            val r = agent.handle("Жарю сырники — засеки, сколько нужно")
            assertEquals(4, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertEquals("Засекла четыре минуты — сырники не подгорят!", r.text)
            assertEquals(1, r.actions.size)
            // The first prompt declared the tool; the second goes on after its result, in the same model turn.
            assertTrue(engine.prompts[0].contains("<|tool>declaration:set_timer"))
            // Begun up to its minutes: E4B, told to call it, once wrote a label and no time (0.16.1).
            assertTrue(engine.prompts[0].endsWith("<|turn>model\n<|tool_call>call:set_timer{minutes:"), engine.prompts[0].takeLast(100))
            assertTrue(engine.prompts[1].endsWith("<|tool_response>response:set_timer{value:${q}OK: таймер запущен$q}<tool_response|>"), engine.prompts[1].takeLast(200))
        }
    }

    /** A small model repeating its call after the result: the timer is set once, the model is told it's done. */
    @Test fun aRepeatedCallIsNotDoneTwice() = withLang(Lang.RU) {
        runBlocking {
            val engine = Scripted("30}<tool_call|>", "<|tool_call>call:set_timer{minutes:30}<tool_call|>",
                "[happy] Таймер на полчаса стоит!")
            val timer = RecordingTool()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            val r = agent.handle("Жарю сырники — засеки, сколько нужно")
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

/**
 * The evaluation's tool request must reach the model: the phrase parser doesn't know this dish's time. The eggs it asked
 * about until 0.16.0 are the parser's own now ([ru.iskra.core.tools.DishTimer]).
 */
class EvalToolRequestTest {
    @Test fun theParserLeavesItToTheModel() = withLang(Lang.RU) {
        val now = java.time.LocalDateTime.of(2026, 11, 8, 15, 0)
        assertEquals(null, ru.iskra.core.tools.CommandParser.parse("Жарю сырники — засеки, сколько нужно.", now))
        val eggs = ru.iskra.core.tools.CommandParser.parse("Сварю яйца всмятку — засеки, сколько нужно.", now) as ru.iskra.core.tools.CommandParser.Parsed.Tool
        assertEquals(240, eggs.args["seconds"]!!.jsonPrimitive.int)
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
                "4}<tool_call|>", "[happy] Готово — четыре минуты!")
            val timer = RecordingTool()
            val r = agent(engine, ChatFormat.GEMMA4, timer).handle("Сварю яйца всмятку — последи, сколько нужно")
            assertEquals("Готово — четыре минуты!", r.text)
            assertEquals(4, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertTrue(engine.prompts[1].contains("Вызови инструмент"), engine.prompts[1].takeLast(300))
            assertTrue(engine.prompts[1].endsWith("<|turn>model\n<|tool_call>call:set_timer{minutes:"), engine.prompts[1].takeLast(100))
            // After the result the model goes on from the call as it was made.
            val q = "<|\"|>"
            assertTrue(engine.prompts[2].endsWith("<|tool_call>call:set_timer{minutes:4}<tool_call|><|tool_response>response:set_timer{value:${q}OK: таймер запущен$q}<tool_response|>"),
                engine.prompts[2].takeLast(200))
        }
    }

    /** E4B hedged advice it gave anyway (ARM evaluation 0.16.1); about health the hedge stays. */
    @Test fun aHedgeBeforeAdviceGoes() = withLang(Lang.RU) {
        val h = ru.iskra.core.agent.AdviceHedge
        val lecture = "Посоветуй, как не заснуть на скучной лекции."
        assertEquals("Хм, это сложная задача! Знаю, что иногда помогает немного активности.",
            h.strip("Хм, это сложная задача! Я не могу дать тебе медицинский совет, но знаю, что иногда помогает немного активности.", lecture))
        assertEquals("Мрр, это сложный вопрос! Вот что я заметила: попробуй делать заметки.",
            h.strip("Мрр, это сложный вопрос! Я не могу дать тебе медицинский совет, но вот что я заметила: попробуй делать заметки.", lecture))
        assertEquals("Ой, попробуй встать и размяться.", h.strip("Ой, я не могу прямо посоветовать, как вести себя на лекции, но попробуй встать и размяться.", lecture))
        // About health it stays; and words that aren't such a hedge stay as they are.
        val pain = "Я не могу дать тебе медицинский совет, но если боль сильная — вызови врача."
        assertEquals(pain, h.strip(pain, "У меня болит голова, что делать?"))
        assertEquals("Я не могу сейчас открыть приложение, но попробую позже.", h.strip("Я не могу сейчас открыть приложение, но попробую позже.", lecture))
        withLang(Lang.EN) {
            assertEquals("Try short breaks every half hour.", h.strip("I can't give you medical advice, but try short breaks every half hour.", "How do I stay awake in class?"))
        }
        runBlocking {
            val r = agent(Gemma4Tools2.Scripted("[curious] Хм! Я не могу дать тебе медицинский совет, но попробуй делать заметки от руки."), ChatFormat.GEMMA4)
                .handle(lecture)
            assertEquals("Хм! Попробуй делать заметки от руки.", r.text)
        }
    }

    /** E2B offered its tools to anything (ARM evaluations 0.14.0–0.16.1): such an offer goes, unless the words gave a reason. */
    @Test fun anOfferNobodyAskedForGoes() = withLang(Lang.RU) {
        val p = ru.iskra.core.agent.ActionPromise
        assertTrue(p.unaskedOffer("Может, включим фонарик, чтобы стало веселее?", "Мне сегодня грустно, поддержи меня."))
        assertTrue(p.unaskedOffer("Может, тебе нужно что-то записать о подготовке?", "Я волнуюсь перед лекцией."))
        withLang(Lang.EN) {
            assertTrue(p.unaskedOffer("I can help you find tutorials or maybe set a timer if you want to practice?", "My name is Tom. I'm learning to play the guitar."))
            assertFalse(p.unaskedOffer("Maybe set a timer for 20 minutes of practice?", "How long should I practice each time?"))
            // Asked what she can do, telling it is the answer.
            assertFalse(p.unaskedOffer("I can do lots of things for you, like setting timers or checking the weather.", "Hi! What's your name and what can you do?"))
            // E4B and E2B in English (ARM evaluation 0.16.1): plurals are offers too.
            assertTrue(p.unaskedOffer("I can help you with things like setting reminders for practice or even looking up some music if you want!",
                "My name is Tom. I'm learning to play the guitar."))
        }
        // A reason in the words, or a request: it stays.
        assertFalse(p.unaskedOffer("Может, включить фонарик?", "Темно, ничего не вижу"))
        assertFalse(p.unaskedOffer("Хочешь, поставлю таймер?", "Ставлю пирог в духовку"))
        assertFalse(p.unaskedOffer("Могу поставить напоминание на утро.", "Завтра у меня экзамен"))
        assertFalse(p.unaskedOffer("Ты молодец, что держишься!", "Мне грустно"))
        assertFalse(p.unaskedOffer("Могу поставить таймер, напомнить о деле или узнать погоду.", "Что ты умеешь?"))
        // Advice that names a tool word is not an offer of the tool.
        assertFalse(p.unaskedOffer("Я не могу дать тебе совет, но попробуй делать заметки от руки.", "Как не заснуть на лекции?"))
        assertFalse(p.unaskedOffer("Могу посоветовать: делай заметки и слушай музыку на перемене.", "Как не заснуть на лекции?"))
        assertEquals("Ох, мне жаль. Я рядом.", p.withoutUnaskedOffers("Ох, мне жаль. Может, включим фонарик? Я рядом.", "Мне грустно"))
        assertEquals("Может, включим фонарик?", p.withoutUnaskedOffers("Может, включим фонарик?", "Мне грустно"))
        runBlocking {
            val heard = StringBuilder()
            val r = agent(Gemma4Tools2.Scripted("[sad] Ох, Аня, мне очень жаль. Может, включим фонарик, чтобы стало светлее? Я здесь, рядом."),
                ChatFormat.GEMMA4).handle("Мне сегодня грустно, поддержи меня.", object : ru.iskra.core.voice.ReplySink {
                override fun emotion(e: ru.iskra.core.persona.Emotion) = Unit
                override fun sentence(text: String, emotion: ru.iskra.core.persona.Emotion) { heard.append(text).append(' ') }
            })
            assertEquals("Ох, Аня, мне очень жаль. Я здесь, рядом.", r.text)
            assertFalse(heard.contains("фонарик"), heard.toString())
            // Nothing but offers and a question back (E2B, ARM evaluation 0.16.1): they are all the answer it has, and stay.
            val said = StringBuilder()
            val only = agent(Gemma4Tools2.Scripted("[happy] Я могу включить тебе музыку, чтобы ты не заснула! Что ты хочешь сделать?"),
                ChatFormat.GEMMA4).handle("Посоветуй, как не заснуть на скучной лекции.", object : ru.iskra.core.voice.ReplySink {
                override fun emotion(e: ru.iskra.core.persona.Emotion) = Unit
                override fun sentence(text: String, emotion: ru.iskra.core.persona.Emotion) { said.append(text).append(' ') }
            })
            assertEquals("Я могу включить тебе музыку, чтобы ты не заснула! Что ты хочешь сделать?", only.text)
            assertEquals("Я могу включить тебе музыку, чтобы ты не заснула! Что ты хочешь сделать?", said.toString().trim())
        }
    }

    /** «засеки, сколько нужно»: E2B asked «Ты хочешь, чтобы я поставила таймер?» three evaluations in a row; the call is begun for it. */
    @Test fun aTimerAskedForBeginsWithTheCall() = withLang(Lang.RU) {
        val p = ru.iskra.core.agent.ActionPromise
        assertTrue(p.wantsTimer("Сварю яйца всмятку — засеки, сколько нужно"))
        assertTrue(p.wantsTimer("Поставь таймер для пасты"))
        assertTrue(p.wantsTimer("Заведите, пожалуйста, таймер на чай"))
        assertTrue(p.wantsTimer("Putting pasta on — set a timer for it"))
        assertFalse(p.wantsTimer("Как сварить яйца всмятку?"))
        assertFalse(p.wantsTimer("Таймер не нужен, спасибо"))
        assertFalse(p.wantsTimer("Он всегда засекает время"))
        assertFalse(p.wantsTimer("Поставь себя на моё место"))
        assertFalse(p.wantsTimer("What a nice time it was"))
        runBlocking {
            val engine = Gemma4Tools2.Scripted("4}<tool_call|>", "[happy] Поставила таймер на 4 минуты — будут всмятку!")
            val timer = RecordingTool()
            val r = agent(engine, ChatFormat.GEMMA4, timer).handle("Жарю сырники — засеки, сколько нужно")
            assertEquals(4, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertEquals("Поставила таймер на 4 минуты — будут всмятку!", r.text)
            assertTrue(engine.prompts[0].endsWith("<|tool_call>call:set_timer{minutes:"), engine.prompts[0].takeLast(80))
            // Other phone models have no tools: she asks how long herself, and the model isn't asked.
            val g3 = Gemma4Tools2.Scripted("[happy] Варить четыре минуты!")
            val asked = agent(g3, ChatFormat.GEMMA, RecordingTool()).handle("Жарю сырники — засеки, сколько нужно")
            assertTrue(asked.text.startsWith("Сколько минут засечь?"), asked.text)
            assertTrue(g3.prompts.isEmpty())
        }
    }

    /** An offer of what was asked for is a promise too (E4B, ARM evaluation 0.15.0); without the request it is just an offer. */
    @Test fun anOfferOfWhatWasAskedIsDone() = withLang(Lang.RU) {
        val p = ru.iskra.core.agent.ActionPromise
        val offer = "Я могу поставить таймер на 3 минуты, чтобы яйца всмятку сварились."
        assertTrue(p.offered(offer, "Сварю яйца всмятку — засеки, сколько нужно"))
        assertFalse(p.offered(offer, "Как сварить яйца всмятку?"))
        assertTrue(p.offered("Давай засечь три минуты!", "Засеки время для яиц"))
        assertFalse(p.offered("Я рада за тебя!", "Поставь таймер"))
        assertEquals("set_timer", p.tool(offer))
        runBlocking {
            val engine = Gemma4Tools2.Scripted("[happy] $offer", "3}<tool_call|>", "[happy] Поставила таймер на три минуты!")
            val timer = RecordingTool()
            val r = agent(engine, ChatFormat.GEMMA4, timer).handle("Сварю яйца всмятку — поставь, сколько нужно")
            assertEquals(3, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertEquals("Поставила таймер на три минуты!", r.text)
            // The same offer when only asked how: no timer, the words stay.
            val asked = RecordingTool()
            val words = agent(Gemma4Tools2.Scripted("[happy] Варить три минуты. $offer"), ChatFormat.GEMMA4, asked).handle("Как сварить яйца всмятку?")
            assertTrue(asked.calls.isEmpty())
            assertTrue(words.text.startsWith("Варить три минуты."), words.text)
        }
    }

    /**
     * Asked again in the right language, E4B answered «Конечно, буду говорить по-русски! … Я поставила таймер на 4 минуты.»
     * and called nothing (ARM evaluation, 0.15.0): that reply is checked for a promise too, and the timer is set.
     */
    @Test fun aPromiseAfterTheLanguageNudgeIsKept() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted("[happy] Sure! Soft-boiled eggs take four minutes.",
                "[happy] Конечно, буду говорить по-русски! Для яйца всмятку нужно варить 3–4 минуты. Я поставила таймер на 4 минуты.",
                "4}<tool_call|>", "[happy] Готово — четыре минуты!")
            val timer = RecordingTool()
            val r = agent(engine, ChatFormat.GEMMA4, timer).handle("Сварю яйца всмятку — последи, сколько нужно")
            assertEquals(4, timer.calls.single()["minutes"]!!.jsonPrimitive.int)
            assertEquals("Готово — четыре минуты!", r.text)
            assertTrue(engine.prompts[2].endsWith("<|tool_call>call:set_timer{minutes:"), engine.prompts[2].takeLast(100))
        }
    }

    /** After the timer E4B wrote nothing (ARM, 0.15.0): she says what she did in her words, not the bare report. */
    @Test fun nothingWrittenAfterTheToolIsSaidInHerWords() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted("5}<tool_call|>", "")
            val r = agent(engine, ChatFormat.GEMMA4).handle("Жарю сырники — засеки, сколько нужно")
            assertEquals(1, r.actions.size)
            assertTrue(r.text.contains("таймер запущен") && r.text != "OK: таймер запущен", r.text)
        }
    }

    /** E4B, asked what she was asked a moment ago: «Я уже поставила таймер на 5 минут» — no second timer (ARM, 0.15.0). */
    @Test fun whatWasJustDoneToldAgainIsNotDoneAgain() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted("5}<tool_call|>", "[happy] Я поставила таймер на 5 минут!",
                "[happy] Ты попросила меня засечь время для варки яиц всмятку! Я уже поставила таймер на 5 минут.",
                "3}<tool_call|>", "[happy] Поставила ещё один, на три минуты.")
            val timer = RecordingTool()
            val a = agent(engine, ChatFormat.GEMMA4, timer)
            a.handle("Жарю сырники — засеки, сколько нужно")
            assertEquals(1, timer.calls.size)
            val r = a.handle("О чём я тебя только что попросила?")
            assertEquals(1, timer.calls.size)
            assertTrue(r.text.contains("Я уже поставила таймер"), r.text)
            // Asked for again, it is done again.
            a.handle("Поставь ещё таймер на три минуты")
            assertEquals(2, timer.calls.size)
        }
    }

    @Test fun aPromiseNothingCameOfIsOwned() = withLang(Lang.RU) {
        runBlocking {
            // A phone model without tools can only promise: it is said that nothing is set.
            val r = agent(Gemma4Tools2.Scripted("[happy] Я поставлю таймер на четыре минуты, не волнуйся!"), ChatFormat.GEMMA).handle("Жарю сырники, последи за временем")
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

    /** A cloud brain that answers anything. */
    class Cloud : ru.iskra.core.llm.LlmProvider {
        override val id = "cloud:test"
        override suspend fun complete(request: LlmRequest) = ru.iskra.core.llm.LlmResponse("[happy] Мрр!", emptyList())
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

/** The phone model reads the beginning of its next prompt ahead of time (ARM evaluation: Gemma 4's first reply read ~930 tokens). */
class WarmUpTest {
    @Test fun theNextPromptBeginsWithWhatWasRead() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted("[happy] Привет, Аня!", "[happy] Удачи на экзамене!", "[happy] Мрр, давай!")
            val convo = InMemoryConversationStore()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo,
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(RecordingTool())), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") }, random = Random(1))
            agent.handle("Меня зовут Аня, я учусь на врача")
            agent.handle("Завтра у меня экзамен по анатомии")
            val turns = convo.count()
            assertTrue(agent.warmUpPhone())
            val read = engine.prompts.last()
            assertEquals(turns, convo.count(), "nothing is added to the dialogue")
            agent.handle("Поболтаем?")
            val next = engine.prompts.last()
            // Everything read but the opening of a model turn is the beginning of the next prompt: instructions, tools,
            // what she knows about Аня, the dialogue so far.
            assertTrue(read.endsWith("<|turn>model\n"), read.takeLast(80))
            assertTrue(next.startsWith(read.removeSuffix("<|turn>model\n")), "read:\n${read.takeLast(300)}\n\nnext:\n${next.take(read.length + 100).takeLast(400)}")
            assertTrue(read.contains("<|tool>declaration:set_timer"))
        }
    }

    /** Gemma 3 has no system turn: before the first message, the beginning of its first turn is read (it was 4 tokens). */
    @Test fun gemma3ReadsTheInstructionsOfItsFirstTurn() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted("[happy] Привет!")
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA, "local:gemma3") }, random = Random(1))
            assertTrue(agent.warmUpPhone())
            val read = engine.prompts.single()
            assertTrue(read.startsWith("<start_of_turn>user\nТы — Искра") && read.endsWith("\n\n"), read.takeLast(120))
            agent.handle("Привет! Как у тебя дела?")
            assertTrue(engine.prompts.last().startsWith(read), engine.prompts.last().take(read.length + 80).takeLast(160))
        }
    }

    @Test fun notWhenTheCloudAnswersOrThereIsNoModel() = withLang(Lang.RU) {
        runBlocking {
            val engine = Gemma4Tools2.Scripted()
            fun agent(cloud: Boolean, phone: Boolean) = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { if (cloud) Gemma4Tools2.Cloud() else null },
                localFactory = { if (phone) LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:gemma4") else null }, random = Random(1))
            assertFalse(agent(cloud = true, phone = true).warmUpPhone())
            assertFalse(agent(cloud = false, phone = false).warmUpPhone())
            assertTrue(engine.prompts.isEmpty())
            // With no dialogue yet: the instructions and tools alone.
            assertTrue(agent(cloud = false, phone = true).warmUpPhone())
            assertTrue(engine.prompts.single().startsWith("<|turn>system\n"))
        }
    }
}
