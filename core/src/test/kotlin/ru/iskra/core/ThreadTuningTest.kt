package ru.iskra.core

import ru.iskra.core.llm.local.EngineSpeed
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.ThreadTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A phone with two fast and six slow cores, as a fake engine: speeds per thread count. */
private class FakePhone(private val speeds: Map<Int, EngineSpeed?>) : LlamaEngine {
    val calls = mutableListOf<Pair<Int, Int>>()
    var threads = 0 to 0
    override fun generate(prompt: String, maxTokens: Int, temperature: Float, stable: Int, head: Int, onPiece: (String) -> Boolean) = ""
    override fun cancel() = Unit
    override fun close() = Unit
    override val info = "fake"
    override val contextSize = 2048
    override fun setThreads(gen: Int, batch: Int) { threads = gen to batch; calls += threads }
    override fun bench(nPrompt: Int, nGen: Int): EngineSpeed? = if (nPrompt < 32) EngineSpeed(1.0, 1.0) else speeds[threads.first]
}

class ThreadTuningTest {
    @Test
    fun candidatesAreTheFastCoresTwoMoreAndAll() {
        assertEquals(listOf(2, 4, 8), ThreadTuning.candidates(2, 8))
        assertEquals(listOf(4, 6, 8), ThreadTuning.candidates(4, 8))
        assertEquals(listOf(2, 4), ThreadTuning.candidates(2, 4))
        assertEquals(listOf(1), ThreadTuning.candidates(1, 1))
    }

    @Test
    fun writingAndReadingGetTheirOwnBestThreads() {
        // Slow cores help reading (many tokens at once) but make writing (one at a time) wait.
        val phone = FakePhone(mapOf(2 to EngineSpeed(40.0, 12.0), 4 to EngineSpeed(70.0, 10.0), 8 to EngineSpeed(68.0, 6.0)))
        val r = ThreadTuning.calibrate(phone, fast = 2, all = 8)!!
        assertEquals(2, r.gen)
        assertEquals(4, r.batch)  // 8 is within 5% of 4 but costs more: the fewer win
        assertEquals(2 to 4, phone.threads)  // and it is set
        assertEquals(EngineSpeed(70.0, 12.0), r.speed)
        assertEquals(listOf(2, 2, 4, 8), phone.calls.dropLast(1).map { it.first })  // warm-up, then each candidate
    }

    @Test
    fun nothingMeasuredMeansNoChange() {
        val phone = FakePhone(mapOf(2 to null, 4 to null, 8 to null))
        assertNull(ThreadTuning.calibrate(phone, fast = 2, all = 8))
    }
}
