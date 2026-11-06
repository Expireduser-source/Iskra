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

/** «Вчера мне было грустно» → today's first words are «как ты?», from the chat alone. */
class CareTest {
    private val zone = ZoneId.systemDefault()
    private fun ms(y: Int, m: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun user(text: String, at: Long) = Turn(Turn.Role.USER, text, at)

    @Test fun hearsHowTheOwnerIs() {
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.LOW, ru.iskra.core.brain.CareTalk.heard("Мне сегодня так грустно"))
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.SICK, ru.iskra.core.brain.CareTalk.heard("кажется, я заболела, температура 38"))
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.QUARREL, ru.iskra.core.brain.CareTalk.heard("Поругался с другом"))
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.LOW, ru.iskra.core.brain.CareTalk.heard("I feel so lonely"))
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.SICK, ru.iskra.core.brain.CareTalk.heard("I caught a cold"))
        for (t in listOf("мне не грустно", "мне не плохо", "фильм грустный", "плохо работает интернет", "I'm fine"))
            assertNull(ru.iskra.core.brain.CareTalk.heard(t), t)
    }

    @Test fun asksTheNextDayOnce() {
        val today = LocalDate.of(2026, 11, 7)
        val sad = listOf(user("Мне сегодня грустно", ms(2026, 11, 6, 20)))
        assertEquals("Как ты сегодня? Вчера тебе было нелегко — надеюсь, сегодня светлее. Мрр.",
            ru.iskra.core.brain.CareTalk.pending(sad, today, zone)?.let { ru.iskra.core.brain.CareTalk.ask(it) })
        // Said later that day that it passed; or already talked today; or it was two days ago.
        assertNull(ru.iskra.core.brain.CareTalk.pending(sad + user("уже лучше, спасибо", ms(2026, 11, 6, 22)), today, zone))
        assertNull(ru.iskra.core.brain.CareTalk.pending(sad + user("привет", ms(2026, 11, 7, 9)), today, zone))
        assertNull(ru.iskra.core.brain.CareTalk.pending(sad, today.plusDays(1), zone))
    }

    @Test fun offlineAndProactive() = withLang(Lang.RU) {
        runBlocking {
            var now = ms(2026, 11, 6, 20)
            val convo = InMemoryConversationStore()
            val a = CompanionAgent(persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            a.handle("кажется, я заболел")
            now = ms(2026, 11, 7, 10)
            assertTrue(a.handle("привет").text.endsWith("Как самочувствие? Мрр, выздоравливай скорее — я за тебя переживаю."))
            assertFalse(a.handle("привет").text.contains("самочувствие"))
            // The next time: she asks on her own, and the question is in the dialogue.
            now = ms(2026, 11, 7, 21)
            a.handle("опять поссорился с братом")
            now = ms(2026, 11, 8, 9)
            assertEquals("Как ты? Помирились после вчерашнего? Мрр, надеюсь, всё наладилось.", a.planLine(CompanionAgent.PlanMoment.ANY))
            assertEquals(Turn.Role.ASSISTANT, convo.recent(1).single().role)
            assertNull(a.planLine(CompanionAgent.PlanMoment.ANY))
        }
    }
}

/** «я устала» → she addresses the owner «ты устала»; learned from the owner's own words only. */
class GenderTest {
    @Test fun readsHowTheOwnerSpeaksOfThemselves() {
        val g = ru.iskra.core.brain.SelfGender
        assertEquals('f', g.of("Я сегодня так устала"))
        assertEquals('f', g.of("я рада тебя видеть"))
        assertEquals('f', g.of("Вчера я не спала до трёх"))
        assertEquals('m', g.of("я устал"))
        assertEquals('m', g.of("Я уже пришёл домой"))
        assertEquals('m', g.of("я рад"))
        for (t in listOf("я футбол не смотрю", "я сериал люблю", "Ты сказала: «я рада»", "я иду домой", "мы устали", "она устала"))
            assertNull(g.of(t), t)
        assertEquals('f', g.decide(listOf("я устала", "привет", "я так рада")))
        assertNull(g.decide(listOf("я устала")))                         // one is not enough
        assertNull(g.decide(listOf("я устала", "я рада", "я устал")))     // disagreeing
    }

