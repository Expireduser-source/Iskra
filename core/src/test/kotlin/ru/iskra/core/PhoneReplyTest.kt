package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.agent.ReplyParser
import ru.iskra.core.agent.Turn
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
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A small model on the phone once answered «[happy] …» by chance (ARM evaluation, Gemma 3 1B); stored as «…», it was
 * copied into every reply after. Now an empty answer is asked again, then answered without the model, and never shown
 * back to it.
 */
class PhoneReplyTest {
    private class Scripted(vararg answers: String) : LlmProvider {
        override val id = "local:test"
        override val onDevice = true
        private val left = ArrayDeque(answers.toList())
        val asked = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse { asked += request; return LlmResponse(left.removeFirstOrNull() ?: "[happy] Мрр!", emptyList()) }
    }

    private val t0 = LocalDateTime.of(2026, 11, 6, 15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun agent(phone: LlmProvider, convo: InMemoryConversationStore = InMemoryConversationStore()) = CompanionAgent(
        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = convo,
        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
        providerFactory = { null }, localFactory = { phone }, random = Random(1), clock = { t0 })

    @Test fun strayStartIsTrimmed() {
        assertEquals("It's so nice to meet you!", ReplyParser.parse("[happy]! It's so nice to meet you!").text)
        assertEquals("Ты выглядишь очень интересно!", ReplyParser.parse("[curious] …Ты выглядишь очень интересно!").text)
        assertEquals("", ReplyParser.parse("[happy] …").text)
        assertEquals("«Да», — сказала она.", ReplyParser.parse("[happy] «Да», — сказала она.").text)
    }

    @Test fun hedgedFormsAreResolved() {
        val g = ru.iskra.core.brain.SelfGender
        // Unknown: a lone address goes, the rest stays.
        assertEquals("Ой. Я здесь, чтобы тебя обнять.", g.slashes("Ой, милый/милая. Я здесь, чтобы тебя обнять.", null))
        assertEquals("Не грусти!", g.slashes("Милый/милая, не грусти!", null))
        assertEquals("Не грусти, всё наладится.", g.slashes("Не грусти, милый/милая, всё наладится.", null))
        assertEquals("Ты устал(а)?", g.slashes("Ты устал(а)?", null))
        // Known: the matching form.
        assertEquals("Ой, милая. Я здесь.", g.slashes("Ой, милый/милая. Я здесь.", true))
        assertEquals("Ой, милый. Я здесь.", g.slashes("Ой, милый/милая. Я здесь.", false))
        assertEquals("Милая, не грусти!", g.slashes("Милый/милая, не грусти!", true))
        assertEquals("Ты устала? Ты вернулась!", g.slashes("Ты устал(а)? Ты вернулся/вернулась!", true))
        assertEquals("Ты устал? Ты вернулся!", g.slashes("Ты устал(а)? Ты вернулся/вернулась!", false))
        // Not a pair of forms.
        for (t in listOf("Чай/кофе, и/или 50/50.", "Счёт 2/3, ок.")) {
            assertEquals(t, g.slashes(t, null))
            assertEquals(t, g.slashes(t, true))
        }
    }

    @Test fun anEmptyAnswerIsAskedAgain() = withLang(Lang.RU) {
        runBlocking {
            val phone = Scripted("[happy] …", "[happy] Привет! Рада тебя видеть.")
            val r = agent(phone).handle("Расскажи, что нового")
            assertEquals("Привет! Рада тебя видеть.", r.text)
            assertEquals(2, phone.asked.size)
        }
    }

    @Test fun twiceEmptyIsAnsweredWithoutTheModel() = withLang(Lang.RU) {
        runBlocking {
            val phone = Scripted("[happy] …", "[neutral]")
            val convo = InMemoryConversationStore()
            val r = agent(phone, convo).handle("Хочу посоветоваться насчёт учёбы")
            assertEquals("Мрр, мысль ускользнула. Скажешь ещё раз, другими словами?", r.text)
            assertEquals("Мрр, мысль ускользнула. Скажешь ещё раз, другими словами?", convo.recent(1).single().text)
        }
    }

    @Test fun anOldEmptyReplyIsNotShownToTheModel() = withLang(Lang.RU) {
        runBlocking {
            val convo = InMemoryConversationStore()
            convo.append(Turn(Turn.Role.USER, "Привет!", t0 - 60_000))
            convo.append(Turn(Turn.Role.ASSISTANT, "…", t0 - 59_000, ru.iskra.core.persona.Emotion.HAPPY))
            val phone = Scripted("[happy] Как прошёл день?")
            agent(phone, convo).handle("Я дома")
            val shown = phone.asked.single().messages
            assertTrue(shown.none { it is ChatMessage.Assistant }, shown.toString())
            assertEquals(listOf("Привет!", "Я дома"), shown.map { (it as ChatMessage.User).text })
        }
    }

    /** «Ты человек?» — «Uh... I think so.» (Gemma 3 1B): asked again, told the language. A translation is left alone. */
    @Test fun aReplyInAnotherLanguageIsAskedAgain() = withLang(Lang.RU) {
        runBlocking {
            val phone = Scripted("[shy] Uh... I think so.", "[shy] Нет, я программа, которая живёт в телефоне.")
            assertEquals("Нет, я программа, которая живёт в телефоне.", agent(phone).handle("Ты человек?").text)
            assertTrue(phone.asked[1].situation.endsWith("Ответь по-русски."), phone.asked[1].situation)
            val translator = Scripted("[neutral] I love winter evenings.")
            assertEquals("I love winter evenings.", agent(translator).handle("Переведи на английский: «Я люблю зимние вечера»").text)
            assertEquals(1, translator.asked.size)
            // The second try answered the reminder first (E4B: «Хорошо, я отвечу по-русски.»): that part goes.
            val polite = Scripted("[shy] Uh... I think so.", "[shy] Хорошо, я отвечу по-русски. Нет, я цифровая жизнь из этого телефона.")
            assertEquals("Нет, я цифровая жизнь из этого телефона.", agent(polite).handle("Ты человек?").text)
            assertEquals("Хорошо, договорились.", ru.iskra.core.agent.PromptComposer().dropNudgeAnswer("Хорошо, договорились."))
            // E4B, 0.15.0: «Конечно, буду говорить по-русски!» before the answer.
            assertEquals("Для яйца всмятку нужно варить 3–4 минуты.",
                ru.iskra.core.agent.PromptComposer().dropNudgeAnswer("Конечно, буду говорить по-русски! Для яйца всмятку нужно варить 3–4 минуты."))
            assertEquals("Перехожу к делу.", ru.iskra.core.agent.PromptComposer().dropNudgeAnswer("Перехожу к делу."))
            // Still English after the nudge: the first answer stays (better than none).
            val stubborn = Scripted("[shy] Uh... I think so.", "[shy] Sorry, English only.")
            assertEquals("Uh... I think so.", agent(stubborn).handle("Ты человек?").text)
        }
    }
}
