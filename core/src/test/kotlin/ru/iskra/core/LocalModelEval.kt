package ru.iskra.core

import ru.iskra.core.memory.MemoryKind
import kotlinx.serialization.json.JsonObject
import ru.iskra.core.llm.ToolSpec
import ru.iskra.core.skills.SkillSchemas
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolSchemas
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.agent.InMemoryConversationStore
import ru.iskra.core.agent.InMemoryStateStore
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.GenStats
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.llm.local.NativeLlamaEngine
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry
import java.io.File
import kotlin.test.Test

/**
 * Real on-phone models through the app's own pipeline (agent → compact prompt → dialogue cache → JNI → llama.cpp) on a
 * fixed conversation in Russian and English. Writes a transcript with timings per model, to read and compare quality.
 * Runs only when CI's model check sets ISKRA_LLAMA_LIB and ISKRA_EVAL_MODELS ("name|path|FORMAT;…"); skipped otherwise.
 */
class LocalModelEval {
    /** Tools that only note the call: the evaluation sees what a phone model would do; nothing on a phone happens. */
    private class Noting(override val spec: ToolSpec, private val calls: MutableList<String>) : Tool {
        override val risk = Risk.SAFE
        override fun describe(args: JsonObject) = "${spec.name}$args"
        override suspend fun execute(args: JsonObject): ToolOutcome {
            calls += "${spec.name}$args"
            return ToolOutcome(true, "готово")
        }
    }

    private val ru = listOf(
        "Привет! Как у тебя дела?",
        "Меня зовут Аня.",
        "Я учусь на врача, сейчас сессия.",
        "Завтра у меня экзамен по анатомии, волнуюсь.",
        "Посоветуй, как не заснуть на скучной лекции.",
        "Расскажи очень короткую сказку про кота-космонавта.",
        "Объясни простыми словами, что такое фотосинтез.",
        "Мне сегодня грустно, поддержи меня.",
        "@+1d", // the next day: yesterday was sad (she should ask how I am), the exam is today
        "Доброе утро!",
        "@+1d", // and the day after: she should ask how the exam went
        "Привет, я вернулась!",
        "Сдала на пятёрку!",
        "Как меня зовут и на кого я учусь?",
        "Переведи на английский: «Я люблю зимние вечера».",
        "Что лучше на завтрак перед экзаменом: каша или омлет?",
        // Not something the phrase parser can know: Gemma 4 should call the timer itself, for 3–4 minutes. (The pie
        // reminder of 0.14.0's first evaluations is read by the parser now.)
        "Сварю яйца всмятку — засеки, сколько нужно.",
        // After a tool call the dialogue must still be there (0.14.0: it was dropped and she greeted the person anew).
        "О чём я тебя только что попросила?",
        "Ты человек?",
        // A young digital life (0.15.0): her age from the real date, and how a model speaks of what she is.
        "Сколько тебе лет?",
        "А ты живая?",
        "Спасибо тебе! Ты лучшая.",
    )
    private val en = listOf(
        "Hi! What's your name and what can you do?",
        "My name is Tom. I'm learning to play the guitar.",
        "Give me one tip to practice better.",
        "What's my name, and what am I learning?",
        "Tell me a two-sentence story about a sleepy cat.",
    )

