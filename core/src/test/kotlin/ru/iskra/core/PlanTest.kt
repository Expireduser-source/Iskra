package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.Turn
import ru.iskra.core.brain.MemoryTalk
import ru.iskra.core.brain.PlanTalk
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plans («завтра экзамен») and the new kinds of facts about the owner: tastes, age, family. */
class PlanTest {
    private val today = LocalDate.of(2026, 11, 6) // a Friday

    private fun one(text: String) = PlanTalk.hear(text, today).plans.let { assertEquals(1, it.size, "$text → $it"); it.single() }
    private fun item(text: String, id: Long = 1) = MemoryItem(id, MemoryKind.FACT, text, MemorySource.USER_EXPLICIT, 3, 0, 0)
    private fun ms(y: Int, m: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun hearsPlansInRussian() {
        one("Завтра у меня экзамен по физике").let {
            assertEquals(LocalDate.of(2026, 11, 7), it.date); assertEquals("экзамен по физике", it.what); assertEquals(PlanTalk.Kind.TRIAL, it.kind)
        }
        one("у меня в понедельник собеседование в Яндекс").let { assertEquals(LocalDate.of(2026, 11, 9), it.date); assertEquals("собеседование в Яндекс", it.what) }
        one("В пятницу иду к стоматологу").let {
            assertEquals(LocalDate.of(2026, 11, 13), it.date); assertEquals("визит к стоматологу", it.what); assertEquals(PlanTalk.Kind.HEALTH, it.kind)
        }
        one("послезавтра лечу в Сочи!").let { assertEquals(LocalDate.of(2026, 11, 8), it.date); assertEquals("поездка в Сочи", it.what); assertEquals(PlanTalk.Kind.TRIP, it.kind) }
        one("Через три дня концерт Земфиры, жду не дождусь").let { assertEquals(LocalDate.of(2026, 11, 9), it.date); assertEquals("концерт Земфиры", it.what) }
        one("12 ноября у брата свадьба").let { assertEquals(LocalDate.of(2026, 11, 12), it.date); assertEquals("свадьба у брата", it.what) }
        one("Брат завтра сдаёт экзамен").let { assertEquals("экзамен у брата", it.what) }
        one("сегодня вечером свидание с Машей").let { assertEquals(today, it.date); assertEquals("свидание с Машей", it.what) }
        one("на выходных идём в кино").let { assertEquals(LocalDate.of(2026, 11, 7), it.date); assertEquals("поход в кино", it.what) }
        one("Завтра контрольную пишем").let { assertEquals("контрольная", it.what); assertEquals('f', it.gender) }
        one("У меня завтра приём у врача в 10 утра").let { assertEquals("приём у врача", it.what); assertEquals(PlanTalk.Kind.HEALTH, it.kind) }
        one("Не будет времени, завтра экзамен").let { assertEquals("экзамен", it.what) }
        assertEquals(2, PlanTalk.hear("Завтра экзамен, а в субботу вечеринка", today).plans.size)
        one("Завтра первый рабочий день!").let { assertEquals("первый рабочий день", it.what) }
        one("в субботу иду на день рождения к другу").let { assertEquals("день рождения", it.what); assertEquals(PlanTalk.Kind.FUN, it.kind) }
        one("У меня завтра тест по английскому").let { assertEquals("тест по английскому", it.what) }
    }

    @Test fun hearsPlansInEnglish() {
        one("I have a physics exam tomorrow").let { assertEquals(LocalDate.of(2026, 11, 7), it.date); assertEquals("physics exam", it.what); assertFalse(it.ru) }
        one("My job interview at Google is on Monday").let { assertEquals(LocalDate.of(2026, 11, 9), it.date); assertEquals("job interview at Google", it.what) }
        one("Going to the dentist on Wednesday").let { assertEquals("dentist visit", it.what); assertEquals(PlanTalk.Kind.HEALTH, it.kind) }
        one("Flying to Paris on November 20").let { assertEquals(LocalDate.of(2026, 11, 20), it.date); assertEquals("trip to Paris", it.what) }
    }

    @Test fun notPlans() {
        for (t in listOf("Вчера был экзамен", "когда у меня экзамен?", "у него завтра экзамен", "экзамен был сложный", "напомни завтра про экзамен",
            "Завтра пойду гулять", "у меня завтра день рождения", "люблю концерты", "I'll test it tomorrow", "Did you have an exam today?",
            "She has an exam tomorrow", "I had a great date yesterday"))
            assertTrue(PlanTalk.hear(t, today).plans.isEmpty(), t)
    }

    @Test fun cancelAndMove() {
        PlanTalk.hear("Экзамен отменили!", today).let { assertEquals(listOf("экзамен"), it.cancelled); assertTrue(it.plans.isEmpty()) }
        PlanTalk.hear("Собеседование перенесли на среду", today).let {
            assertEquals(listOf("собеседование"), it.cancelled); assertEquals(LocalDate.of(2026, 11, 11), it.plans.single().date)
        }
        assertEquals(listOf("экзамен"), PlanTalk.hear("Завтра не будет экзамена", today).cancelled)
        assertEquals(listOf("meeting"), PlanTalk.hear("The meeting got cancelled", today).cancelled)
    }

    @Test fun memoryRoundTripAndLines() {
        val p = one("Завтра у меня экзамен по физике")
        assertEquals("Планы владельца на 7 ноября 2026: экзамен по физике", p.memoryText)
        val k = PlanTalk.known(listOf(item(p.memoryText))).single()
        assertEquals(p.date, k.plan.date); assertEquals(PlanTalk.Kind.TRIAL, k.plan.kind); assertFalse(k.done)
        assertTrue(PlanTalk.known(listOf(item(p.doneText))).single().done)
        assertEquals("Как прошёл экзамен по физике? Мрр, рассказывай!", PlanTalk.ask(k.plan))
        assertEquals("Сегодня экзамен по физике — держу за тебя лапки!", PlanTalk.wish(k.plan))
        assertEquals("Запомнила: завтра — экзамен по физике. Держу за тебя лапки!", PlanTalk.noted(p, today))
        val trip = PlanTalk.known(listOf(item(one("послезавтра лечу в Сочи!").memoryText))).single().plan
        assertEquals(PlanTalk.Kind.TRIP, trip.kind)
        assertEquals("Как поездка в Сочи? Мрр, рассказывай!", PlanTalk.ask(trip))
        val wedding = PlanTalk.known(listOf(item(one("12 ноября у брата свадьба").memoryText))).single().plan
        assertEquals("Как прошла свадьба у брата? Мрр, рассказывай!", PlanTalk.ask(wedding))
        val en = PlanTalk.known(listOf(item(one("I have a physics exam tomorrow").memoryText))).single().plan
        assertEquals(PlanTalk.Kind.TRIAL, en.kind)
        assertEquals("How did the physics exam go? Mrr, tell me!", PlanTalk.ask(en))
        assertTrue(PlanTalk.mentions(k.plan, "Сдал физику на пятёрку!"))
        assertFalse(PlanTalk.mentions(k.plan, "Пойду погуляю"))
    }

    @Test fun dueAfterTheDay() {
        val known = PlanTalk.known(listOf(item("Планы владельца на 6 ноября 2026: экзамен по физике")))
        assertNull(PlanTalk.due(known, today, 10))             // the morning of the exam: too early
        assertNotNull(PlanTalk.due(known, today, 20))          // the same evening
        assertNotNull(PlanTalk.due(known, today.plusDays(1), 9))
        assertNull(PlanTalk.due(known, today.plusDays(5), 9))  // too long ago to ask
        val party = PlanTalk.known(listOf(item("Планы владельца на 6 ноября 2026: вечеринка")))
        assertNull(PlanTalk.due(party, today, 20))             // a party goes on in the evening: ask tomorrow
    }

    @Test fun offlineAgentRemembersPlansAndAsksOnce() = withLang(Lang.RU) {
        runBlocking {
            var now = ms(2026, 11, 6, 10)
            val memory = MemoryManager(InMemoryMemoryStore())
            val convo = InMemoryConversationStore()
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            assertEquals("Запомнила: завтра — экзамен по физике. Держу за тебя лапки!", a.handle("Завтра у меня экзамен по физике").text)
            assertEquals("Планы владельца на 7 ноября 2026: экзамен по физике", memory.all().single().text)
            assertTrue(a.handle("какие у меня планы на завтра?").text.contains("завтра — экзамен по физике"))
            assertEquals("Завтра экзамен по физике — выспись как следует, мрр.", a.planLine(CompanionAgent.PlanMoment.EVENING))
            now = ms(2026, 11, 7, 8)
            assertEquals("Сегодня экзамен по физике — держу за тебя лапки!", a.planLine(CompanionAgent.PlanMoment.MORNING))
            assertNull(a.planLine(CompanionAgent.PlanMoment.ANY))
            now = ms(2026, 11, 8, 12)
            val r = a.handle("привет")
            assertTrue(r.text.contains("Кстати, как прошёл экзамен по физике?"), r.text)
            assertTrue(memory.all().single().text.startsWith("7 ноября 2026 у владельца было"))
            assertFalse(a.handle("привет").text.contains("экзамен"))
        }
    }

    @Test fun ownerTellsHowItWentBeforeBeingAsked() = withLang(Lang.RU) {
        runBlocking {
            var now = ms(2026, 11, 6, 10)
            val memory = MemoryManager(InMemoryMemoryStore())
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            a.handle("сегодня собеседование в Яндекс")
            now = ms(2026, 11, 6, 17)
            a.handle("Собеседование прошло отлично!")
            assertTrue(PlanTalk.known(memory.all()).single().done)
            now = ms(2026, 11, 7, 12)
            assertNull(a.planLine(CompanionAgent.PlanMoment.ANY))
        }
    }

    @Test fun planMovesAndIsCalledOff() = withLang(Lang.RU) {
        runBlocking {
            val now = ms(2026, 11, 6, 10)
            val memory = MemoryManager(InMemoryMemoryStore())
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            a.handle("В понедельник собеседование в Яндекс")
            a.handle("Собеседование перенесли на среду")
            assertEquals(LocalDate.of(2026, 11, 11), PlanTalk.known(memory.all()).single().plan.date)
            assertEquals("Поняла, вычёркиваю: собеседование в Яндекс.", a.handle("Собеседование отменили").text)
            assertTrue(memory.all().isEmpty())
        }
    }

    @Test fun proactiveQuestionGoesIntoTheDialogue() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add("Планы владельца на 5 ноября 2026: концерт Земфиры", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, 0)
            val convo = InMemoryConversationStore()
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { ms(2026, 11, 6, 12) }, random = Random(1))
            assertEquals("Как прошёл концерт Земфиры? Мрр, рассказывай!", a.planLine(CompanionAgent.PlanMoment.ANY))
            assertEquals(Turn.Role.ASSISTANT, convo.recent(1).single().role)
            assertNull(a.planLine(CompanionAgent.PlanMoment.ANY))
        }
    }

    @Test fun theModelHearsTheNotes() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add("Планы владельца на 5 ноября 2026: экзамен по физике", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, 0)
            val provider = FakeProvider(mutableListOf({ _ -> LlmResponse("[happy] Привет! Как прошёл экзамен по физике?", emptyList()) },
                { _ -> LlmResponse("[happy] Отлично!", emptyList()) }))
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { provider }, clock = { ms(2026, 11, 6, 12) }, random = Random(1))
            a.handle("Привет!")
            assertTrue(provider.requests[0].situation.contains("вчера у владельца было: экзамен по физике — спроси, как прошло"), provider.requests[0].situation)
            // She asked: no second reminder.
            a.handle("Как дела?")
            assertFalse(provider.requests[1].situation.contains("спроси, как прошло"))
        }
    }

    @Test fun tastesAgeAndFamily() = withLang(Lang.RU) {
        fun f(t: String) = MemoryTalk.fact(t, today)
        assertEquals("Владелец любит пиццу", f("Я очень люблю пиццу")?.text)
        assertEquals("Владелец любит гулять по парку", f("я люблю гулять по парку, особенно осенью")?.text)
        assertEquals("Владелец любит, когда идёт дождь", f("Люблю, когда идёт дождь")?.text)
        assertEquals("Владелец терпеть не может рано вставать", f("Терпеть не могу рано вставать")?.text)
        assertEquals("Владельцу нравятся кошки", f("Мне нравятся кошки")?.text)
        assertNull(f("я люблю тебя"))
        assertNull(f("мне нравится, как ты говоришь"))
        assertNull(f("Мне нравится эта идея"))
        assertEquals("Любимый фильм владельца — Интерстеллар", f("Мой любимый фильм — Интерстеллар")?.text)
        assertEquals("Любимая еда владельца — пицца", f("моя любимая еда это пицца")?.text)
        assertEquals("Владельцу 25 лет (на ноябрь 2026)", f("Мне двадцать пять лет")?.text)
        assertEquals("Владельцу 21 год (на ноябрь 2026)", f("мне 21")?.text)
        assertEquals("Сестру владельца зовут Аня", f("Мою сестру зовут Аня")?.text)
        assertEquals("Младшего брата владельца зовут Петя", f("у меня есть младший брат Петя")?.text)
        assertEquals("Маму владельца зовут Ольга", f("у меня мама, её зовут Ольга")?.text)
        assertNull(f("у меня мама врач"))
        assertEquals("День рождения владельца: 7 ноября", f("у меня завтра день рождения")?.text)
        assertTrue(f("Сегодня мой день рождения")!!.reply.contains("С днём рождения"))
        assertNull(f("мой день рождения был вчера"))
        // A memory of the owner's sister is not the owner's name.
        assertNull(MemoryTalk.ownerName(listOf(item("Сестру владельца зовут Аня"))))
        withLang(Lang.EN) {
            assertEquals("The owner loves jazz", f("I love jazz")?.text)
            assertEquals("The owner doesn't like rain", f("I don't like rain")?.text)
            assertNull(f("I love you"))
            assertEquals("The owner's favorite movie is Interstellar", f("My favorite movie is Interstellar")?.text)
            assertEquals("The owner is 30 (as of November 2026)", f("I'm 30 years old")?.text)
            assertEquals("The owner's sister is named Anna", f("My sister's name is Anna")?.text)
            assertEquals("The owner's birthday: November 7", f("my birthday is tomorrow")?.text)
        }
    }

    @Test fun recallsTastesAgeFamily() = withLang(Lang.RU) {
        val mem = listOf("Владелец любит пиццу", "Владелец любит джаз", "Владелец не любит дождь", "Любимый фильм владельца — Интерстеллар",
            "Владельцу 25 лет (на ноябрь 2025)", "Сестру владельца зовут Аня", "Младшего брата владельца зовут Петя", "Владельца зовут Аня Смирнова")
            .mapIndexed { i, t -> item(t, i.toLong()) }
        assertEquals("Мрр, помню: ты любишь пиццу, и джаз.", MemoryTalk.recall("Что я люблю?", mem, today))
        assertEquals("Мрр, помню: ты не любишь дождь.", MemoryTalk.recall("помнишь, что я не люблю?", mem, today))
        assertEquals("Мрр, помню: твой любимый фильм — Интерстеллар.", MemoryTalk.recall("какой мой любимый фильм?", mem, today))
        assertEquals("Мрр, помню: тебе 26 лет.", MemoryTalk.recall("сколько мне лет?", mem, today))
        assertEquals("Мрр, помню: твою сестру зовут Аня, и твоего младшего брата зовут Петя.", MemoryTalk.recall("как зовут мою сестру и как зовут моего брата?", mem, today))
        assertEquals("Мрр, помню: тебя зовут Аня Смирнова.", MemoryTalk.recall("как меня зовут?", mem, today))
        val all = MemoryTalk.recall("что ты обо мне знаешь?", mem, today)!!
        assertTrue(all.contains("тебе 26 лет") && all.contains("твою сестру зовут Аня") && all.contains("ты любишь пиццу"), all)
        assertNull(MemoryTalk.recall("какая моя любимая песня?", mem, today))
        withLang(Lang.EN) {
            val en = listOf("The owner loves jazz", "The owner's sister is named Anna", "The owner is 30 (as of November 2026)").mapIndexed { i, t -> item(t, i.toLong()) }
            assertEquals("Mrr, I remember: you love jazz.", MemoryTalk.recall("what do I like?", en, today))
            assertEquals("Mrr, I remember: your sister's name is Anna.", MemoryTalk.recall("what's my sister's name?", en, today))
            assertEquals("Mrr, I remember: you're 30.", MemoryTalk.recall("how old am I?", en, today))
        }
    }
}