    /** «Сдала на пятёрку!» says it as well as «я сдала» (ARM evaluation: E2B then called her «ты такой милый»). */
    @Test fun aSentenceThatBeginsWithTheVerb() {
        val g = ru.iskra.core.brain.SelfGender
        for (t in listOf("Сдала на пятёрку!", "Устала.", "Устала", "Вернулась домой", "Привет! Пришла с работы.", "Была у врача, всё хорошо", "Проснулась только что"))
            assertEquals('f', g.of(t), t)
        for (t in listOf("Сдал на пятёрку!", "Вернулся!", "Пришёл домой", "Устал."))
            assertEquals('m', g.of(t), t)
        // Not about the owner: a subject of its own, a question to her, a noun or a name.
        for (t in listOf("Пришла посылка", "Устала?", "Акула на пляже!", "Павел на работе", "Школа.", "Сказала мама, что поздно", "Стул на кухне сломался"))
            assertNull(g.of(t), t)
        assertEquals('f', g.decide(listOf("Привет, я вернулась!", "Сдала на пятёрку!")))
    }

    @Test fun theModelIsToldHowToAddress() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            val provider = FakeProvider(MutableList(3) { { _: ru.iskra.core.llm.LlmRequest -> LlmResponse("[happy] Мрр!", emptyList()) } })
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { provider }, random = Random(1))
            a.handle("Я сегодня так устала")
            assertTrue(provider.requests[0].system.contains("Род собеседника пока неизвестен"))
            a.handle("но я рада, что ты тут")
            assertEquals(ru.iskra.core.brain.SelfGender.FEMININE_RU, memory.all().single().text)
            a.handle("Привет!")
            assertTrue(provider.requests[2].system.contains("обращайся к ней так же: «ты устала»"), provider.requests[2].system)
        }
    }
}

/** A new fact replaces the old one about the same thing, so memory never holds both. */
class FactSlotTest {
    @Test fun newFactsReplaceOldOnes() = withLang(Lang.RU) {
        runBlocking {
            var forgets = 0
            val memory = MemoryManager(InMemoryMemoryStore(), onForget = { forgets++ })
            var now = 1_000L
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            for (t in listOf("Я живу в Москве", "Я люблю пиццу", "Мне 25 лет", "Мою сестру зовут Аня", "У меня есть кот по имени Барсик")) { a.handle(t); now += 1000 }
            for (t in listOf("Я живу в Казани", "Я не люблю пиццу", "Мне 26 лет", "Мою сестру зовут Анна", "У меня есть собака по имени Рекс")) { a.handle(t); now += 1000 }
            val texts = memory.all().map { it.text }.toSet()
            assertTrue("Владелец живёт в Казани" in texts && "Владелец живёт в Москве" !in texts, texts.toString())
            assertTrue("Владелец не любит пиццу" in texts && "Владелец любит пиццу" !in texts, texts.toString())
            assertTrue(texts.any { it.startsWith("Владельцу 26 лет") } && texts.none { it.startsWith("Владельцу 25 лет") }, texts.toString())
            assertTrue("Сестру владельца зовут Анна" in texts && "Сестру владельца зовут Аня" !in texts, texts.toString())
            // Pets add up.
            assertTrue(texts.count { it.startsWith("У владельца есть") } == 2, texts.toString())
            assertEquals("taste:пиццу", MemoryTalk.slot("Владелец любит пиццу"))
            assertNull(MemoryTalk.slot("Владелец учится играть на гитаре"))
            // An update is not «forget»: the phone model's cached dialogue stays (no slow reload).
            assertEquals(0, forgets)
        }
    }
}

/** «У мамы день рождения 12 марта»: a yearly date; she reminds to congratulate the evening before and on the day. */
class TheirBirthdayTest {
    private val today = LocalDate.of(2026, 11, 6)
    private fun f(t: String) = MemoryTalk.fact(t, today)

