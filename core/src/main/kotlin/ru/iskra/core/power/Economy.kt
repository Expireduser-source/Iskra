package ru.iskra.core.power

/** The owner's choice in Settings → «Экономия». [AUTO] picks [BALANCED] or [BATTERY] from the phone's state. */
enum class EconomyMode(val title: String, val hint: String) {
    AUTO("Авто (рекомендуется)",
        "Сбалансированный, а при экономии энергии Android, заряде до 20 % или на слабом телефоне — как «Батарея»."),
    MAX("Максимум плавности",
        "Пока Искра на экране, весь экран работает на самой высокой частоте (до 120 Гц). Тратит больше всего."),
    BALANCED("Сбалансированный",
        "Высокая частота экрана — только пока она двигается. В покое — 30 кадров/с, а частоту выбирает система."),
    BATTERY("Батарея",
        "30 кадров/с в движении, 20 в покое, спокойный характер, экономный слух, сама заговаривает только облачком."),
}

/** What the overlay window asks of the display. Its vote applies to the whole screen, not only to her. */
enum class RefreshPolicy {
    /** Highest refresh rate all the time (the old behaviour). */
    ALWAYS_HIGH,
    /** Highest only while she moves; no preference when she stands still. */
    HIGH_WHILE_MOVING,
    /** Never ask: the system and the app in front decide. */
    SYSTEM,
}

/** Phone state that [EconomyMode.AUTO] reacts to. [batteryPct] null = unknown. */
data class PowerState(val batteryPct: Int? = null, val charging: Boolean = false, val powerSave: Boolean = false)

enum class DeviceClass(val title: String) { LOW("слабый"), MID("средний"), HIGH("мощный") }

object DeviceClassifier {
    /**
     * [totalRamMb] from ActivityManager.MemoryInfo.totalMem (a "3 GB" phone reports ~2.8 GB),
     * [lowRam] = ActivityManager.isLowRamDevice (Android Go).
     */
    fun classify(totalRamMb: Long, cores: Int, lowRam: Boolean): DeviceClass = when {
        lowRam || totalRamMb in 1..3_300 || cores in 1..4 -> DeviceClass.LOW
        totalRamMb >= 7_000 && cores >= 8 -> DeviceClass.HIGH
        else -> DeviceClass.MID
    }
}

/** What the overlay applies. [mode] is never [EconomyMode.AUTO]. */
data class EconomyPlan(
    val mode: EconomyMode,
    /** Max frames per second while she moves; 0 = every display refresh. */
    val movingFps: Int,
    val idleFps: Int,
    val sleepFps: Int,
    val refresh: RefreshPolicy,
    /** true: economical hearing regardless of the setting; false: the owner's setting. */
    val forceEconomicalEar: Boolean,
    /** Upper bound for «Непоседливость» (0 calm … 2 restless). */
    val maxLiveliness: Int,
    /** Whether unprompted lines may be spoken aloud (otherwise only the bubble). */
    val proactiveVoice: Boolean,
    /** Why this plan, for the journal and the settings screen. */
    val reason: String,
) {
    fun describe(): String {
        val fps = (if (movingFps == 0) "в движении — как у экрана" else "в движении $movingFps кадров/с") + ", в покое $idleFps"
        val hz = when (refresh) {
            RefreshPolicy.ALWAYS_HIGH -> "экран всё время на максимуме"
            RefreshPolicy.HIGH_WHILE_MOVING -> "высокая частота экрана только в движении"
            RefreshPolicy.SYSTEM -> "частоту экрана выбирает система"
        }
        return "${mode.title} ($reason): $fps; $hz" + if (forceEconomicalEar) "; экономный слух" else ""
    }
}

object EconomyPolicy {
    const val LOW_BATTERY_PCT = 20

    fun resolve(choice: EconomyMode, power: PowerState, device: DeviceClass): EconomyPlan {
        val pct = power.batteryPct
        val (mode, reason) = when (choice) {
            EconomyMode.AUTO -> when {
                power.powerSave -> EconomyMode.BATTERY to "включена экономия энергии Android"
                !power.charging && pct != null && pct <= LOW_BATTERY_PCT -> EconomyMode.BATTERY to "заряд $pct %"
                device == DeviceClass.LOW -> EconomyMode.BATTERY to "телефону лучше полегче"
                else -> EconomyMode.BALANCED to "авто"
            }
            else -> choice to "выбрано в настройках"
        }
        return when (mode) {
            EconomyMode.MAX -> EconomyPlan(mode, 0, 30, 10, RefreshPolicy.ALWAYS_HIGH, false, 2, true, reason)
            EconomyMode.BATTERY -> EconomyPlan(mode, 30, 20, 5, RefreshPolicy.SYSTEM, true, 0, false, reason)
            else -> EconomyPlan(EconomyMode.BALANCED, 0, 30, 10, RefreshPolicy.HIGH_WHILE_MOVING, false, 2, true, reason)
        }
    }

    /** Settings from before 0.9 had only a frame-rate choice ("AUTO", "HZ60", "ECO"). */
    fun fromLegacyFps(name: String?): EconomyMode = when (name) {
        "ECO" -> EconomyMode.BATTERY
        "HZ60" -> EconomyMode.BALANCED
        else -> EconomyMode.AUTO
    }
}

/**
 * Debounces "she is moving" for the display vote: on at once, off only after [holdMs] of stillness,
 * so short pauses between steps do not switch the screen mode back and forth.
 */
class MotionVote(private val holdMs: Long = 1500) {
    var moving = false
        private set
    private var stillSince = -1L

    /** Returns true when [moving] changed. */
    fun update(movingNow: Boolean, nowMs: Long): Boolean {
        if (movingNow) {
            stillSince = -1L
            if (!moving) { moving = true; return true }
            return false
        }
        if (!moving) return false
        if (stillSince < 0) stillSince = nowMs
        if (nowMs - stillSince >= holdMs) { moving = false; stillSince = -1L; return true }
        return false
    }
}