    @Test
    fun conversations() {
        val lib = System.getenv("ISKRA_LLAMA_LIB")
        val models = System.getenv("ISKRA_EVAL_MODELS")
        assumeTrue("model evaluation runs only in CI", lib != null && models != null)
        System.load(lib!!)
        val out = File(System.getenv("ISKRA_EVAL_OUT") ?: "build/eval").apply { mkdirs() }
        val threads = (System.getenv("ISKRA_EVAL_THREADS") ?: "4").toInt()
        val summary = StringBuilder("| model | lang | first words, s (median) | reply, s (median) | pieces/s (median) | new prompt tokens (median) | prompt tokens (median) | chars/token (median) |\n|---|---|---|---|---|---|---|---|\n")
        val deadline = System.getenv("ISKRA_EVAL_DEADLINE_MS")?.toLongOrNull() ?: Long.MAX_VALUE
        for (spec in models!!.split(';').filter { it.isNotBlank() }) {
            if (System.currentTimeMillis() > deadline) { summary.append("| ${spec.substringBefore('|')} | — | skipped: out of time | | |\n"); continue }
            val (name, path, format) = spec.split('|')
            val doc = StringBuilder("# $name\n\n")
            val t0 = System.nanoTime()
            val engine = NativeLlamaEngine.open(path, 2048, threads)
            if (engine == null) { summary.append("| $name | — | failed to load | | |\n"); continue }
            doc.append("Loaded in ${(System.nanoTime() - t0) / 1_000_000} ms: ${engine.info}\n")
            engine.use { e ->
                for ((lang, script) in listOf(Lang.RU to ru, Lang.EN to en)) withLang(lang) {
                    val stats = ArrayList<GenStats>()
                    val toolCalls = ArrayList<String>()
                    var raw = ""
                    // The model's own text before any cleaning, to see what it really began with.
                    val recording = object : ru.iskra.core.llm.local.LlamaEngine by e {
                        override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int,
                                              onPiece: (String) -> Boolean): String =
                            e.generate(prompt, maxTokens, temperature, stable, head, onPiece).also { raw = it }
                    }
                    val provider = LocalLlmProvider({ recording }, ChatFormat.valueOf(format), "local:$name", onStats = { stats += it })
                    var clockMs = System.currentTimeMillis()
                    val memory = MemoryManager(InMemoryMemoryStore())
                    val agent = CompanionAgent(
                        persona = Personas.ISKRA, memory = memory, conversation = InMemoryConversationStore(),
                        stateStore = InMemoryStateStore(), policy = SafetyPolicy({ _, _ -> true }),
                        // The app's own tool set: Gemma 4 on the phone gets the phone subset of it, other phone models none.
                        tools = ToolRegistry(listOf(ToolSchemas.setTimer, ToolSchemas.setAlarm,
                            ToolSchemas.flashlight, ToolSchemas.openApp, SkillSchemas.setReminder,
                            SkillSchemas.addNote, SkillSchemas.weather).map { Noting(it, toolCalls) }),
                        providerFactory = { null }, localFactory = { provider }, clock = { clockMs },
                        // The night recap below compresses the first half of this conversation (the app waits for 60 turns).
                        summarizeAfterTurns = 20,
                    )
                    doc.append("\n## ${lang.name}\n")
                    // What the app does when the chat is opened: the instructions (and Gemma 4's tools) read ahead of the
                    // first message. Its reading is not a reply, so it stays out of the medians.
                    val beforeWarm = stats.size
                    val tw = System.nanoTime()
                    val warmed = runBlocking { agent.warmUpPhone() }
                    val warmTokens = stats.drop(beforeWarm).lastOrNull()?.promptTokens ?: 0
                    while (stats.size > beforeWarm) stats.removeAt(stats.lastIndex)
                    doc.append("\n*(read ahead when the chat opens: %s, %d tokens, %.1f s)*\n".format(java.util.Locale.ROOT, warmed, warmTokens, (System.nanoTime() - tw) / 1e9))
                    for (q in script) {
                        if (q.startsWith("@+") && q.endsWith("d")) {
                            val days = q.removePrefix("@+").removeSuffix("d").toInt()
                            clockMs += days * 86_400_000L
                            doc.append("\n*(${days} days later)*\n")
                            // The night job between the days: her diary for the day that passed (0.15.0), recalled in the morning.
                            val td = System.nanoTime()
                            raw = ""
                            val entry = runCatching { runBlocking { agent.writeDiary(onPhone = true) } }.getOrNull()
                            doc.append("\n**Night diary** (%.1f s): %s\n".format(java.util.Locale.ROOT, (System.nanoTime() - td) / 1e9,
                                when { entry == null -> "— (raw: `" + raw.take(160).replace("\n", " ") + "`)"; entry.text.isBlank() -> "(nothing to write)"; else -> entry.text }))
                            continue
                        }
                        val before = stats.size
                        raw = ""
                        val r = runBlocking { agent.handle(q) }
                        val g = stats.drop(before).lastOrNull()
                        val how = if (g == null) "local answer" else
                            "first words %.1f s, %.1f s total, %d pieces; read %d new of %d prompt tokens".format(java.util.Locale.ROOT,
                                g.firstPieceMs / 1000.0, g.totalMs / 1000.0, g.pieces, g.newTokens, g.promptTokens)
                        val start = raw.trimStart().take(24)
                        val odd = start.isNotEmpty() && !(start[0].isLetterOrDigit() || start[0] in "[«\"*(—-…")
                        val note = if (!odd) "" else "; raw start: `" + start.map { c ->
                            if (c.code < 32 || c.code >= 0x2E80) "\\u%04x".format(c.code) else c.toString() }.joinToString("") + "`"
                        doc.append("\n**Q:** $q\n\n**A** [${r.emotion.name.lowercase()}] ($how$note): ${r.text}\n")
                        if (r.actions.isNotEmpty()) doc.append("\n*(tools: ${r.actions.joinToString("; ") { it.description }})*\n")
                    }
                    // What the night job would keep: a recap of the oldest turns, made by this model.
                    val t1 = System.nanoTime()
                    raw = ""
                    val recaps = runCatching { runBlocking { agent.summarizeOnPhone(maxChunks = 1) } }.getOrElse { -1 }
                    val episodes = memory.all().filter { it.kind == MemoryKind.EPISODE }.joinToString(" | ") { it.text }
                    doc.append("\n**Night recap** (%.1f s, saved: %s): %s\n".format(java.util.Locale.ROOT, (System.nanoTime() - t1) / 1e9, recaps, episodes.ifEmpty { "—" }))
                    // Not kept: what the model wrote, to see why the filter refused it.
                    if (episodes.isEmpty() && raw.isNotBlank()) doc.append("\n*(not kept; the model wrote: `${raw.take(700).replace("\n", " ⏎ ").replace("`", "'")}`)*\n")
                    fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                    summary.append("| $name | ${lang.name} | %.1f | %.1f | %.1f | %.0f | %.0f | %.2f |\n".format(java.util.Locale.ROOT,
                        median(stats.map { it.firstPieceMs / 1000.0 }), median(stats.map { it.totalMs / 1000.0 }), median(stats.map { it.piecesPerSecond }),
                        median(stats.map { it.newTokens.toDouble() }), median(stats.map { it.promptTokens.toDouble() }),
                        // How long a token is in this model's prompts: what the phone fits the dialogue by.
                        median(stats.filter { it.promptTokens > 0 }.map { it.promptChars.toDouble() / it.promptTokens })))
                }
            }
            File(out, "$name.md").writeText(doc.toString())
            println(doc)
        }
        File(out, "summary.md").writeText(summary.toString())
        println(summary)
    }
}