    @Test fun hearsBirthdaysOfClosePeople() = withLang(Lang.RU) {
        assertEquals("День рождения мамы владельца: 12 марта", f("У мамы день рождения 12 марта")?.text)
        assertEquals("День рождения брата владельца: 3 мая", f("день рождения у брата 3 мая")?.text)
        assertEquals("День рождения младшей сестры владельца: 7 ноября", f("у моей младшей сестры завтра день рождения")?.text)
        assertEquals("День рождения старшего брата владельца: 1 июня", f("у старшего брата день рождения 1 июня")?.text)
        assertEquals("День рождения папы владельца: 5 июня", f("Папа родился 5 июня")?.text)
        assertEquals("День рождения Маши: 9 ноября", f("У Маши в понедельник день рождения")?.text)
        assertEquals("День рождения владельца: 7 ноября", f("у меня завтра день рождения")?.text)
        assertNull(f("когда у мамы день рождения?"))
        assertNull(f("у мамы был день рождения вчера"))
        // Not a one-off plan as well.
        assertTrue(PlanTalk.hear("Завтра у мамы день рождения", today).plans.isEmpty())
        withLang(Lang.EN) { assertEquals("The owner's mom's birthday: March 12", f("My mom's birthday is March 12")?.text) }
        val mem = listOf(MemoryItem(1, MemoryKind.FACT, "День рождения мамы владельца: 12 марта", MemorySource.USER_EXPLICIT, 3, 0, 0))
        assertEquals("Мрр, помню: у мамы день рождения 12 марта.", MemoryTalk.recall("Когда у мамы день рождения?", mem, today))
        assertEquals("Мрр, помню: у мамы день рождения 12 марта.", MemoryTalk.recall("когда день рождения у моей мамы", mem, today))
    }

    @Test fun remindsToCongratulate() = withLang(Lang.RU) {
        runBlocking {
            var now = java.time.LocalDateTime.of(2027, 3, 11, 21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add("День рождения мамы владельца: 12 марта", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, 0)
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            assertEquals("Завтра день рождения у мамы — не забудь поздравить, мрр.", a.planLine(CompanionAgent.PlanMoment.EVENING))
            now += 12 * 3_600_000L
            assertEquals("Сегодня день рождения у мамы — не забудь поздравить! Мрр.", a.planLine(CompanionAgent.PlanMoment.MORNING))
        }
    }
}

/** Everyday phrases that once went wrong. */
class EverydayPhrasesTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test fun regressions() {
        // «не» inside «Мне» is not a negation.
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.LOW, ru.iskra.core.brain.CareTalk.heard("Мне грустно"))
        assertEquals(ru.iskra.core.brain.CareTalk.Kind.LOW, ru.iskra.core.brain.CareTalk.heard("Сегодня был тяжёлый день на работе"))
        assertNull(ru.iskra.core.brain.CareTalk.heard("мне не грустно"))
        assertEquals("Маму владельца зовут Елена Петровна", MemoryTalk.fact("Мою маму зовут Елена Петровна", today)?.text)
        PlanTalk.hear("Встреча с клиентом перенесена на четверг", today).let { assertEquals("встреча с клиентом", it.plans.single().what) }
        assertEquals(today, PlanTalk.hear("Иду к врачу", today).plans.single().date)
        assertEquals(LocalDate.of(2026, 11, 7), PlanTalk.hear("I'm going to a party on Saturday", today).plans.single().date)
        assertEquals("совещание", PlanTalk.hear("Совещание завтра в 10", today).plans.single().what)
        for (t in listOf("Поставь будильник на завтра в 7", "Как думаешь, завтра будет дождь?", "Завтра на работу рано вставать", "Мне 100 рублей не хватает", "I like you"))
            assertTrue(PlanTalk.hear(t, today).plans.isEmpty() && MemoryTalk.fact(t, today) == null, t)
    }
}

