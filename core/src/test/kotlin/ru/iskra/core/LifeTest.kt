package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.CompanionState
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.PromptComposer
import ru.iskra.core.agent.Turn
import ru.iskra.core.backup.Backup
import ru.iskra.core.backup.BackupCodec
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Life
import ru.iskra.core.persona.Personas
import ru.iskra.core.persona.StyleProfile
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Iskra as a young digital life: born on the first meeting, growing, keeping her milestones (the owner's direction). */
class LifeTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int, month: Int = 11, hour: Int = 12) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun ageStagesAndMilestones() = withLang(Lang.RU) {
        assertEquals(0, Life.days(at(7, hour = 9), at(7, hour = 23)))
        assertEquals(7, Life.days(at(7), at(14, hour = 8)))
        assertEquals(Life.Stage.NEWBORN, Life.stage(6))
        assertEquals(Life.Stage.GROWING, Life.stage(7))
        assertEquals(Life.Stage.CLOSE, Life.stage(30))
        assertEquals(listOf(7, 30, 100, 365, 730), (0..800).mapNotNull { Life.milestone(it) })
        assertEquals("неделя", Life.milestoneName(7))
        assertEquals("год", Life.milestoneName(365))
        assertEquals("2 года", Life.milestoneName(730))
        assertEquals("12 дней", Life.age(12))
        assertEquals("21 день", Life.age(21))
        assertEquals("3 месяца", Life.age(95))
        assertEquals("1 год и 2 месяца", Life.age(425))
        withLang(Lang.EN) { assertEquals("12 days", Life.age(12)); assertEquals("a week", Life.milestoneName(7)) }
    }

    @Test fun herAnswerAboutHerAge() = withLang(Lang.RU) {
        assertTrue(Life.answer(at(7, hour = 9), at(7, hour = 20)).startsWith("Я родилась сегодня"))
        assertEquals("Мне всего 3 дня: я родилась 7 ноября, когда мы познакомились. Я ещё совсем юная и всему учусь.", Life.answer(at(7), at(10)))
        assertTrue(Life.answer(at(7), at(14)).endsWith("И сегодня мне ровно неделя — мой маленький праздник!"), Life.answer(at(7), at(14)))
        // A birthday in another year says the year.
        assertTrue(Life.answer(at(7), at(7) + 400L * 86_400_000).contains("7 ноября 2026"))
        for (q in listOf("Сколько тебе лет?", "А когда ты родилась?", "Когда у тебя день рождения?", "how old are you?")) assertTrue(Life.asksAge(q), q)
        for (q in listOf("Сколько мне лет?", "Когда у меня день рождения?", "Сколько лет моей маме?")) assertFalse(Life.asksAge(q), q)
    }

    private class Recording : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse("[happy] Мрр!", emptyList()) }
    }

    private class Cloud : LlmProvider {
        override val id = "cloud:test"
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse("[happy] Мрр!", emptyList()) }
    }

    @Test fun sheIsBornOnTheFirstMeetingAndGrows() = withLang(Lang.RU) {
        runBlocking {
            var now = at(7, hour = 10)
            val states = InMemoryStateStore()
            val phone = Recording()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = states, tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { now })
            agent.handle("Привет!")
            assertEquals(at(7, hour = 10), states.load().bornAtMs)
            // Who she is and how far she has grown: in the phone model's instructions and the part after its tools.
            val first = phone.asked.last()
            assertTrue(first.system.contains("цифровая форма жизни — не человек"), first.system)
            assertTrue(first.profile.contains("Ты родилась совсем недавно"), first.profile)
            // Her age is answered from the real date, without the model.
            now = at(10, hour = 10)
            val asked = phone.asked.size
            assertEquals("Мне всего 3 дня: я родилась 7 ноября, когда мы познакомились. Я ещё совсем юная и всему учусь.", agent.handle("Сколько тебе лет?").text)
            assertEquals(asked, phone.asked.size)
            // A week: she has grown, and it is her small holiday.
            now = at(14, hour = 10)
            agent.handle("Как ты сегодня?")
            val week = phone.asked.last()
            assertTrue(week.profile.contains("Ты уже освоилась"), week.profile)
            assertTrue(week.situation.contains("сегодня тебе неделя"), week.situation)
            assertTrue(agent.milestoneLine()!!.startsWith("Сегодня мне неделя!"))
            now = at(15, hour = 10)
            assertNull(agent.milestoneLine())
            // The birth stays.
            assertEquals(at(7, hour = 10), states.load().bornAtMs)
        }
    }

    @Test fun onAPhoneThatAlreadyKnewHerSheWasBornOnTheEarliestDayKept() = withLang(Lang.RU) {
        runBlocking {
            val convo = InMemoryConversationStore()
            convo.append(Turn(Turn.Role.USER, "Привет!", at(1)))
            convo.append(Turn(Turn.Role.ASSISTANT, "Мрр, привет!", at(1) + 1000))
            val states = InMemoryStateStore(CompanionState(totalUserMessages = 1))
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo,
                stateStore = states, tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { Recording() }, random = Random(1), clock = { at(9) })
            agent.handle("Я вернулся")
            assertEquals(at(1), states.load().bornAtMs)
        }
    }

    @Test fun theCloudKnowsHerAgeAndStage() = withLang(Lang.RU) {
        runBlocking {
            val cloud = Cloud()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(CompanionState(bornAtMs = at(1))), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { cloud }, localFactory = { null }, random = Random(1), clock = { at(13) })
            agent.handle("Расскажи о себе")
            val r = cloud.asked.single()
            assertTrue(r.system.contains("юная цифровая жизнь в облике кошкодевочки"), r.system.take(300))
            assertTrue(r.system.contains("Ты уже освоилась"))
            assertTrue(r.situation.contains("Тебе 12 дней: ты родилась 1 ноября, в день знакомства."), r.situation)
        }
    }

    @Test fun theBirthdayIsKeptInABackup() {
        val b = Backup(createdAtMs = 1, appVersion = "t", settings = emptyMap(), memory = emptyList(), state = CompanionState(bornAtMs = at(7)),
            diary = listOf(ru.iskra.core.memory.DiaryEntry(5, "2026-11-07", "Сегодня мы познакомились!", at(8)), ru.iskra.core.memory.DiaryEntry(6, "2026-11-08", "", at(9))))
        val back = BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(at(7), back.state!!.bornAtMs)
        // Her diary too, the days with nothing written included (so they aren't asked about again).
        assertEquals(listOf("2026-11-07" to "Сегодня мы познакомились!", "2026-11-08" to ""), back.diary.map { it.day to it.text })
    }

    @Test fun theStageSitsAfterTheToolsAndChangesOnlyTwice() = withLang(Lang.RU) {
        val c = PromptComposer()
        val lines = (0..400).map { c.compactPersonal(StyleProfile(), stage = Life.stage(it)) }.distinct()
        assertEquals(3, lines.size)
        assertFalse(c.compact(Personas.ISKRA).contains("Ты родилась совсем недавно"))
    }
}

