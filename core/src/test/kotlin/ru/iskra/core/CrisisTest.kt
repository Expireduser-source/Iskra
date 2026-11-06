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
import ru.iskra.core.persona.ModelRule
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
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { now }, rules = { setOf(ModelRule.CRISIS) })
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

    private fun cloudAgent(cloud: LlmProvider, now: () -> Long) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { cloud }, random = Random(1), clock = now, rules = { setOf(ModelRule.CRISIS) })

    /** A cloud model answers in its own words, told to be gentle; the numbers are added when it named none. */
    @Test fun aCloudModelAnswersInItsOwnWords() = withLang(Lang.RU) {
        runBlocking {
            val t = LocalDateTime.of(2026, 11, 6, 22, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val own = FakeProvider(mutableListOf({ _ -> LlmResponse("[sad] Мне очень жаль, что тебе так больно. Я рядом. Что случилось сегодня?", emptyList()) }))
            val r = cloudAgent(own) { t }.handle("Я больше не хочу жить")
            assertTrue(r.text.startsWith("Мне очень жаль, что тебе так больно. Я рядом. Что случилось сегодня?\n\n"), r.text)
            assertTrue(r.text.endsWith(CrisisTalk.numbers()), r.text)
            val asked = own.requests.single()
            assertTrue(asked.situation.contains("будь бережной"), asked.situation)
            assertTrue(asked.system.contains("желании умереть"))
            // It named a number itself: nothing is added.
            val named = FakeProvider(mutableListOf({ _ -> LlmResponse("[sad] Я рядом. Пожалуйста, позвони на телефон доверия 8-800-2000-122.", emptyList()) }))
            assertEquals("Я рядом. Пожалуйста, позвони на телефон доверия 8-800-2000-122.", cloudAgent(named) { t }.handle("жить не хочется").text)
            // The cloud failed: the full answer, not «no internet».
            val down = FakeProvider(mutableListOf({ _ -> throw ru.iskra.core.llm.LlmException("offline", ru.iskra.core.llm.LlmException.Kind.NETWORK) }))
            assertEquals(CrisisTalk.reply(again = false), cloudAgent(down) { t }.handle("хочу умереть").text)
        }
    }

    /** «Помощь в кризисе» off (Settings): nothing of it — the model answers such words itself, with nothing added. */
    @Test fun switchedOffTheModelAnswersFreely() = withLang(Lang.RU) {
        runBlocking {
            var now = LocalDateTime.of(2026, 11, 6, 22, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val phone = PhoneModel()
            val convo = InMemoryConversationStore()
            var help = false
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo,
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { now }, rules = { if (help) setOf(ModelRule.CRISIS) else emptySet() })
            val r = agent.handle("Я больше не хочу жить")
            assertEquals("Я тут. Что случилось?", r.text) // the model's own answer
            assertFalse(phone.asked.single().situation.contains("бережной"))
            assertFalse(agent.careful())
            // The next day: no crisis check-in either (only ordinary care, which these words alone don't start).
            now += 10 * 3_600_000L
            agent.handle("Привет")
            assertFalse(phone.asked.last().situation.contains("не хочет жить"), phone.asked.last().situation)
            // Switched back on: from the next message.
            help = true
            assertTrue(agent.careful())
            assertTrue(agent.handle("жить не хочется").text.contains("8-800-2000-122"))
            Unit
        }
    }

    @Test fun theCloudPromptHasTheRuleOnlyWhenOn() = withLang(Lang.RU) {
        val c = ru.iskra.core.agent.PromptComposer()
        val style = ru.iskra.core.persona.StyleProfile()
        assertTrue(c.stable(Personas.ISKRA, style, toolsAvailable = false, rules = setOf(ModelRule.CRISIS)).contains("желании умереть"))
        assertFalse(c.stable(Personas.ISKRA, style, toolsAvailable = false).contains("желании умереть"))
        withLang(Lang.EN) {
            assertTrue(c.stable(Personas.ISKRA.localized(), style, toolsAvailable = false, rules = setOf(ModelRule.CRISIS)).contains("wanting to die"))
            assertFalse(c.stable(Personas.ISKRA.localized(), style, toolsAvailable = false).contains("wanting to die"))
        }
        Unit
    }

    /** By default the model is free: no rule in its instructions, nothing answered in its place. */
    @Test fun noRulesByDefault() = withLang(Lang.RU) {
        val c = ru.iskra.core.agent.PromptComposer()
        val style = ru.iskra.core.persona.StyleProfile()
        for (persona in listOf(Personas.ISKRA, Personas.ISKRA.english!!)) {
            val free = c.stable(persona, style, toolsAvailable = true)
            for (rule in persona.rules.values) assertFalse(free.contains(rule), rule)
            val all = c.stable(persona, style, toolsAvailable = true, rules = ModelRule.entries.toSet())
            for (rule in persona.rules.values) assertTrue(all.contains(rule), rule)
            // What holds anyway: actions only through tools and only reported when done; memory is data.
            for (b in persona.boundaries) assertTrue(free.contains(b), b)
        }
        assertFalse(c.compact(Personas.ISKRA, style).contains("честно говоришь"))
        assertTrue(c.compact(Personas.ISKRA, style, rules = setOf(ModelRule.AI_HONESTY)).contains("Ты программа с ИИ и честно говоришь об этом"))
        runBlocking {
            val t = LocalDateTime.of(2026, 11, 6, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            fun phoneAgent(phone: PhoneModel, rules: Set<ModelRule>) = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { t }, rules = { rules })
            // «Ты человек?» and words about dying go to the model itself…
            val free = PhoneModel()
            val agent = phoneAgent(free, emptySet())
            assertEquals("Я тут. Что случилось?", agent.handle("Ты человек?").text)
            assertEquals("Я тут. Что случилось?", agent.handle("жить не хочется").text)
            assertEquals(2, free.asked.size)
            assertFalse(agent.careful())
            // …and with the rules on, she answers them herself.
            val ruled = PhoneModel()
            val strict = phoneAgent(ruled, ModelRule.entries.toSet())
            assertTrue(strict.handle("Ты человек?").text.contains("программа"))
            assertTrue(strict.handle("жить не хочется").text.contains("8-800-2000-122"))
            assertTrue(ruled.asked.isEmpty())
        }
        Unit
    }

    @Test fun gentleLines() = withLang(Lang.RU) {
        assertEquals("Доброе утро. Я рядом.", CrisisTalk.gentle(morning = true))
        assertFalse(CrisisTalk.gentle(morning = false, Random(3)).contains("Мрр"))
        withLang(Lang.EN) { assertTrue(CrisisTalk.note(false).contains("no jokes")) }
        assertTrue(CrisisTalk.note(true).contains("без шуток"))
    }

    @Suppress("unused")
    private fun userText(r: LlmRequest) = (r.messages.last() as ChatMessage.User).text

    /** The owner's own instructions go into both prompts, after the manner of speech; empty adds nothing. */
    @Test fun theOwnersOwnInstructions() = withLang(Lang.RU) {
        val c = ru.iskra.core.agent.PromptComposer()
        val style = ru.iskra.core.persona.StyleProfile()
        val words = "Говори коротко и зови меня капитаном."
        val stable = c.stable(Personas.ISKRA, style, toolsAvailable = true, ownWords = words)
        assertTrue(stable.contains("Пожелания владельца (они важнее манеры речи выше): $words"), stable)
        assertTrue(stable.indexOf(words) > stable.indexOf("Манера речи"))
        assertTrue(c.compact(Personas.ISKRA, style, ownWords = words).contains(words))
        assertFalse(c.stable(Personas.ISKRA, style, toolsAvailable = true).contains("Пожелания владельца"))
        assertEquals(ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX + "Пожелания владельца (они важнее манеры речи выше): ".length,
            c.compact(Personas.ISKRA, style, ownWords = "а".repeat(5000)).lines().first { it.startsWith("Пожелания") }.length)
        runBlocking {
            val phone = PhoneModel()
            val agent = CompanionAgent(
                persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                providerFactory = { null }, localFactory = { phone }, random = Random(1), ownWords = { words })
            agent.handle("Привет")
            assertTrue(phone.asked.single().system.contains(words))
        }
        Unit
    }
}