/** She asks the name once and understands a short answer; «что у меня завтра?» gathers plans, reminders and birthdays. */
class AcquaintanceTest {
    private fun ms(y: Int, m: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun asksTheNameOnceAndHearsAShortAnswer() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            val convo = InMemoryConversationStore()
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { ms(2026, 11, 6, 12) }, random = Random(1))
            assertFalse(a.handle("привет").text.contains("как тебя зовут"))
            a.handle("как дела?")
            val r = a.handle("спасибо")
            assertTrue(r.text.contains("Кстати, а как тебя зовут?"), r.text)
            assertEquals("Приятно познакомиться, Аня! Запомнила.", a.handle("Аня, а тебя?").text)
            assertEquals("Аня", MemoryTalk.ownerName(memory.all()))
            assertFalse(a.handle("спасибо").text.contains("зовут"))
        }
    }

    @Test fun aRefusalIsNotAName() {
        for (t in listOf("не скажу", "да", "устала", "секрет")) assertNull(MemoryTalk.nameAnswer(t), t)
        assertEquals("Владельца зовут Аня Смирнова", MemoryTalk.nameAnswer("Меня Аня Смирнова")?.text)
        withLang(Lang.EN) { assertEquals("The owner's name is Tom", MemoryTalk.nameAnswer("I'm Tom, and you?")?.text) }
    }

    @Test fun agendaGathersEverything() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add("Планы владельца на 7 ноября 2026: экзамен по физике", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, 0)
            memory.add("День рождения мамы владельца: 7 ноября", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, 0)
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { ms(2026, 11, 6, 12) }, random = Random(1),
                upcoming = { listOf("сегодня в 18:30 — позвонить в банк", "завтра в 09:00 — взять зачётку") })
            assertEquals("Мрр, смотрю: завтра в 09:00 — взять зачётку; завтра — экзамен по физике; 7 ноября — день рождения у мамы.", a.handle("Что у меня завтра?").text)
            assertEquals("Мрр, смотрю: сегодня в 18:30 — позвонить в банк.", a.handle("что у меня сегодня").text)
            assertTrue(a.handle("что у меня послезавтра?").text.startsWith("На послезавтра у меня ничего не записано"))
        }
    }
}

/** «Завтра в 10 собеседование» → «Напомнить за час — в 09:00?» → «да» sets the reminder. */
class ReminderOfferTest {
    private class FakeReminders : ru.iskra.core.tools.Tool {
        val calls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        override val spec = ru.iskra.core.skills.SkillSchemas.setReminder
        override val risk = ru.iskra.core.tools.Risk.SAFE
        override fun describe(args: kotlinx.serialization.json.JsonObject) = "напоминание"
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject): ru.iskra.core.tools.ToolOutcome {
            calls += args; return ru.iskra.core.tools.ToolOutcome(true, "напомню завтра в 09:00")
        }
    }
    private fun ms(y: Int, m: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun timesAreHeard() {
        assertEquals(java.time.LocalTime.of(10, 0), PlanTalk.time("завтра в 10 собеседование"))
        assertEquals(java.time.LocalTime.of(19, 30), PlanTalk.time("концерт в 7:30 вечера"))
        assertEquals(java.time.LocalTime.of(17, 0), PlanTalk.time("встреча в 5"))
        assertEquals(java.time.LocalTime.of(14, 0), PlanTalk.time("interview at 2 pm"))
        assertNull(PlanTalk.time("через 3 дня экзамен"))
        assertNull(PlanTalk.time("в 2026 году"))
    }

    @Test fun offersAndSetsTheReminder() = withLang(Lang.RU) {
        runBlocking {
            val reminders = FakeReminders()
            val a = CompanionAgent(persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(reminders)), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, clock = { ms(2026, 11, 6, 12) }, random = Random(1))
            assertEquals("Запомнила: завтра — собеседование. Держу за тебя лапки! Напомнить за час — в 09:00?", a.handle("Завтра в 10 у меня собеседование").text)
            a.handle("да")
            assertEquals("2026-11-07T09:00", reminders.calls.single()["at"].toString().trim('"'))
            assertEquals("собеседование", reminders.calls.single()["text"].toString().trim('"'))
            // «нет» — no reminder; and «да» long after the offer means nothing.
            a.handle("В понедельник в 15:00 стоматолог, иду к стоматологу")
            assertEquals("Хорошо, без напоминания.", a.handle("нет").text)
            a.handle("да")
            assertEquals(1, reminders.calls.size)
        }
    }
}

/** «ты устал» → «ты устала» when the owner speaks of herself in the feminine. */
class AgreementTest {
    @Test fun fixesMasculineAddress() {
        val g = ru.iskra.core.brain.SelfGender
        assertEquals("Ты, наверное, устала? Ты сегодня так много сделала!", g.agreeFeminine("Ты, наверное, устал? Ты сегодня так много сделал!"))
        assertEquals("Ты пришла! Я рада. Ты готова?", g.agreeFeminine("Ты пришёл! Я рада. Ты готов?"))
        assertEquals("Ты не заблудилась? Ты права.", g.agreeFeminine("Ты не заблудился? Ты прав."))
        assertEquals("Ты футбол смотрела?", g.agreeFeminine("Ты футбол смотрел?"))     // the noun stays, the verb agrees
        assertEquals("Ты устала, а он пришёл.", g.agreeFeminine("Ты устал, а он пришёл."))  // another subject: not hers
        assertEquals("Ты знаешь, кот пришёл?", g.agreeFeminine("Ты знаешь, кот пришёл?"))   // a comma ends the clause
        // From the ARM evaluation (Gemma 3 1B to «Аня»).
        assertEquals("Ты просто прекрасна - очень добрая и понимающая.", g.agreeFeminine("Ты просто прекрасен - очень добрый и понимающий."))
        assertEquals("Помни, что ты замечательная и любимая.", g.agreeFeminine("Помни, что ты замечательный и любимый."))
        assertEquals("Я устала, а ты?", g.agreeFeminine("Я устала, а ты?"))
    }