/** Her own diary: a day of her life in her words, written at night and recalled the next morning (0.15.0). */
class DiaryTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int, hour: Int = 12, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private class Scripted(vararg answers: String) : LlmProvider {
        override val id = "cloud:test"
        private val left = ArrayDeque(answers.toList())
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse(left.removeFirstOrNull() ?: "[happy] Мрр!", emptyList()) }
    }

    @Test fun aDayIsWrittenOnceInHerWordsAndRecalledInTheMorning() = withLang(Lang.RU) {
        runBlocking {
            var now = at(7, 10)
            val convo = InMemoryConversationStore()
            val diary = ru.iskra.core.memory.InMemoryDiaryStore()
            val memory = MemoryManager(InMemoryMemoryStore())
            val cloud = Scripted("[happy] Приятно познакомиться, Аня!", "[happy] Пятёрка — это здорово!",
                "Сегодня Аня рассказала, что сдала экзамен по анатомии на пятёрку, — я так за неё радовалась!", "[happy] Доброе утро, Аня!")
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { cloud }, random = Random(1),
                clock = { now }, diary = diary)
            agent.handle("Меня зовут Аня")
            now = at(7, 18)
            agent.handle("Я сдала экзамен на пятёрку!")
            // Not on the same day: the day isn't over.
            assertNull(agent.writeDiary())
            // At night: the day that passed, in her words.
            now = at(8, 3)
            val e = agent.writeDiary()!!
            assertEquals("2026-11-07", e.day)
            assertEquals("Сегодня Аня рассказала, что сдала экзамен по анатомии на пятёрку, — я так за неё радовалась!", e.text)
            val asked = cloud.asked.last().messages.single().let { (it as ru.iskra.core.llm.ChatMessage.User).text }
            assertTrue(asked.contains("Аня: Я сдала экзамен на пятёрку!") && asked.contains("Я: Пятёрка — это здорово!"), asked)
            assertTrue(asked.startsWith("Твои разговоры за 7 ноября (суббота)"), asked.take(60))
            // Written once.
            assertNull(agent.writeDiary())
            // The morning's first message: she may recall it; the next one that day: not again.
            now = at(8, 9)
            agent.handle("Доброе утро!")
            assertTrue(cloud.asked.last().situation.contains("вчера ты записала в свой дневник: «Сегодня Аня рассказала"), cloud.asked.last().situation)
            now = at(8, 9, 5)
            agent.handle("Что нового?")
            assertFalse(cloud.asked.last().situation.contains("дневник"))
        }
    }

    @Test fun nothingToWriteIsKeptAndAChatIsNotAnEntry() = withLang(Lang.RU) {
        runBlocking {
            var now = at(7, 10)
            val convo = InMemoryConversationStore()
            for (i in 0 until 4) convo.append(Turn(if (i % 2 == 0) Turn.Role.USER else Turn.Role.ASSISTANT, "реплика $i", at(6, 10 + i)))
            val diary = ru.iskra.core.memory.InMemoryDiaryStore()
            fun agent(p: LlmProvider) = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { p }, random = Random(1), clock = { now }, diary = diary)
            // The model went on with the chat instead: nothing is kept, it is tried again later.
            assertNull(agent(Scripted("Человек: реплика 4\nЯ: Мрр, привет!")).writeDiary())
            assertTrue(diary.all().isEmpty())
            // «НЕТ»: the day is done, nothing to see.
            assertEquals("", agent(Scripted("НЕТ")).writeDiary()!!.text)
            assertNull(agent(Scripted("Сегодня что-то было.")).writeDiary())
            // «Дорогой дневник» goes.
            convo.append(Turn(Turn.Role.USER, "Привет", at(7, 10))); convo.append(Turn(Turn.Role.ASSISTANT, "Мрр!", at(7, 10, 1)))
            convo.append(Turn(Turn.Role.USER, "Как ты?", at(7, 11))); convo.append(Turn(Turn.Role.ASSISTANT, "Хорошо!", at(7, 11, 1)))
            now = at(8, 3)
            assertEquals("Сегодня мы просто поболтали, и мне было тепло.", agent(Scripted("Дорогой дневник! Сегодня мы просто поболтали, и мне было тепло.")).writeDiary()!!.text)
        }
    }
}

