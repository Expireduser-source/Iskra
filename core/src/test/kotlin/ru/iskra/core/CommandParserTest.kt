package ru.iskra.core

import kotlinx.serialization.json.jsonPrimitive
import ru.iskra.core.tools.CommandParser
import ru.iskra.core.tools.CommandParser.Parsed
import ru.iskra.core.tools.ToolNames
import ru.iskra.core.tools.DishTimer
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandParserTest {
    private fun tool(s: String) = assertIs<Parsed.Tool>(CommandParser.parse(s), s)

    @Test fun alarm() {
        val p = tool("Поставь будильник на 7:30")
        assertEquals(ToolNames.SET_ALARM, p.name)
        assertEquals("7", p.args["hour"]!!.jsonPrimitive.content)
        assertEquals("30", p.args["minute"]!!.jsonPrimitive.content)
        assertEquals("19", tool("разбуди меня в 7 вечера").args["hour"]!!.jsonPrimitive.content)
        assertNull(CommandParser.parse("поставь будильник на 25:00"))
        // In words, with «завтра», as a question.
        fun hm(s: String) = tool(s).let { it.args["hour"]!!.jsonPrimitive.content + ":" + it.args["minute"]!!.jsonPrimitive.content }
        assertEquals("7:0", hm("Разбуди меня завтра в семь"))
        assertEquals("6:30", hm("Поставь будильник на завтра на 6:30"))
        assertEquals("7:0", hm("Разбудишь меня в 7 утра?"))
        assertEquals("8:15", hm("заведи будильник на восемь пятнадцать"))
        assertEquals("14:0", hm("разбуди меня в 2 дня"))
        assertEquals("6:0", hm("будильник на 6 завтра утром"))
        assertNull(CommandParser.parse("разбуди меня, когда приедем"))
    }

    @Test fun timer() {
        assertEquals("300", tool("таймер на 5 минут").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("30", tool("поставь таймер на 30 секунд").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("1800", tool("таймер на полчаса").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("7200", tool("таймер 2 часа").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("180", tool("Искра, таймер на три минуты!").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("300", tool("Засеки 5 минут").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("600", tool("засеки мне десять минут").args["seconds"]!!.jsonPrimitive.content)
        assertNull(CommandParser.parse("засеки, сколько нужно"))
    }

    @Test fun simpleCommands() {
        assertEquals(ToolNames.FLASHLIGHT, tool("включи фонарик").name)
        assertEquals("false", tool("выключи фонарик").args["on"]!!.jsonPrimitive.content)
        assertEquals("home", tool("домой").args["action"]!!.jsonPrimitive.content)
        assertEquals("wifi", tool("открой настройки wi-fi").args["section"]!!.jsonPrimitive.content)
        assertEquals("телеграм", tool("открой телеграм").args["name"]!!.jsonPrimitive.content)
        assertEquals("+79991234567", tool("позвони +7 999 123-45-67").args["number"]!!.jsonPrimitive.content)
        assertEquals(ToolNames.OPEN_CAMERA, tool("открой камеру").name)
    }

    @Test fun rememberKeepsCase() {
        val p = assertIs<Parsed.Remember>(CommandParser.parse("Запомни: мою сестру зовут Маша"))
        assertEquals("мою сестру зовут Маша", p.text)
        assertIs<Parsed.ListMemory>(CommandParser.parse("что ты помнишь?"))
    }

    @Test fun chitchatIsNotACommand() {
        assertNull(CommandParser.parse("как дела?"))
        assertNull(CommandParser.parse("расскажи анекдот про котов"))
    }
}

/** A timer for a dish whose usual time is known, without a model (Gemma 3 1B offered an omelette recipe instead, 0.16.0). */
class DishTimerTest {
    private fun tool(t: String) = CommandParser.parse(t) as? CommandParser.Parsed.Tool

    @Test fun usualTimesOfCommonDishes() = ru.iskra.core.i18n.withLang(ru.iskra.core.i18n.Lang.RU) {
        val eggs = tool("Сварю яйца всмятку — засеки, сколько нужно")!!
        assertEquals(ToolNames.SET_TIMER, eggs.name)
        assertEquals("240", eggs.args["seconds"]!!.jsonPrimitive.content)
        assertEquals("Яйца всмятку", eggs.args["label"]!!.jsonPrimitive.content)
        assertEquals("Засекла 4 минуты — яйца всмятку будут в самый раз — считай от закипания.", eggs.say)
        assertEquals("540", tool("Засеки, сколько варить яйца вкрутую")!!.args["seconds"]!!.jsonPrimitive.content)
        assertEquals("420", tool("Поставь таймер на пельмени")!!.args["seconds"]!!.jsonPrimitive.content)
        assertEquals("600", tool("Варю макароны, засеки")!!.args["seconds"]!!.jsonPrimitive.content)
        assertEquals("240", tool("Заварила чай — засеки время")!!.args["seconds"]!!.jsonPrimitive.content)
        // A time of its own, something else asked as well, a dish it doesn't know or no timer asked for: not this rule.
        assertNull(DishTimer.parse("Засеки 5 минут на яйца всмятку"))
        assertNull(DishTimer.parse("Засеки время на пельмени и напомни позвонить маме"))
        assertNull(DishTimer.parse("Жарю сырники — засеки, сколько нужно"))
        assertNull(DishTimer.parse("Сколько варить яйца всмятку?"))
        assertNull(DishTimer.parse("Нарисуй рисунок, засеки"))
        ru.iskra.core.i18n.withLang(ru.iskra.core.i18n.Lang.EN) {
            val e = tool("Boiling soft-boiled eggs, set a timer")!!
            assertEquals("240", e.args["seconds"]!!.jsonPrimitive.content)
            assertEquals("Timer set for 4 minutes — soft-boiled eggs will be just right — count from the boil.", e.say)
            assertEquals("600", tool("Set a timer for the pasta")!!.args["seconds"]!!.jsonPrimitive.content)
        }
    }

    @Test fun sheSetsItHerselfWithAnyBrain() = ru.iskra.core.i18n.withLang(ru.iskra.core.i18n.Lang.RU) {
        kotlinx.coroutines.runBlocking {
            val timer = RecordingTool()
            val model = object : ru.iskra.core.llm.LlmProvider {
                override val id = "local:test"
                override val onDevice = true
                override suspend fun complete(request: ru.iskra.core.llm.LlmRequest): ru.iskra.core.llm.LlmResponse = error("the model is not asked")
            }
            val agent = ru.iskra.core.agent.CompanionAgent(persona = ru.iskra.core.persona.Personas.ISKRA,
                memory = ru.iskra.core.memory.MemoryManager(ru.iskra.core.memory.InMemoryMemoryStore()),
                conversation = ru.iskra.core.agent.InMemoryConversationStore(), stateStore = ru.iskra.core.agent.InMemoryStateStore(),
                tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, localFactory = { model })
            val r = agent.handle("Сварю яйца всмятку — засеки, сколько нужно")
            assertEquals("Засекла 4 минуты — яйца всмятку будут в самый раз — считай от закипания.", r.text)
            assertEquals(240, timer.calls.single()["seconds"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test fun withoutToolsSheAsksHowLong() = ru.iskra.core.i18n.withLang(ru.iskra.core.i18n.Lang.RU) {
        kotlinx.coroutines.runBlocking {
            val light = object : ru.iskra.core.llm.LlmProvider {
                override val id = "local:light"
                override val onDevice = true
                override suspend fun complete(request: ru.iskra.core.llm.LlmRequest): ru.iskra.core.llm.LlmResponse =
                    ru.iskra.core.llm.LlmResponse("[happy] Мрр, интересно!", emptyList())
            }
            // A light model on the phone, which has no tools, and no model at all.
            for (brain in listOf<ru.iskra.core.llm.LlmProvider?>(light, null)) {
                val timer = RecordingTool()
                val agent = ru.iskra.core.agent.CompanionAgent(persona = ru.iskra.core.persona.Personas.ISKRA,
                    memory = ru.iskra.core.memory.MemoryManager(ru.iskra.core.memory.InMemoryMemoryStore()),
                    conversation = ru.iskra.core.agent.InMemoryConversationStore(), stateStore = ru.iskra.core.agent.InMemoryStateStore(),
                    tools = ToolRegistry(listOf(timer)), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, localFactory = { brain })
                assertEquals("Сколько минут засечь? Скажи, например: «7 минут» — и поставлю.", agent.handle("Жарю сырники — засеки, сколько нужно").text)
                assertTrue(timer.calls.isEmpty())
                agent.handle("минут семь")
                assertEquals(420, timer.calls.single()["seconds"]!!.jsonPrimitive.content.toInt())
                // Asked, and then something else: nothing is set, and the next bare number is not a timer.
                agent.handle("Жарю сырники — засеки, сколько нужно")
                agent.handle("А какая завтра погода?")
                agent.handle("5")
                assertEquals(1, timer.calls.size)
            }
        }
        val p = ru.iskra.core.agent.ActionPromise
        assertEquals(1800, p.duration("полчаса"))
        assertEquals(90, p.duration("1.5 минуты"))
        assertEquals(420, p.duration("7"))
        assertEquals(600, p.duration("на 10 мин."))
        assertEquals(30, p.duration("30 секунд"))
        assertEquals(600, p.duration("10 minutes"))
        assertNull(p.duration("через 5 минут позвони маме"))
        assertNull(p.duration("не знаю"))
    }

    @Test fun whatWasJustAskedIsAnsweredFromTheChat() = ru.iskra.core.i18n.withLang(ru.iskra.core.i18n.Lang.RU) {
        kotlinx.coroutines.runBlocking {
            val light = object : ru.iskra.core.llm.LlmProvider {
                override val id = "local:light"
                override val onDevice = true
                override suspend fun complete(request: ru.iskra.core.llm.LlmRequest): ru.iskra.core.llm.LlmResponse =
                    ru.iskra.core.llm.LlmResponse("[happy] Я просто пытаюсь помочь!", emptyList())
            }
            val agent = ru.iskra.core.agent.CompanionAgent(persona = ru.iskra.core.persona.Personas.ISKRA,
                memory = ru.iskra.core.memory.MemoryManager(ru.iskra.core.memory.InMemoryMemoryStore()),
                conversation = ru.iskra.core.agent.InMemoryConversationStore(), stateStore = ru.iskra.core.agent.InMemoryStateStore(),
                tools = ToolRegistry(listOf(RecordingTool())), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, localFactory = { light })
            assertEquals("Ты меня пока ни о чём не просишь — спрашивай, мрр!", agent.handle("О чём я тебя только что попросила?").text)
            agent.handle("Жарю сырники — засеки, сколько нужно.")
            assertEquals("Мрр, твоя последняя просьба: «Жарю сырники — засеки, сколько нужно.»", agent.handle("О чём я тебя только что попросила?").text)
            agent.handle("Мне сегодня грустно")
            assertEquals("Мрр, твоё последнее сообщение: «Мне сегодня грустно»", agent.handle("Что я тебе только что написал?").text)
        }
        val r = ru.iskra.core.agent.LastRequest
        assertTrue(r.asks("О чём я тебя только что попросила?"))
        assertTrue(r.asks("what did I just ask you?"))
        assertFalse(r.asks("О чём ты мечтаешь?"))
    }
}