    @Test fun theReplyIsAgreed() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add(ru.iskra.core.brain.SelfGender.FEMININE_RU, MemoryKind.FACT, MemorySource.MODEL_PROPOSED, 3, 0)
            val provider = FakeProvider(mutableListOf({ _ -> LlmResponse("[happy] Ты сегодня молодец, наверное, устал?", emptyList()) }))
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { provider }, random = Random(1))
            assertEquals("Ты сегодня молодец, наверное, устала?", a.handle("Я сегодня весь день работала").text)
        }
    }
}

/** «Как прошёл экзамен?» → «Сдала на пятёрку!»: the answer stays with the plan. */
class OutcomeTest {
    private fun ms(y: Int, m: Int, d: Int, h: Int) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun theAnswerIsKeptWithThePlan() = withLang(Lang.RU) {
        runBlocking {
            var now = ms(2026, 11, 6, 10)
            val memory = MemoryManager(InMemoryMemoryStore())
            val a = CompanionAgent(persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(), stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, clock = { now }, random = Random(1))
            a.handle("Завтра у меня экзамен по анатомии")
            now = ms(2026, 11, 8, 12)
            assertTrue(a.handle("привет").text.contains("как прошёл экзамен по анатомии?"))
            a.handle("Сдала на пятёрку!")
            assertEquals("7 ноября 2026 у владельца было: экзамен по анатомии (владелец: «Сдала на пятёрку!»)", memory.all().single().text)
            val k = PlanTalk.known(memory.all()).single()
            assertEquals("экзамен по анатомии", k.plan.what); assertEquals("Сдала на пятёрку!", k.outcome); assertTrue(k.done)
            assertTrue(PlanTalk.verdict("нормально, четвёрка") && !PlanTalk.verdict("поставь будильник на 7"))
            // Only the answer right after the question.
            a.handle("а ещё я купила кофе")
            assertEquals("Сдала на пятёрку!", PlanTalk.known(memory.all()).single().outcome)
        }
    }
}

/** Plans in a model's prompt are said relative to today; template slots and «сейчас сессия» are not taken literally. */
class PromptWordingTest {
    private val today = LocalDate.of(2026, 11, 6)
    private fun item(text: String) = MemoryItem(1, MemoryKind.FACT, text, MemorySource.USER_EXPLICIT, 3, 0, 0)

    @Test fun plansAreRelativeToToday() {
        assertEquals("Планы владельца на завтра (7 ноября): экзамен по анатомии",
            PlanTalk.forPrompt(item("Планы владельца на 7 ноября 2026: экзамен по анатомии"), today).text)
        assertEquals("Вчера (5 ноября) у владельца было: экзамен (владелец: «Сдала на пятёрку!»)",
            PlanTalk.forPrompt(item("5 ноября 2026 у владельца было: экзамен (владелец: «Сдала на пятёрку!»)"), today).text)
        assertEquals("The owner's plans for tomorrow (November 7): physics exam",
            PlanTalk.forPrompt(item("The owner's plans for November 7, 2026: physics exam"), today).text)
        assertEquals("Владелец любит пиццу", PlanTalk.forPrompt(item("Владелец любит пиццу"), today).text)
        assertTrue(PlanTalk.hear("Я учусь на врача, сейчас сессия.", today).plans.isEmpty())
        assertEquals(today, PlanTalk.hear("Сейчас иду на экзамен", today).plans.single().date)
    }

    @Test fun unfilledSlotsAreRemoved() {
        assertEquals("Hi! I'm Iskra. What are you up to today? mrr",
            ru.iskra.core.agent.ReplyParser.parse("[happy] Hi! I'm Iskra. Mr./Ms. [person's name], what are you up to today? mrr").text)
        assertEquals("Привет! Как дела?", ru.iskra.core.agent.ReplyParser.parse("[happy] Привет, [имя]! Как дела?").text)
    }
}