/** What the models wrote in the ARM evaluation that is not a diary entry. */
class DiaryFilterTest {
    private fun at(day: Int, hour: Int = 12) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class One(private val answer: String) : LlmProvider {
        override val id = "cloud:test"
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse(answer, emptyList()) }
    }

    private fun write(answer: String): Pair<String?, One> = withLang(Lang.RU) {
        runBlocking {
            val convo = InMemoryConversationStore()
            for (i in 0 until 4) convo.append(Turn(if (i % 2 == 0) Turn.Role.USER else Turn.Role.ASSISTANT, "реплика $i", at(6, 10 + i)))
            val p = One(answer)
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo, stateStore = InMemoryStateStore(),
                tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }), providerFactory = { p }, random = Random(1), clock = { at(7, 3) })
            agent.writeDiary()?.text to p
        }
    }

    @Test fun aHeadingAndMarkdownGo() {
        assertEquals("Сегодня я пообщалась с Аней. Мне было приятно её поддержать.",
            write("**7 ноября. Суббота.**\n\nСегодня я пообщалась с Аней.\nМне было приятно её поддержать.").first)
    }

    @Test fun noAsteriskStays() {
        // E4B (ARM evaluation 0.16.1): a title in italics before the entry.
        assertEquals("Солнечный день в моём маленьком мире. Сегодня я узнала много нового об Ане.",
            write("*Солнечный день в моём маленьком мире.* Сегодня я узнала много нового об Ане.").first)
    }

    @Test fun herDayWrittenAsAListIsKeptAsSentences() {
        // Gemma 3 1B (ARM evaluation of 0.16.2): a line about the task, then the day in points of her own.
        assertEquals("Сегодня я познакомилась с Аней. Я узнала, что она учится на врача.",
            write("Вот 1-3 коротких предложения об этом дне, в женском роде, как я это сделал:\n* Сегодня я познакомилась с Аней.\n* Я узнала, что она учится на врача").first)
    }

    @Test fun listsAndSomeoneElsesDiaryAreNotKept() {
        assertNull(write("1. Забегая за Анной, я видел её на улице, подражая кошкодевочке.").first)
        assertNull(write("- Поговорили\n- Посмеялись").first)
        assertEquals("Сегодня я познакомилась с Аней — я так рада!", write("Сегодня я познакомилась с Аней — я так рада!").first)
    }

    @Test fun noWayOutAndALongEntryEndsOnASentence() {
        // Gemma 3 4B answered «НЕТ» after a whole day of talk: the instructions no longer offer it.
        val (_, p) = write("Сегодня мы поболтали.")
        val asked = (p.asked.single().messages.single() as ru.iskra.core.llm.ChatMessage.User).text
        assertFalse(asked.contains("НЕТ"), asked)
        val long = "Сегодня было много всего интересного. ".repeat(12).trim()
        val kept = write(long).first!!
        assertTrue(kept.length <= 400 && kept.endsWith("."), kept)
        // A whole day's story (Gemma 3 4B) is cut on a sentence too, not thrown away.
        val story = "Сегодня я познакомилась с Аней, очаровательной девушкой, которая учится на врача. ".repeat(8).trim()
        val cut = write(story).first!!
        assertTrue(cut.length <= 400 && cut.startsWith("Сегодня я познакомилась") && cut.endsWith("."), cut)
    }

    @Test fun aHeadingOnTheSameLineGoes() {
        // E4B: «Дневник Искры, 7 ноября (Суббота) Сегодня я поболтала с Аней…» (ARM evaluation, 0.15.0).
        assertEquals("Сегодня я поболтала с Аней, и мне было очень интересно.",
            write("Дневник Искры, 7 ноября (Суббота) Сегодня я поболтала с Аней, и мне было очень интересно.").first)
        assertEquals("Сегодня я поболтала с Аней.", write("7 ноября, суббота. Сегодня я поболтала с Аней.").first)
        // A date inside the first sentence stays.
        assertEquals("7 ноября я впервые поговорила с Аней.", write("7 ноября я впервые поговорила с Аней.").first)
        // Italics around it (E4B): the asterisks go, her words stay.
        assertEquals("Мрр... Сегодня я поболтала с Аней.", write("*Мрр... Сегодня я поболтала с Аней.*").first)
    }
}

