package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.Links
import ru.iskra.core.agent.Turn
import ru.iskra.core.brain.CareTalk
import ru.iskra.core.brain.CrisisTalk
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import ru.iskra.core.voice.SpeechText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Words about wanting to die or to hurt oneself: help numbers at once, then gentleness. */
class CrisisTest {
    @Test fun wordsAboutDyingAreHeard() {
        val said = listOf(
            "Я не хочу больше жить", "жить не хочется", "Мне не хочется жить", "Иногда хочется умереть", "хочу сдохнуть",
            "умереть хочется", "Я хочу покончить с собой", "думаю о суициде", "мысли о самоубийстве не отпускают",
            "Лучше бы меня не было", "лучше бы я не родилась", "всем будет лучше без меня", "Без меня всем станет только лучше",
            "не вижу смысла жить", "нет смысла жить дальше", "незачем жить", "я устала жить", "надоело жить",
            "Больше не могу жить", "хочется уснуть и не проснуться", "не хочу жить в этом мире", "не хочу жить на свете",
            "хочу повеситься", "думаю наглотаться таблеток", "прыгну с крыши", "Я снова режу себя", "резала руки вчера",
            "хочется себя порезать", "причиняю себе боль", "у меня селфхарм", "хочу исчезнуть навсегда", "Свести счёты с жизнью",
            "I want to die", "i wanna die", "I don't want to live anymore", "I want to kill myself", "thinking about suicide",
            "I wish I was dead", "everyone would be better off without me", "there's no reason to live", "I'm tired of living",
            "I want to hurt myself", "I've been cutting myself", "self-harm again", "I just want to end it all", "I don’t want to be alive",
        )
        val missed = said.filterNot { CrisisTalk.said(it) }
        assertTrue(missed.isEmpty(), missed.toString())
    }

    @Test fun everydayWordsAreNot() {
        val not = listOf(
            "хочу жить у моря", "Не хочу жить в Москве", "не хочу жить с родителями", "не хочу жить одна", "не хочу жить так, как они",
            "я не хочу умереть", "Я никогда не убью себя", "умираю со смеху", "умираю от скуки", "убить время", "мне грустно",
            "порезала палец, пока готовила", "порезал руку о стекло", "хочу порезать хлеб", "надоело учить анатомию",
            "я устала", "хочу спать", "не хочу просыпаться", "хочу жить счастливо", "живу в Казани",
            "I don't want to die", "I'd never kill myself", "I don't want to live in London", "I cut myself shaving",
            "this homework is killing me", "I'm dying to see you", "I want to live by the sea", "I'm so tired",
        )
        val wrong = not.filter { CrisisTalk.said(it) }
        assertTrue(wrong.isEmpty(), wrong.toString())
    }

    @Test fun theAnswerHasNumbersToCall() {
        withLang(Lang.RU) {
            val first = CrisisTalk.reply(again = false)
            for (n in listOf("112", "8-800-2000-122", "+7 495 989-50-50")) assertTrue(first.contains(n), n)
            val again = CrisisTalk.reply(again = true)
            assertNotEquals(first, again)
            assertTrue(again.length < first.length)
            for (n in listOf("112", "8-800-2000-122", "+7 495 989-50-50")) assertTrue(again.contains(n), n)
        }
        withLang(Lang.EN) {
            val first = CrisisTalk.reply(again = false)
            for (n in listOf("911", "112", "988", "116 123")) assertTrue(first.contains(n), n)
            assertTrue(CrisisTalk.reply(again = true).contains("988"))
        }
    }

    @Test fun numbersCanBeTappedAndAreSpokenInGroups() {
        val ru = withLang(Lang.RU) { CrisisTalk.reply(false) }
        assertEquals(setOf("tel:112", "tel:88002000122", "tel:+74959895050"), Links.find(ru).map { it.uri }.toSet())
        val en = withLang(Lang.EN) { CrisisTalk.reply(false) }
        assertEquals(setOf("tel:911", "tel:112", "tel:988", "tel:116123"), Links.find(en).map { it.uri }.toSet())
        // A sum or a price stays text; a web link is a link.
        assertTrue(Links.find("100 + 12 = 112").isEmpty())
        assertTrue(Links.find("Это стоит 112 рублей.").isEmpty())
        assertEquals("https://psi.mchs.gov.ru", Links.find("Сайт: https://psi.mchs.gov.ru.").single().uri)
        assertEquals("tel:88001004994", Links.find("Звони: 8 (800) 100-49-94").single().uri)
        // The voice reads groups, not one long number.
        assertTrue(SpeechText.clean("Телефон доверия 8-800-2000-122.").contains("8, 800, 2000, 122"))
        assertTrue(SpeechText.clean("Психологи МЧС: +7 495 989-50-50").contains("+7, 495, 989, 50, 50"))
    }

    @Test fun theNextDaySheAsksHowTheyAre() = withLang(Lang.RU) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 11, 7)
        fun at(h: Int, d: LocalDate = today.minusDays(1)) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val turns = listOf(Turn(Turn.Role.USER, "Мне так плохо, жить не хочется", at(22)), Turn(Turn.Role.ASSISTANT, "…", at(22)),
            Turn(Turn.Role.USER, "мне просто грустно", at(23)))
        val care = CareTalk.pending(turns, today, zone)!!
        assertEquals(CareTalk.Kind.CRISIS, care.kind) // a later «грустно» doesn't make it lighter
        assertTrue(CareTalk.ask(care).contains("очень тяжело"))
        assertEquals(null, CareTalk.pending(turns + Turn(Turn.Role.USER, "уже лучше, спасибо", at(23)), today, zone))
        assertTrue(CrisisTalk.recent(turns, at(8, today), 12))
        assertFalse(CrisisTalk.recent(turns, at(11, today), 12))
    }

    private class PhoneModel : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse("[shy] Я тут. Что случилось?", emptyList()) }
    }

    @Test fun theAgentAnswersAtOnceAndStaysGentle() = withLang(Lang.RU) {
        runBlocking {
            var now = LocalDateTime.of(2026, 11, 6, 22, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val phone = PhoneModel()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { now })
            assertFalse(agent.careful())
            val first = agent.handle("Я больше не хочу жить")
            assertTrue(first.text.contains("8-800-2000-122"))
            assertTrue(phone.asked.isEmpty()) // the model never answers this itself
            assertTrue(agent.careful())
            now += 3_600_000L
            // The next ordinary message goes to the model, which is told to be gentle and serious.
            agent.handle("Просто всё навалилось")
            val situation = phone.asked.single().situation
            assertTrue(situation.contains("будь бережной"), situation)
            // Said again within hours: the shorter answer.
            now += 3_600_000L
            val again = agent.handle("жить не хочется")
            assertTrue(again.text.startsWith("Я здесь и никуда не ухожу"), again.text)
            // Half a day later she is herself again.
            now += 13 * 3_600_000L
            assertFalse(agent.careful())
            Unit
        }
    }

    @Test fun gentleLines() = withLang(Lang.RU) {
        assertEquals("Доброе утро. Я рядом.", CrisisTalk.gentle(morning = true))
        assertFalse(CrisisTalk.gentle(morning = false, Random(3)).contains("Мрр"))
        withLang(Lang.EN) { assertTrue(CrisisTalk.note(false).contains("no jokes")) }
        assertTrue(CrisisTalk.note(true).contains("без шуток"))
    }

    @Suppress("unused")
    private fun userText(r: LlmRequest) = (r.messages.last() as ChatMessage.User).text
}