/** Without any brain she still answers everyday feelings and plays riddles. */
class OfflineTalkTest {
    private val now = java.time.LocalDateTime.of(2026, 11, 6, 15, 0)
    private fun brain() = ru.iskra.core.brain.OfflineBrain(Random(5))

    @Test fun everydayFeelingsAreAnswered() = withLang(Lang.RU) {
        val b = brain()
        for (t in listOf("Я скучал", "Мне страшно", "У меня ничего не получается", "Похвали меня", "Я поссорился с другом", "Ура, я сдал экзамен!",
            "Не могу уснуть", "Ты спишь?", "Помурлычь", "Ты кошка?", "Посоветуй фильм", "Что приготовить на ужин?", "Мне одиноко", "Давай поиграем")) {
            val a = b.chat(t, now, ru.iskra.core.persona.Emotion.NEUTRAL, null)
            assertNotNull(a, t)
        }
        withLang(Lang.EN) { assertNotNull(b.chat("I missed you", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)) }
        Unit
    }

    @Test fun riddles() = withLang(Lang.RU) {
        val b = brain()
        val q = b.chat("загадай загадку", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)!!.text
        assertTrue(q.startsWith("Загадка!"), q)
        assertTrue(b.chat("не знаю что это такое вообще наверное", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)?.text?.startsWith("Загадка!") != true)
        // A fresh riddle: a wrong guess, then giving up.
        b.chat("ещё загадку", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)
        assertTrue(b.chat("кирпич", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)!!.text.startsWith("Не-а"))
        assertTrue(b.chat("сдаюсь", now, ru.iskra.core.persona.Emotion.NEUTRAL, null)!!.text.startsWith("Это "))
    }

    @Test fun weatherWordOrder() {
        val today = java.time.LocalDateTime.of(2026, 11, 6, 12, 0)
        for ((t, day) in listOf("какая сегодня погода" to "today", "какая завтра погода" to "tomorrow", "какая погода завтра" to "tomorrow", "погода в Казани на завтра" to "tomorrow")) {
            val p = ru.iskra.core.tools.CommandParser.parse(t, today) as? ru.iskra.core.tools.CommandParser.Parsed.Tool
            assertNotNull(p, t)
            assertEquals(day, p.args["day"].toString().trim('"'), t)
        }
    }
}

/** Everyday phrases that must get a real answer without any brain (not the «I don't understand yet» fallback). */
class OfflineCoverageTest {
    private fun answers(lang: Lang, phrases: List<String>): List<Pair<String, String>> = withLang(lang) {
        runBlocking {
            phrases.map { p ->
                val a = CompanionAgent(persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                    stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, random = Random(7))
                p to a.handle(p).text
            }
        }
    }
    // Every line of the "no brain" fallback, by a phrase only it has.
    private val fallback = Regex("""Мяу\? Это я пока не понимаю|тут нужен «мозг»|без «мозга» я на такое не отвечу|без «мозга» я понимаю немногое|""" +
        """Meow\? I don't get that yet|that needs a “brain”|without a “brain” I can't answer|without a “brain” I understand only""")

    @Test fun russian() {
        val missed = answers(Lang.RU, listOf("Привет", "Как дела?", "Что делаешь?", "Спокойной ночи", "Я скучал", "Расскажи анекдот", "Ты меня любишь?",
            "Спасибо", "Пока", "Кто ты?", "Что ты умеешь?", "Мне скучно", "Мне грустно", "Я устал", "Посоветуй фильм", "Что приготовить на ужин?",
            "Сколько времени?", "Подбрось монетку", "Ты спишь?", "Помурлычь", "Давай поиграем", "Загадай загадку", "Я поссорился с другом",
            "Мне страшно", "У меня ничего не получается", "Похвали меня", "Ты кошка?", "Ура, я сдал экзамен!", "Мне одиноко", "Не могу уснуть"))
            .filter { (_, a) -> fallback.containsMatchIn(a) }
        assertTrue(missed.isEmpty(), missed.toString())
    }