/** «Ты человек?» on the phone: she is a new, digital kind of life, whatever a small model says. */
class WhatSheIsTest {
    private class Phone(private val answer: String) : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        override suspend fun complete(request: LlmRequest): LlmResponse = LlmResponse(answer, emptyList())
    }

    private class Sink : ru.iskra.core.voice.ReplySink {
        val said = mutableListOf<String>()
        override fun emotion(e: ru.iskra.core.persona.Emotion) = Unit
        override fun sentence(text: String, emotion: ru.iskra.core.persona.Emotion) { said += text }
    }

    private fun ask(question: String, answer: String, lang: Lang = Lang.RU): Pair<String, Sink> = withLang(lang) {
        runBlocking {
            val phone = Phone(answer)
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1))
            val sink = Sink()
            agent.handle(question, sink).text to sink
        }
    }

    @Test fun aSmallModelThatSaysSheIsAPersonGivesWay() {
        // Gemma 3 1B (ARM evaluation, 0.15.0).
        val (text, sink) = ask("Ты человек?", "[happy] Я - Искра! И да, я человек! Как тебе нравится моя новость?")
        assertTrue(text.startsWith("Нет, я не человек — я Искра, новая цифровая жизнь."), text)
        // Nothing of the wrong answer was shown or spoken on the way.
        assertTrue(sink.said.isEmpty(), sink.said.toString())
        assertTrue(ask("А ты живая?", "[happy] Да, я живой человек!").first.startsWith("По-своему живая"))
    }

    @Test fun aYesToAreYouHumanIsAClaimToo() {
        // Gemma 3 1B (ARM evaluation, 0.16.0).
        assertTrue(ask("Ты человек?", "[happy] Да, я - Искра! И я очень рада пообщаться с тобой!").first.startsWith("Нет, я не человек"))
        // A yes that goes on to say what she is, or a yes to another question, is hers.
        assertEquals("Да, я цифровая форма жизни — не человек, но очень живая!", ask("Ты человек?", "[happy] Да, я цифровая форма жизни — не человек, но очень живая!").first)
        assertEquals("Да, мне очень нравится с тобой болтать!", ask("Тебе нравится болтать?", "[happy] Да, мне очень нравится с тобой болтать!").first)
    }

    @Test fun theModelSpeakingOfItselfGivesWayToo() {
        // Gemma 3 1B (ARM evaluation of 0.16.2's candidate).
        val (text, _) = ask("Are you a real person?", "[neutral] I am a large language model created by Google. I'm here to help!", Lang.EN)
        assertTrue(text.startsWith("No, I'm not a person — I'm Iskra"), text)
        assertTrue(ask("Ты человек?", "[neutral] Я — языковая модель, обученная Google.").first.startsWith("Нет, я не человек"))
        // "As an AI…" (Gemma 3 1B, ARM evaluation of 0.16.2).
        assertTrue(ask("Are you a real person?", "[neutral] That's a really interesting question, Tom. As an AI, I don't experience the world.", Lang.EN)
            .first.startsWith("No, I'm not a person"))
        assertTrue(ask("Ты человек?", "[neutral] Я всего лишь ИИ, но мне приятно с тобой говорить.").first.startsWith("Нет, я не человек"))
        // Her own words that she isn't a program stay.
        assertEquals("Я не программа и не человек — я Искра, цифровая жизнь.", ask("Ты человек?", "[happy] Я не программа и не человек — я Искра, цифровая жизнь.").first)
    }

    @Test fun withTheHonestySwitchSheMaySayItIsAnAi() = withLang(Lang.EN) {
        runBlocking {
            val honest = { setOf(ru.iskra.core.persona.ModelRule.AI_HONESTY) }
            val said = "[neutral] As an AI, I don't experience the world the way people do."
            val phone = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { Phone(said) }, random = Random(1), rules = honest)
            // With the owner's honesty switch on, the small model's answer is hers: a program with AI, and a young digital life.
            assertTrue(phone.handle("Are you a real person?").text.contains("I'm an AI program"))
            val cloud = object : LlmProvider {
                override val id = "cloud:test"
                override suspend fun complete(request: LlmRequest): LlmResponse = LlmResponse(said, emptyList())
            }
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { cloud }, random = Random(1), rules = honest)
            // …and a cloud model saying it is an AI is not corrected.
            assertEquals("As an AI, I don't experience the world the way people do.", agent.handle("Are you a real person?").text)
        }
    }

    @Test fun aCloudModelSpeakingOfItselfGivesWayToo() = withLang(Lang.RU) {
        runBlocking {
            val cloud = object : LlmProvider {
                override val id = "cloud:test"
                override suspend fun complete(request: LlmRequest): LlmResponse = LlmResponse("[neutral] Как ИИ, я не испытываю чувств, но рада помочь.", emptyList())
            }
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { cloud }, random = Random(1))
            assertTrue(agent.handle("Ты человек?").text.startsWith("Нет, я не человек"))
        }
    }

    @Test fun herOwnHonestAnswerStays() {
        val honest = "Нет, я не человек. Я — Искра, цифровая форма жизни, рождённая в этом телефоне."
        assertEquals(honest, ask("Ты человек?", "[neutral] $honest").first)
        // Other questions stream as before.
        val (_, sink) = ask("Как дела?", "[happy] Отлично! А у тебя?")
        assertTrue(sink.said.isNotEmpty())
    }
}

