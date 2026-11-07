package ru.iskra.core

import kotlinx.coroutines.runBlocking
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the app has the phone model read ahead — after each reply and after the night job ([CompanionAgent.warmUpPhone]) —
 * is the beginning of the next message's prompt, so that reply reads only the message itself. Without it a fact just
 * learned («Меня зовут Аня»), notes meant for one message, a timer's round or a new day made the next reply read the
 * whole dialogue first (ARM evaluation 0.16.0: 150–270 tokens, 10–17 s before E4B's first words).
 */
class ReadAheadTest {
    /** Keeps what it read, as llama.cpp's cache does: the prompt and the words it wrote after it. */
    private class Caching(private val answer: (String) -> String) : LlamaEngine {
        var cache = ""
        /** Each prompt, with how much of its beginning the cache already held. */
        val reads = mutableListOf<Pair<String, Int>>()
        override val info = "caching"
        override val contextSize = 4096
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            reads += prompt to cache.commonPrefixWith(prompt).length
            val out = StringBuilder()
            // A read ahead asks for one token and takes none.
            if (maxTokens > 1) for (piece in Regex("""<\|tool_call>|<tool_call\|>|[^<]+|<""").findAll(answer(prompt)).map { it.value }) {
                out.append(piece)
                if (!onPiece(piece)) break
            }
            cache = prompt + out
            return out.toString()
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private val script = listOf(
        "Привет! Как у тебя дела?",
        "Меня зовут Аня.",
        "Я учусь на врача, сейчас сессия.",
        "Завтра у меня экзамен по анатомии, волнуюсь.",
        "Жарю сырники — засеки, сколько нужно.",
        "Мне сегодня грустно, поддержи меня.",
        "@night",
        "Доброе утро!",
        "@night",
        "Привет, я вернулась!",
        "Сдала на пятёрку!",
        "Как меня зовут и на кого я учусь?",
        "Переведи на английский: «Я люблю зимние вечера».",
        "Расскажи очень короткую сказку про кота-космонавта.",
        "Объясни простыми словами, что такое фотосинтез.",
        "Что лучше на завтрак: каша или омлет?",
        "Спасибо тебе!",
    )

    @Test fun theNextReplyReadsOnlyItsMessage() = withLang(Lang.RU) {
        var n = 0
        val engine = Caching { prompt ->
            when {
                prompt.contains("запиши в свой дневник") -> "Сегодня я познакомилась с Аней, она учится на врача."
                // A timer asked for: the call is begun for the model, which writes the minutes.
                prompt.endsWith("<|tool_call>call:set_timer") -> "{minutes:4}<tool_call|>"
                prompt.contains("<|tool_response>") && !prompt.substringAfterLast("<|tool_response>").contains("<|turn>user") -> "[happy] Поставила таймер на 4 минуты!"
                else -> "[happy] Ответ номер ${++n}: мрр, интересно!"
            }
        }
        var clock = java.time.LocalDateTime.of(2026, 11, 7, 12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val agent = CompanionAgent(persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
            stateStore = InMemoryStateStore(), tools = ToolRegistry(listOf(RecordingTool())), policy = SafetyPolicy({ _, _ -> true }),
            providerFactory = { null }, localFactory = { LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:test") }, random = Random(1),
            clock = { clock }, summarizeAfterTurns = 20)
        var readAgain = 0
        runBlocking {
            assertTrue(agent.warmUpPhone())
            for (q in script) {
                if (q == "@night") {
                    // The night job: her diary written aside, the dialogue's cache put back, then the morning's prompt read.
                    clock += 86_400_000L
                    val kept = engine.cache
                    agent.writeDiary(onPhone = true)
                    engine.cache = kept
                    agent.warmUpPhone()
                    continue
                }
                val first = engine.reads.size
                val r = agent.handle(q)
                // An answer without the model («Как меня зовут…» from memory) reads nothing.
                engine.reads.getOrNull(first)?.let { (prompt, known) ->
                    // Everything before the new message was read already.
                    val message = prompt.lastIndexOf("<|turn>user\n")
                    assertTrue(known >= message, "«$q» read again from ${prompt.length - known} characters before its end:\n" +
                        prompt.substring(maxOf(0, known - 80), minOf(prompt.length, known + 120)))
                }
                clock += 60_000L
                // As the app does after a reply (with the model loaded): the beginning of the next prompt, while it is read.
                val before = engine.reads.size
                agent.warmUpPhone()
                engine.reads.drop(before).forEach { (p, k) -> readAgain = maxOf(readAgain, p.length - k) }
                assertTrue(r.text.isNotBlank())
            }
        }
        // The read ahead did the work the next reply would have done: after «Меня зовут Аня» her facts about the person
        // changed at the start of the prompt, and the whole dialogue after them was read again then.
        assertTrue(readAgain > 300, "the read ahead re-read at most $readAgain characters")
    }
}