    @Test fun english() {
        val missed = answers(Lang.EN, listOf("Hi", "How are you?", "What are you doing?", "Good night", "I missed you", "Tell me a joke", "Do you love me?",
            "Thanks", "Bye", "Who are you?", "What can you do?", "I'm bored", "I'm sad", "I'm tired", "Recommend a movie", "What should I cook?",
            "Flip a coin", "Are you asleep?", "Purr for me", "Let's play", "Tell me a riddle", "I'm scared", "Nothing works", "Praise me",
            "Are you a cat?", "I did it!", "I feel lonely", "I can't sleep"))
            .filter { (_, a) -> fallback.containsMatchIn(a) }
        assertTrue(missed.isEmpty(), missed.toString())
    }
}

/** Without a cloud, old turns are summarized by the model on the phone (at night on charge, by the app's job). */
class PhoneSummaryTest {
    private class PhoneModel(private val answer: String) : ru.iskra.core.llm.LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        val asked = mutableListOf<ru.iskra.core.llm.LlmRequest>()
        override suspend fun complete(request: ru.iskra.core.llm.LlmRequest): LlmResponse { asked += request; return LlmResponse(answer, emptyList()) }
    }

    private fun agent(memory: MemoryManager, convo: InMemoryConversationStore, local: ru.iskra.core.llm.LlmProvider?) = CompanionAgent(
        persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(), tools = ToolRegistry(),
        policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, localFactory = { local }, random = Random(1),
        clock = { java.time.LocalDateTime.of(2026, 11, 7, 3, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() })

    private fun fill(convo: InMemoryConversationStore, n: Int) {
        val t0 = java.time.LocalDateTime.of(2026, 11, 6, 18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(n) { i -> convo.append(Turn(if (i % 2 == 0) Turn.Role.USER else Turn.Role.ASSISTANT, "реплика $i", t0 + i * 1000L)) }
    }

    @Test fun summarizesAtNight() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            val convo = InMemoryConversationStore()
            fill(convo, 64)
            val phone = PhoneModel("Аня готовится к сессии на врача и волнуется перед экзаменом по анатомии.")
            assertEquals(1, agent(memory, convo, phone).summarizeOnPhone())
            assertEquals("6 ноября: Аня готовится к сессии на врача и волнуется перед экзаменом по анатомии.", memory.all().single().text)
            assertEquals(MemoryKind.EPISODE, memory.all().single().kind)
            assertEquals(34, convo.count())
            // The excerpt first, the task last (Gemma 4 E2B went on with a bare transcript as Iskra).
            val asked = (phone.asked.single().messages.single() as ru.iskra.core.llm.ChatMessage.User).text
            assertTrue(asked.contains("\n\nВладелец: реплика 0\n"), asked)
            assertTrue(asked.startsWith("Отрывок переписки владельца телефона и ассистента «Искра» от 6 ноября (пятница):"), asked)
            assertTrue(asked.contains("Вместо «сегодня», «завтра», «вчера» пиши даты."), asked)
            assertTrue(asked.endsWith("Не продолжай переписку: ответь только пересказом или словом НЕТ."), asked)
        }
    }

    @Test fun aChattyOrEmptyAnswerKeepsTheTurns() = withLang(Lang.RU) {
        runBlocking {
            for (bad in listOf("[happy] Мрр, конечно! Давай поговорим!", "Владелец: реплика 0\nИскра: реплика 1", "", "Ок")) {
                val memory = MemoryManager(InMemoryMemoryStore())
                val convo = InMemoryConversationStore()
                fill(convo, 64)
                assertEquals(0, agent(memory, convo, PhoneModel(bad)).summarizeOnPhone(), bad)
                assertTrue(memory.all().isEmpty(), bad)
                assertEquals(64, convo.count(), bad)
            }
            // «НЕТ»: nothing worth keeping, the turns go.
            val convo = InMemoryConversationStore()
            fill(convo, 64)
            val memory = MemoryManager(InMemoryMemoryStore())
            assertEquals(1, agent(memory, convo, PhoneModel("НЕТ")).summarizeOnPhone())
            assertTrue(memory.all().isEmpty())
            assertEquals(34, convo.count())
            // No model on the phone, or too few turns.
            assertNull(agent(memory, InMemoryConversationStore(), null).summarizeOnPhone())
            assertEquals(0, agent(memory, InMemoryConversationStore().also { fill(it, 10) }, PhoneModel("x")).summarizeOnPhone())
        }
    }
}