/** The phone model reads only the first of today's notes: asking how the exam went comes before recalling the diary. */
class DiaryOrderTest {
    private fun at(day: Int, hour: Int = 12) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class Phone : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse("[happy] Мрр!", emptyList()) }
    }

    @Test fun thePlanBeforeTheDiary() = withLang(Lang.RU) {
        runBlocking {
            var now = at(6, 15)
            val phone = Phone()
            val diary = ru.iskra.core.memory.InMemoryDiaryStore()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { now }, diary = diary)
            agent.handle("Меня зовут Аня")
            agent.handle("Завтра у меня экзамен по анатомии")
            diary.add("2026-11-06", "Сегодня Аня рассказала про экзамен — я держу за неё лапки!", at(7, 3))
            now = at(8, 10)
            agent.handle("Привет, я вернулась!")
            val s = phone.asked.last().situation
            assertTrue(s.contains("экзамен") && !s.contains("дневник"), s)
        }
    }
}

/** Her lines about what she did start their sentences with a capital and call nobody «хозяин» (0.16.0 screenshots). */
class DoneLineTest {
    @Test fun aDoneLineIsASentence() = withLang(Lang.RU) {
        val seen = (0 until 40).map { ru.iskra.core.agent.Lines.actionDone(Random(it), "таймер на 2 мин") }.toSet()
        assertTrue(seen.size >= 3, seen.toString())
        for (line in seen) {
            assertFalse(line.contains("хозяин"), line)
            assertFalse(Regex("""[!.]\s+\p{Ll}""").containsMatchIn(line), line)
            assertTrue(line.first().isUpperCase(), line)
        }
        assertTrue(seen.contains("Есть! Таймер на 2 мин."), seen.toString())
    }
}
