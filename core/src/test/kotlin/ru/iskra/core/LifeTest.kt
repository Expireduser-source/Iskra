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
        val b = Backup(createdAtMs = 1, appVersion = "t", settings = emptyMap(), memory = emptyList(), state = CompanionState(bornAtMs = at(7)))
        assertEquals(at(7), BackupCodec.decode(BackupCodec.encode(b)).state!!.bornAtMs)
    }

    @Test fun theStageSitsAfterTheToolsAndChangesOnlyTwice() = withLang(Lang.RU) {
        val c = PromptComposer()
        val lines = (0..400).map { c.compactPersonal(StyleProfile(), stage = Life.stage(it)) }.distinct()
        assertEquals(3, lines.size)
        assertFalse(c.compact(Personas.ISKRA).contains("Ты родилась совсем недавно"))
    }
}
