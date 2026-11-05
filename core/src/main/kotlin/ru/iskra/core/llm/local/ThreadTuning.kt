package ru.iskra.core.llm.local

/**
 * Threads for this very phone, measured instead of guessed. Phones mix fast and slow cores (Redmi Note 14: two fast,
 * six slow): whether the slow ones help reading a prompt, or only make the fast ones wait, differs from phone to phone
 * and from model to model. Writing (one token at a time) and reading (many at once) are measured separately.
 */
object ThreadTuning {
    data class Result(val gen: Int, val batch: Int, val tried: Map<Int, EngineSpeed>) {
        val speed: EngineSpeed get() = EngineSpeed(tried.getValue(batch).read, tried.getValue(gen).write)
    }

    /** Thread counts worth trying with [fast] performance cores out of [all]: the fast ones, two more, all of them. */
    fun candidates(fast: Int, all: Int): List<Int> {
        val n = all.coerceAtLeast(1)
        return listOf(fast, fast + 2, n).map { it.coerceIn(1, n) }.distinct().sorted()
    }

    /**
     * Measures every candidate and sets the best: the fewest threads within 5% of the fastest (fewer threads, less heat,
     * the screen stays smooth). Takes seconds (tens for a 4B model on a mid-range phone). Null if nothing could be measured.
     */
    fun calibrate(engine: LlamaEngine, fast: Int, all: Int, nPrompt: Int = 96, nGen: Int = 16): Result? {
        val options = candidates(fast, all)
        // A first short run brings the model's weights into memory, so the first candidate isn't measured cold.
        engine.setThreads(options.first(), options.first())
        engine.bench(16, 2)
        val tried = LinkedHashMap<Int, EngineSpeed>()
        for (t in options) {
            engine.setThreads(t, t)
            engine.bench(nPrompt, nGen)?.let { tried[t] = it }
        }
        if (tried.isEmpty()) return null
        fun best(of: (EngineSpeed) -> Double): Int {
            val top = tried.values.maxOf(of)
            return tried.entries.filter { of(it.value) >= top * 0.95 }.minOf { it.key }
        }
        val gen = best { it.write }
        val batch = best { it.read }
        engine.setThreads(gen, batch)
        return Result(gen, batch, tried)
    }
}
