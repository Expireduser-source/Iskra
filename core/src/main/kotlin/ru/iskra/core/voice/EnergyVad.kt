package ru.iskra.core.voice

/**
 * Cheap voice activity detector for the always-on ear: decides per audio chunk whether the speech
 * recogniser needs to run. In a quiet room most chunks are silence, so the recogniser (the costly
 * part) sleeps most of the time.
 *
 * Speech = loudness clearly above the adaptive noise floor. After speech ends the recogniser keeps
 * getting [hangoverMs] of audio so it can finish the phrase; when speech starts, the last
 * [preRollMs] are replayed so the first syllable ("Ис-") is not lost.
 */
class EnergyVad(
    private val chunkMs: Int = 100,
    private val hangoverMs: Int = 900,
    val preRollMs: Int = 400,
    private val ratio: Double = 2.2,
    private val absoluteMin: Double = 160.0,
) {
    enum class Decision { SILENCE, START, SPEECH, END }

    var noiseFloor = 300.0; private set
    private var hangover = 0
    private var active = false

    val preRollChunks get() = (preRollMs + chunkMs - 1) / chunkMs

    /** [rms] of one chunk of 16-bit audio. */
    fun update(rms: Double): Decision {
        val loud = rms > maxOf(noiseFloor * ratio, absoluteMin)
        // Floor follows quiet chunks quickly and loud ones very slowly (steady fan/traffic noise).
        noiseFloor = if (rms < noiseFloor) noiseFloor * 0.85 + rms * 0.15
        else if (!loud) noiseFloor + (rms - noiseFloor) * 0.05
        else noiseFloor + (rms - noiseFloor) * 0.002
        noiseFloor = noiseFloor.coerceIn(30.0, 6000.0)
        return when {
            loud && !active -> { active = true; hangover = hangoverMs / chunkMs; Decision.START }
            loud -> { hangover = hangoverMs / chunkMs; Decision.SPEECH }
            active && hangover > 0 -> { hangover--; Decision.SPEECH }
            active -> { active = false; Decision.END }
            else -> Decision.SILENCE
        }
    }

    fun reset() { active = false; hangover = 0 }

    companion object {
        fun rms(buf: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
            return kotlin.math.sqrt(sum / n)
        }
    }
}
