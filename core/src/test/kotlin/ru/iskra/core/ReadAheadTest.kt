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
    /**
     * Keeps what it read, as llama.cpp's cache does: the prompt and the words it wrote after it. Turns that left the
     * dialogue's beginning are cut out of the cache and the rest moved into place, as the bridge does; a turn that comes
     * back at the beginning can't be put in, and everything after it is read again. [charsPerToken]: how long a token of
     * this prompt is, for the measure the provider fits the dialogue by.
     */
    private class Caching(override val contextSize: Int = 4096, private val charsPerToken: (String) -> Double = { 3.65 },
                          private val answer: (String) -> String) : LlamaEngine {
        var cache = ""
        /** Each prompt, with how much of its beginning the cache already held (moved into place included). */
        val reads = mutableListOf<Pair<String, Int>>()
        private var last = 0 to 0
        override val info = "caching"
        override fun lastRead() = last
        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean): String {
            val common = cache.commonPrefixWith(prompt).length
            val rest = prompt.substring(common)
            val at = if (rest.length >= 60) cache.indexOf(rest.take(60), common) else -1
            var moved = 0
            if (at >= 0) while (at + moved < cache.length && moved < rest.length && cache[at + moved] == rest[moved]) moved++
            reads += prompt to common + moved
            last = (prompt.length / charsPerToken(prompt)).toInt() to ((common + moved) / charsPerToken(prompt)).toInt()
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
        val readAgain = talk(Caching(answer = answers()))
        // The read ahead did the work the next reply would have done: after «Меня зовут Аня» her facts about the person
        // changed at the start of the prompt, and the whole dialogue after them was read again then.
        assertTrue(readAgain > 300, "the read ahead re-read at most $readAgain characters")
    }

    /**
     * With the phone's smaller context the dialogue is fitted by the measure of a token. Her diary's prompt is measured
     * differently (here 2.9 characters a token against the dialogue's 3.65); taken as the dialogue's measure, it fitted the
     * night's read ahead with other turns than the morning's message, and Gemma 4 read the whole dialogue again before
     * «Доброе утро!» (292 tokens, 17 s for E4B in the ARM evaluation 0.16.1).
     */
    @Test fun theMorningAfterHerDiaryToo() {
        withLang(Lang.RU) { talk(Caching(2048, { p -> if (p.contains("запиши в свой дневник")) 2.9 else 3.65 }, answers()), phoneTools = true) }
    }

    /** The app's phone tools, as Gemma 4 gets them: their declarations fill most of a 2048-token context. */
    private fun phoneTools(): List<ru.iskra.core.tools.Tool> = listOf(ru.iskra.core.tools.ToolSchemas.setTimer, ru.iskra.core.tools.ToolSchemas.setAlarm,
        ru.iskra.core.tools.ToolSchemas.flashlight, ru.iskra.core.tools.ToolSchemas.openApp, ru.iskra.core.skills.SkillSchemas.setReminder,
        ru.iskra.core.skills.SkillSchemas.addNote, ru.iskra.core.skills.SkillSchemas.weather).map { spec ->
        object : ru.iskra.core.tools.Tool {
            override val spec = spec
            override val risk = ru.iskra.core.tools.Risk.SAFE
            override fun describe(args: kotlinx.serialization.json.JsonObject) = spec.name
            override suspend fun execute(args: kotlinx.serialization.json.JsonObject) = ru.iskra.core.tools.ToolOutcome(true, "готово")
        }
    }

    private fun answers(): (String) -> String {
        var n = 0
        return { prompt ->
            when {
                prompt.contains("запиши в свой дневник") -> "Сегодня я познакомилась с Аней, она учится на врача."
                // A timer asked for: the call is begun for the model, which writes the minutes.
                prompt.endsWith("<|tool_call>call:set_timer{minutes:") -> "4}<tool_call|>"
                prompt.contains("<|tool_response>") && !prompt.substringAfterLast("<|tool_response>").contains("<|turn>user") -> "[happy] Поставила таймер на 4 минуты!"
                else -> "[happy] Ответ номер ${++n}: мрр, как интересно! Расскажи мне об этом ещё немного, мне правда хочется знать."
            }
        }
    }

    /** The script, as the app goes: a read ahead after each reply and after the night; the most read again by one. */
    private fun talk(engine: Caching, phoneTools: Boolean = false): Int {
        // One provider for the whole talk, as the app keeps it: it measures its tokens as it goes.
        val provider = LocalLlmProvider({ engine }, ChatFormat.GEMMA4, "local:test")
        var clock = java.time.LocalDateTime.of(2026, 11, 7, 12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val agent = CompanionAgent(persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
            stateStore = InMemoryStateStore(), tools = ToolRegistry(if (phoneTools) phoneTools() else listOf(RecordingTool())), policy = SafetyPolicy({ _, _ -> true }),
            providerFactory = { null }, localFactory = { provider }, random = Random(1),
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
        return readAgain
    }
}
