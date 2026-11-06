package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.Turn
import ru.iskra.core.brain.PlanTalk
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.Personas
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

/** Fixes for what the ARM evaluation transcripts showed the models on the phone doing wrong. */
class TranscriptFixesTest {
    private class PhoneModel : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse("[happy] Мрр!", emptyList()) }
    }

    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun agent(memory: MemoryManager, convo: InMemoryConversationStore, phone: LlmProvider, now: () -> Long) = CompanionAgent(
        persona = Personas.ISKRA, memory = memory, conversation = convo, stateStore = InMemoryStateStore(), tools = ToolRegistry(),
        policy = SafetyPolicy({ _, _ -> true }), providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = now)

    @Test fun theNewsIsReadRight() {
        assertEquals(1, PlanTalk.tone("Сдала на пятёрку!"))
        assertEquals(1, PlanTalk.tone("Всё прошло отлично"))
        assertEquals(-1, PlanTalk.tone("Не сдала, завалили на втором вопросе"))
        assertEquals(-1, PlanTalk.tone("Ужасно, двойка"))
        assertEquals(0, PlanTalk.tone("Нормально"))
        assertEquals(1, PlanTalk.tone("I passed!"))
        assertEquals(-1, PlanTalk.tone("I failed it"))
    }

    /** «Как прошёл экзамен?» — «Сдала на пятёрку!»: the model hears it's good news (Gemma 3 1B answered «Ох, ну вот так…»). */
    @Test fun theModelIsToldWhetherItIsGoodNews() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            memory.add("5 ноября 2026 у владельца было: экзамен по анатомии", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, at(4, 20))
            val convo = InMemoryConversationStore()
            convo.append(Turn(Turn.Role.USER, "Привет, я вернулась!", at(6, 18)))
            convo.append(Turn(Turn.Role.ASSISTANT, "Привет! Как прошёл экзамен по анатомии? Мрр, рассказывай!", at(6, 18)))
            val phone = PhoneModel()
            val a = agent(memory, convo, phone) { at(6, 19) }
            a.handle("Сдала на пятёрку!")
            val note = phone.asked.single().situation
            assertTrue(note.contains("«Сдала на пятёрку!» — это хорошая новость"), note)
            a.handle("А что посмотреть вечером?")
            assertFalse(phone.asked.last().situation.contains("хорошая новость"))
            // Bad news: comfort.
            val convo2 = InMemoryConversationStore()
            convo2.append(Turn(Turn.Role.ASSISTANT, "Как прошёл экзамен по анатомии?", at(6, 18)))
            val phone2 = PhoneModel()
            agent(memory, convo2, phone2) { at(6, 19) }.handle("Завалила, двойка")
            assertTrue(phone2.asked.single().situation.contains("это огорчение, поддержи"), phone2.asked.single().situation)
        }
    }

    /** What this message told is not shown back as memory (E2B: «Я помню про твой экзамен!» right after being told). */
    @Test fun justToldIsNotRemembered() = withLang(Lang.RU) {
        runBlocking {
            val memory = MemoryManager(InMemoryMemoryStore())
            val convo = InMemoryConversationStore()
            val phone = PhoneModel()
            var now = at(6, 12)
            val a = agent(memory, convo, phone) { now }
            a.handle("Завтра у меня экзамен по анатомии")
            assertTrue(memory.all().any { it.text.contains("экзамен по анатомии") })
            val first = phone.asked.single()
            assertFalse(first.system.contains("экзамен по анатомии") || first.situation.contains("экзамен по анатомии"), first.system + first.situation)
            // The next message: now it is something remembered.
            now += 60_000
            a.handle("Как сдать экзамен по анатомии без паники?")
            val second = phone.asked.last()
            assertTrue(second.system.contains("экзамен по анатомии") || second.situation.contains("экзамен по анатомии"), second.system + second.situation)
        }
    }

    @Test fun noNoteWithoutAQuestionAboutThePlan() {
        val known = PlanTalk.known(listOf(ru.iskra.core.memory.MemoryItem(1, MemoryKind.FACT, "5 ноября 2026 у владельца было: экзамен по анатомии",
            MemorySource.USER_EXPLICIT, 3, 0, 0)))
        assertNull(PlanTalk.outcomeNote(known, "Привет! Как дела?", "Сдала на пятёрку!", true))
        assertNull(PlanTalk.outcomeNote(known, null, "Сдала на пятёрку!", true))
        assertNull(PlanTalk.outcomeNote(known, "Как прошёл экзамен по анатомии?", "Нормально", true))
    }
}
