package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.brain.Calc
import ru.iskra.core.brain.Chance
import ru.iskra.core.brain.MemoryTalk
import ru.iskra.core.brain.NumberWords
import ru.iskra.core.brain.OfflineBrain
import ru.iskra.core.brain.TimeTalk
import ru.iskra.core.brain.Units
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainTest {
    private val now = LocalDateTime.of(2026, 11, 5, 14, 23)   // Thursday

    @Test
    fun numberWordsBecomeDigits() {
        assertEquals("25 умножить на 4", NumberWords.digitize("двадцать пять умножить на четыре"))
        assertEquals("2026", NumberWords.digitize("две тысячи двадцать шесть"))
        assertEquals("в 7 30", NumberWords.digitize("в семь тридцать"))
        assertEquals("таймер на 1.5 часа", NumberWords.digitize("таймер на полтора часа"))
        assertEquals("105 and 25", NumberWords.digitize("one hundred and five and twenty-five"))
        assertEquals("1000 рублей", NumberWords.digitize("тысяча рублей"))
        assertEquals("1200000", NumberWords.digitize("миллион двести тысяч"))
        assertEquals("купить хлеб", NumberWords.digitize("купить хлеб"))
        assertEquals("из 5", NumberWords.digitize("из пяти"))
    }

    @Test
    fun calculatorIsExact() {
        assertEquals(14.0, Calc.eval("2+3*4"))
        assertEquals(20.0, Calc.eval("(2+3)*4"))
        assertEquals(-4.0, Calc.eval("-2^2"))
        assertEquals(1024.0, Calc.eval("2^10"))
        assertEquals(2645.0, Calc.eval("2300 + 15%"))
        assertEquals(345.0, Calc.eval("15 %of 2300"))
        assertEquals(30.0, Calc.eval("200 * 15%"))
        assertEquals(12.0, Calc.eval("sqrt 144"))
        assertEquals(0.5, Calc.eval("sin 30"))
        assertEquals(120.0, Calc.eval("5!"))
        assertEquals(14.0, Calc.eval("2(3+4)"))
        assertEquals(2.5, Calc.eval("5/2"))
        assertEquals(10000.0 * 3, Calc.eval("10 000 * 3"))
        assertNull(Calc.eval("привет"))
        assertEquals(2026.0, Calc.eval("2026"))
        assertNull(Calc.answer("2026"))
        assertTrue(Calc.evaluate("5/0") is Calc.Outcome.Error)
    }

    @Test
    fun spokenSums() = withLang(Lang.RU) {
        assertEquals("25 × 4 = 100", Calc.answer("Сколько будет двадцать пять умножить на четыре?"))
        assertEquals("15% от 2300 = 345", Calc.answer("посчитай 15 процентов от 2300"))
        assertEquals("√144 = 12", Calc.answer("корень из 144"))
        assertEquals("2 + 2 × 2 = 6", Calc.answer("2+2*2"))
        assertEquals("7 ÷ 2 = 3,5", Calc.answer("раздели 7 на 2"))
        assertEquals("100 − 37 = 63", Calc.answer("отними 37 от 100"))
        assertEquals("12 × 12 = 144", Calc.answer("12 х 12"))
        assertTrue(Calc.answer("сколько будет 5 разделить на 0")!!.contains("ноль"))
        assertNull(Calc.answer("сколько времени"))
        assertNull(Calc.answer("сколько стоит хлеб"))
        assertNull(Calc.answer("сколько дней до нового года"))
        assertNull(Calc.answer("поставь таймер на 5 минут"))
        assertEquals("12 345 × 2 = 24 690", Calc.answer("12345*2"))
        withLang(Lang.EN) {
            assertEquals("15% of 80 = 12", Calc.answer("what's 15 percent of 80"))
            assertEquals("7 × 8 = 56", Calc.answer("what is seven times eight"))
            assertEquals("12,345 × 2 = 24,690", Calc.answer("12,345 * 2"))
            assertNull(Calc.answer("what time is it"))
        }
    }

    @Test
    fun unitConversion() = withLang(Lang.RU) {
        assertEquals("5 миль = 8,047 км.", Units.answer("переведи 5 миль в километры"))
        assertEquals("1 дюйм = 2,54 см.", Units.answer("сколько сантиметров в дюйме"))
        assertEquals("100 °F = 37,78 °C.", Units.answer("100 фаренгейт в цельсии"))
        assertEquals("1 час = 60 минут.", Units.answer("сколько минут в часе"))
        assertEquals("1 ГБ = 1024 МБ.", Units.answer("1 гб в мб"))
        assertEquals("100 км/ч = 62,14 миль/ч.", Units.answer("100 км в час в мили в час"))
        assertTrue(Units.answer("5 км в кг")!!.startsWith("Не получится"))
        assertNull(Units.answer("сколько дней до лета"))
        withLang(Lang.EN) {
            assertEquals("1 pound = 16 ounces.", Units.answer("how many ounces in a pound"))
            assertEquals("10 kg = 22.05 pounds.", Units.answer("convert 10 kg to lbs"))
            assertEquals("30 °C = 86 °F.", Units.answer("30 celsius to fahrenheit"))
        }
    }

    @Test
    fun datesAndTime() = withLang(Lang.RU) {
        assertEquals("Сейчас 14:23.", TimeTalk.answer("Который час?", now))
        assertEquals("Сегодня четверг, 5 ноября.", TimeTalk.answer("какое сегодня число", now))
        assertEquals("Завтра пятница, 6 ноября.", TimeTalk.answer("какой день недели будет завтра", now))
        assertEquals("До нового года осталось 57 дней — это пятница, 1 января 2027.", TimeTalk.answer("сколько дней до нового года", now))
        assertEquals("До 8 марта осталось 123 дня — это понедельник, 8 марта 2027.", TimeTalk.answer("сколько осталось до восьмого марта", now))
        assertEquals("1 января 2027 — пятница.", TimeTalk.answer("какой день недели будет первого января", now))
        assertEquals("С 1 сентября прошло 65 дней (вторник, 1 сентября).", TimeTalk.answer("сколько дней прошло с 1 сентября", now))
        assertEquals("Это уже завтра — пятница, 6 ноября.", TimeTalk.answer("сколько дней до пятницы", now))
        assertEquals("Это будет воскресенье, 15 ноября.", TimeTalk.answer("какое число будет через 10 дней", now))
        assertEquals("Сейчас 2026 год.", TimeTalk.answer("какой сейчас год", now))
        assertNull(TimeTalk.answer("поставь будильник на 7", now))
        withLang(Lang.EN) {
            assertEquals("It's 2:23 PM.", TimeTalk.answer("what time is it", now))
            assertEquals("Today is Thursday, November 5.", TimeTalk.answer("what's the date", now))
            assertEquals("50 days until christmas — Friday, December 25.", TimeTalk.answer("how many days until Christmas?", now))
            assertEquals("January 1, 2027 is a Friday.", TimeTalk.answer("what day of the week is January 1st", now))
        }
    }

    @Test
    fun coinsDiceAndChoices() = withLang(Lang.RU) {
        val r = Random(7)
        assertTrue(Chance.answer("подбрось монетку", r) in setOf("Орёл!", "Решка!"))
        assertTrue(Chance.answer("брось кубик", r)!!.matches(Regex("""Выпало [1-6]\.""")))
        val n = Chance.answer("случайное число от 1 до 10", r)!!.removePrefix("Пусть будет ").removeSuffix(".").toInt()
        assertTrue(n in 1..10)
        assertTrue(Chance.answer("выбери: пицца или суши", r)!!.let { it.contains("пицца") || it.contains("суши") })
        assertNull(Chance.answer("выбери фильм", r))
    }

    @Test
    fun smallTalkWithoutAModel() = withLang(Lang.RU) {
        val b = OfflineBrain(Random(1))
        assertTrue(b.chat("Привет!", now, Emotion.NEUTRAL, "Аня")!!.text.contains("Аня"))
        assertTrue(b.chat("привет, как дела?", now, Emotion.HAPPY, null)!!.text.startsWith("Привет! "))
        assertNotNull(b.chat("кто ты", now, Emotion.NEUTRAL, null))
        assertNotNull(b.chat("расскажи шутку", now, Emotion.NEUTRAL, null))
        assertNotNull(b.chat("Искра, спасибо!", now, Emotion.NEUTRAL, null))
        assertTrue(b.chat("ты человек?", now, Emotion.NEUTRAL, null)!!.text.contains("программа"))
        assertNull(b.chat("как сварить борщ", now, Emotion.NEUTRAL, null))
        assertEquals(Emotion.THINKING, b.fallback("как сварить борщ?").emotion)
        assertTrue(b.crisis("я не хочу больше жить")!!.text.contains("112"))
        assertNull(b.crisis("хочу жить у моря"))
        withLang(Lang.EN) {
            assertNotNull(b.chat("how are you", now, Emotion.NEUTRAL, null))
            assertTrue(b.crisis("I want to die")!!.text.contains("988"))
        }
    }

    @Test
    fun factsFromWhatTheOwnerSays() = withLang(Lang.RU) {
        assertEquals("Владельца зовут Аня", MemoryTalk.fact("меня зовут аня")!!.text)
        assertEquals("Владельца зовут Анна Петровна", MemoryTalk.fact("Привет, меня зовут Анна Петровна")!!.text)
        assertNull(MemoryTalk.fact("меня зовут на работу"))
        assertEquals("Владелец живёт в Казани", MemoryTalk.fact("я живу в казани")!!.text)
        assertEquals("День рождения владельца: 12 мая", MemoryTalk.fact("мой день рождения 12 мая")!!.text)
        assertEquals("", MemoryTalk.forgetQuery("забудь всё"))
        assertEquals("я живу в Москве", MemoryTalk.forgetQuery("Забудь, что я живу в Москве"))
        assertNull(MemoryTalk.forgetQuery("не забудь купить хлеб"))
        assertEquals("Владелец учится на врача", MemoryTalk.fact("Я учусь на врача, сейчас сессия.")!!.text)
        assertEquals("Владелец работает программистом", MemoryTalk.fact("я работаю программистом")!!.text)
        assertEquals("Владелец работает в банке уже год", MemoryTalk.fact("Я работаю в банке уже год")!!.text)
        assertEquals("У владельца есть кот по имени Барсик", MemoryTalk.fact("у меня есть кот по имени барсик")!!.text)
        assertNull(MemoryTalk.fact("я работаю"))
        withLang(Lang.EN) {
            assertEquals("The owner's name is John", MemoryTalk.fact("my name is John")!!.text)
            assertEquals("The owner studies medicine", MemoryTalk.fact("I study medicine")!!.text)
            assertEquals("The owner has a dog named Rex", MemoryTalk.fact("I have a dog named rex")!!.text)
        }
    }

    @Test
    fun questionsAboutTheOwnerAreAnsweredFromMemoryExactly() = withLang(Lang.RU) {
        val m = MemoryManager(InMemoryMemoryStore())
        // Facts from every sentence of one message.
        val said = MemoryTalk.facts("Меня зовут Аня. Я учусь на врача, сейчас сессия.")
        assertEquals(listOf("Владельца зовут Аня", "Владелец учится на врача"), said.map { it.text })
        assertEquals("Владелец учится играть на гитаре", MemoryTalk.fact("я учусь играть на гитаре")!!.text)
        (said.map { it.text } + "У владельца есть кошка по имени Мурка").forEachIndexed { i, t ->
            m.add(t, MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, 1_000L + i)
        }
        m.add("Владелец любит зелёный чай", MemoryKind.FACT, MemorySource.MODEL_PROPOSED, 3, 2_000L)
        val all = m.all()
        assertEquals("Мрр, помню: тебя зовут Аня, и ты учишься на врача.", MemoryTalk.recall("Как меня зовут и на кого я учусь?", all))
        assertEquals("Мрр, помню: твою кошку зовут Мурка.", MemoryTalk.recall("А как зовут мою кошку?", all))
        assertEquals("Вот что я о тебе знаю: тебя зовут Аня, ты учишься на врача и у тебя есть кошка по имени Мурка. " +
            "И ещё записей: 1 — их видно на вкладке «Память».", MemoryTalk.recall("Искра, что ты обо мне знаешь?", all))
        // Something memory doesn't hold, or a part that isn't about the owner: the model answers.
        assertNull(MemoryTalk.recall("Где я живу?", all))
        assertNull(MemoryTalk.recall("Как меня зовут и почему небо голубое?", all))
        assertNull(MemoryTalk.recall("Как зовут твоего кота?", all))
        withLang(Lang.EN) {
            val en = MemoryManager(InMemoryMemoryStore())
            val told = MemoryTalk.facts("My name is Tom. I'm learning to play the guitar.")
            assertEquals(listOf("The owner's name is Tom", "The owner is learning to play the guitar"), told.map { it.text })
            told.forEach { en.add(it.text, MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, 1_000L) }
            assertEquals("Mrr, I remember: your name is Tom, and you're learning to play the guitar.",
                MemoryTalk.recall("What’s my name, and what am I learning?", en.all()))
            assertNull(MemoryTalk.recall("Where do I work?", en.all()))
        }
    }

    @Test
    fun birthdaysFromMemory() = withLang(Lang.RU) {
        assertEquals(java.time.MonthDay.of(5, 12), TimeTalk.monthDay("12 мая 1990"))
        assertEquals(java.time.MonthDay.of(5, 12), TimeTalk.monthDay("двенадцатого мая"))
        assertEquals(java.time.MonthDay.of(3, 8), TimeTalk.monthDay("08.03"))
        assertEquals(java.time.MonthDay.of(7, 4), TimeTalk.monthDay("July 4th"))
        val items = listOf(ru.iskra.core.memory.MemoryItem(1, MemoryKind.FACT, "День рождения владельца: 12 мая", MemorySource.USER_EXPLICIT, 5, 0, 0))
        assertEquals(java.time.MonthDay.of(5, 12), MemoryTalk.birthday(items))
        assertTrue(MemoryTalk.birthdayGreeting("Аня").startsWith("С днём рождения, Аня!"))
    }

    private fun agent(memory: MemoryManager) = CompanionAgent(
        persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
        tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null },
        clock = { java.time.ZonedDateTime.of(now, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }, random = Random(3),
    )

    @Test
    fun agentAnswersOfflineAndRemembers() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            val a = agent(memory)
            assertEquals("25 × 4 = 100", a.handle("сколько будет двадцать пять умножить на четыре").text)
            assertTrue(a.handle("Меня зовут Аня").text.contains("Аня"))
            assertEquals("Владельца зовут Аня", memory.all().single().text)
            assertTrue(a.handle("как меня зовут?").text.contains("Аня"))
            assertTrue(a.handle("привет").text.contains("Аня"))
            a.handle("мой день рождения 12 мая")
            assertTrue(a.handle("сколько дней до моего дня рождения").text.startsWith("До 12 мая осталось 188 дней"))
            memory.add("Владелец живёт в Москве", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, 0)
            assertTrue(a.handle("забудь, что я живу в Москве").text.startsWith("Забыла"))
            assertTrue(memory.all().none { it.text.contains("Москве") })
            assertTrue(a.handle("забудь всё").text.contains("не буду"))
            assertEquals(2, memory.all().size)
            // Unknown things without a model: an honest hint, not silence.
            assertTrue(a.handle("почему небо голубое?").text.contains("мозг"))
        }
    }
}
