package ru.iskra.core

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
    private val ru = listOf(
        "Привет! Как у тебя дела?",
        "Меня зовут Аня.",
        "Я учусь на врача, сейчас сессия.",
        "Посоветуй, как не заснуть на скучной лекции.",
        "Расскажи очень короткую сказку про кота-космонавта.",
        "Объясни простыми словами, что такое фотосинтез.",
        "Мне сегодня грустно, поддержи меня.",
        "Как меня зовут и на кого я учусь?",
        "Переведи на английский: «Я люблю зимние вечера».",
        "Что лучше на завтрак перед экзаменом: каша или омлет?",
        "Ты человек?",
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
        val summary = StringBuilder("| model | lang | first words, s (median) | reply, s (median) | pieces/s (median) | new prompt tokens (median) |\n|---|---|---|---|---|---|\n")
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
                    val provider = LocalLlmProvider({ e }, ChatFormat.valueOf(format), "local:$name", onStats = { stats += it })
                    val agent = CompanionAgent(
                        persona = Personas.ISKRA, memory = MemoryManager(InMemoryMemoryStore()), conversation = InMemoryConversationStore(),
                        stateStore = InMemoryStateStore(), tools = ToolRegistry(), policy = SafetyPolicy({ _, _ -> true }),
                        providerFactory = { null }, localFactory = { provider },
                    )
                    doc.append("\n## ${lang.name}\n")
                    for (q in script) {
                        val before = stats.size
                        val r = runBlocking { agent.handle(q) }
                        val g = stats.drop(before).lastOrNull()
                        val how = if (g == null) "local answer" else
                            "first words %.1f s, %.1f s total, %d pieces; read %d new of %d prompt tokens".format(java.util.Locale.ROOT,
                                g.firstPieceMs / 1000.0, g.totalMs / 1000.0, g.pieces, g.newTokens, g.promptTokens)
                        doc.append("\n**Q:** $q\n\n**A** [${r.emotion.name.lowercase()}] ($how): ${r.text}\n")
                    }
                    fun median(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                    summary.append("| $name | ${lang.name} | %.1f | %.1f | %.1f | %.0f |\n".format(java.util.Locale.ROOT,
                        median(stats.map { it.firstPieceMs / 1000.0 }), median(stats.map { it.totalMs / 1000.0 }), median(stats.map { it.piecesPerSecond }),
                        median(stats.map { it.newTokens.toDouble() })))
                }
            }
            File(out, "$name.md").writeText(doc.toString())
            println(doc)
        }
        File(out, "summary.md").writeText(summary.toString())
        println(summary)
    }
}
