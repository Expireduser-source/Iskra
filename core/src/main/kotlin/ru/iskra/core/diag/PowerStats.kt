package ru.iskra.core.diag

import ru.iskra.core.i18n.tr

/**
 * One reading: phone battery level, whether it charges, the screen, which look is drawn, and the CPU
 * time our process has used so far ([cpuMs], from Process.getElapsedCpuTime()).
 */
data class PowerSample(
    val timeMs: Long,         // elapsedRealtime: keeps counting in deep sleep
    val levelPct: Float,
    val charging: Boolean,
    val screenOn: Boolean,
    val look: String,
    val cpuMs: Long,
)

/** Battery drain and our CPU share over a window, only for stretches without charging. */
data class PowerSummary(
    val minutes: Float,
    val drainPctPerHour: Float?,  // null: not enough time or no level change yet
    val cpuPct: Float,            // our process' CPU time / wall time, %
    val screenOffShare: Float,    // 0..1 of the window with the screen off
    val looks: Set<String>,
) {
    fun describe(): String = buildString {
        append(tr("за ${minutes.toInt()} мин без зарядки: ", "over ${minutes.toInt()} min off the charger: "))
        append(drainPctPerHour?.let { tr("батарея −%.1f %%/ч", "battery −%.1f %%/h").format(it) } ?: tr("батарея: мало данных", "battery: not enough data"))
        append(tr(", CPU Искры %.1f %%", ", Iskra CPU %.1f %%").format(cpuPct))
        append(tr(", экран выкл ${(screenOffShare * 100).toInt()} %", ", screen off ${(screenOffShare * 100).toInt()} %"))
        if (looks.isNotEmpty()) append(tr(", вид ${looks.joinToString("/")}", ", look ${looks.joinToString("/")}"))
    }
}

/**
 * Keeps the last readings (one every ~5 min) and sums up the last stretch without charging.
 * Battery % is the whole phone's drain, not only Iskra's; the CPU share is ours alone.
 */
class PowerStats(private val capacity: Int = 400) {
    private val samples = ArrayDeque<PowerSample>()

    @Synchronized fun add(s: PowerSample) {
        samples.addLast(s)
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized fun all(): List<PowerSample> = samples.toList()

    /** The most recent stretch without charging, at most [windowMs] long. */
    @Synchronized fun summary(windowMs: Long = 60 * 60_000L): PowerSummary? {
        if (samples.size < 2) return null
        val last = samples.last()
        if (last.charging) return null
        val run = ArrayList<PowerSample>()
        for (s in samples.reversed()) {
            if (s.charging || last.timeMs - s.timeMs > windowMs) break
            // A restart of the process resets its CPU counter: the stretch ends there.
            if (run.isNotEmpty() && s.cpuMs > run.last().cpuMs) break
            run.add(s)
        }
        if (run.size < 2) return null
        run.reverse()
        val first = run.first()
        val ms = (last.timeMs - first.timeMs).coerceAtLeast(1)
        val minutes = ms / 60_000f
        val dropped = first.levelPct - last.levelPct
        val drain = if (minutes >= 20f && dropped > 0f) dropped / (ms / 3_600_000f) else null
        var off = 0L
        for (i in 1 until run.size) if (!run[i - 1].screenOn) off += run[i].timeMs - run[i - 1].timeMs
        return PowerSummary(
            minutes = minutes,
            drainPctPerHour = drain,
            cpuPct = (last.cpuMs - first.cpuMs) * 100f / ms,
            screenOffShare = off.toFloat() / ms,
            looks = run.map { it.look }.toSet(),
        )
    }
}
