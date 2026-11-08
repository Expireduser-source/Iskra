package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.Echo
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.brain.CareTalk
import ru.iskra.core.brain.SelfGender
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.LlmResponse
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Emotion
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the owner is in the message she answers: her face, a word for the model, and no sentence said twice. */
class FeelingsTest {
    private class Phone(vararg answers: String) : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        private val left = ArrayDeque(answers.toList())
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse(left.removeFirst(), emptyList()) }
    }

    private class Sink : ru.iskra.core.voice.ReplySink {
        val said = mutableListOf<String>()
        val faces = mutableListOf<Emotion>()
        override fun emotion(e: Emotion) { faces += e }
        override fun sentence(text: String, emotion: Emotion) { said += text; faces += emotion }
    }

    private fun agent(phone: Phone) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { null }, localFactory = { phone }, random = Random(1))

    @Test fun whatTheOwnerSaysOfThemselves() {
        assertEquals(CareTalk.Kind.LOW, CareTalk.feels("Мне сегодня грустно, поддержи меня."))
        assertEquals(CareTalk.Kind.WORRY, CareTalk.feels("Завтра у меня экзамен по анатомии, волнуюсь."))
        assertEquals(CareTalk.Kind.WORRY, CareTalk.feels("I'm really stressed about work today."))
        assertEquals(CareTalk.Kind.SICK, CareTalk.feels("Я заболела, температура"))
        // Asked of her, said it passed, said it isn't so, or not about feelings at all.
        for (t in listOf("Тебе грустно?", "Are you sad?", "Мне было грустно, но уже лучше", "Я не волнуюсь", "Это страшно интересно", "Как дела?"))
            assertNull(CareTalk.feels(t), t)
    }

    @Test fun herFaceFitsHowTheOwnerIs() {
        assertEquals(Emotion.SAD, CareTalk.face(CareTalk.Kind.LOW, Emotion.CURIOUS))
        assertEquals(Emotion.SAD, CareTalk.face(CareTalk.Kind.LOW, Emotion.HAPPY))
        assertEquals(Emotion.SHY, CareTalk.face(CareTalk.Kind.LOW, Emotion.SHY))
        assertEquals(Emotion.NEUTRAL, CareTalk.face(CareTalk.Kind.WORRY, Emotion.CURIOUS))
        // A smile of encouragement to «волнуюсь» stays.
        assertEquals(Emotion.HAPPY, CareTalk.face(CareTalk.Kind.WORRY, Emotion.HAPPY))
        assertEquals(Emotion.CURIOUS, CareTalk.face(null, Emotion.CURIOUS))
    }

    @Test fun toSadnessSheLooksSadAndTheModelIsToldToComfort() = withLang(Lang.RU) {
        runBlocking {
            // E2B in the ARM evaluation of 0.16.2: one line, and a curious face.
            val phone = Phone("[curious] Мне немного грустно, когда вижу, что ты расстроена. Помни, я здесь, чтобы быть рядом!")
            val sink = Sink()
            val r = agent(phone).handle("Мне сегодня грустно, поддержи меня.", sink)
            assertEquals(Emotion.SAD, r.emotion)
            assertTrue(sink.faces.isNotEmpty() && sink.faces.all { it == Emotion.SAD }, sink.faces.toString())
            assertTrue(phone.asked.last().situation.contains("собеседнику сейчас грустно"), phone.asked.last().situation)
        }
    }

    @Test fun worriedTheyAreCalmedAndAnEncouragingSmileStays() = withLang(Lang.EN) {
        runBlocking {
            val phone = Phone("[curious] Oh no, stress is the worst feeling! Maybe a few deep breaths could help?")
            val r = agent(phone).handle("I'm really stressed about work today.")
            assertEquals(Emotion.NEUTRAL, r.emotion)
            assertTrue(phone.asked.last().situation.contains("the person is worried"), phone.asked.last().situation)
        }
    }

    @Test fun aSentenceSaidInTheLastReplyIsNotSaidAgain() = withLang(Lang.RU) {
        runBlocking {
            // Gemma 3 1B in the ARM evaluation of 0.16.2, two replies running.
            val phone = Phone("[happy] Привет! Я просто счастлива. У меня тут столько всего интересного, и ты - такой интересный человек!",
                "[happy] Ого, здорово! У меня тут столько всего интересного, и ты — такой интересный человек!")
            val agent = agent(phone)
            agent.handle("Как у тебя дела?")
            val sink = Sink()
            val r = agent.handle("Я учусь на врача, сейчас сессия.", sink)
            assertEquals("Ого, здорово!", r.text)
            // Heard as shown.
            assertEquals(r.text, sink.said.joinToString(" "))
        }
    }

    @Test fun anEchoThatIsAllTheReplyHasStays() {
        val before = listOf("Привет! У меня тут столько всего интересного, и ты — такой интересный человек!")
        val again = "У меня тут столько всего интересного, и ты — такой интересный человек! А ты как?"
        assertEquals(again, Echo.without(again, before))
        // Short ones may well come again.
        assertEquals("Мрр, я так рада! Это правда здорово.", Echo.without("Мрр, я так рада! Это правда здорово.", listOf("Мрр, я так рада!")))
        assertEquals("Пятёрка — это здорово!", Echo.without("Пятёрка — это здорово! У меня тут столько всего интересного, и ты такой интересный человек.", before))
    }

    @Test fun wordsBeforeAManlyNounAgreeWithTheNoun() {
        // E2B to Аня: «ты мне такой интересный человек!» is right as it is.
        assertEquals("Ты мне такой интересный человек!", SelfGender.agreeFeminine("Ты мне такой интересный человек!"))
        assertEquals("Ты лучший друг на свете.", SelfGender.agreeFeminine("Ты лучший друг на свете."))
        assertEquals("Ты такая милая!", SelfGender.agreeFeminine("Ты такой милый!"))
        assertEquals("Ты устала, а я рада.", SelfGender.agreeFeminine("Ты устал, а я рада."))
        assertFalse(SelfGender.agreeFeminine("Ты такой хороший человек").contains("такая"))
    }
}
