package ru.iskra.core.power

import ru.iskra.core.i18n.tr

/** The owner's choice in Settings → «Экономия» / “Economy”. [AUTO] picks [BALANCED] or [BATTERY] from the phone's state. */
enum class EconomyMode(private val ru: Pair<String, String>, private val en: Pair<String, String>) {
    AUTO("Авто (рекомендуется)" to
        "Сбалансированный, а при экономии энергии Android, заряде до 20 % или на слабом телефоне — как «Батарея».",
        "Auto (recommended)" to
        "Balanced, and like “Battery” when Android battery saver is on, the battery is at 20 % or less, or the phone is weak."),
    MAX("Максимум плавности" to
        "Пока Искра на экране, весь экран работает на самой высокой частоте (до 120 Гц). Тратит больше всего.",
        "Maximum smoothness" to
        "While Iskra is on screen, the whole screen runs at its highest refresh rate (up to 120 Hz). Uses the most battery."),
    BALANCED("Сбалансированный" to
        "Высокая частота экрана — только пока она двигается. В покое — 30 кадров/с, а частоту выбирает система.",
        "Balanced" to
        "High refresh rate only while she moves. When idle — 30 fps, and the system picks the refresh rate."),
    BATTERY("Батарея" to
        "30 кадров/с в движении, 20 в покое, спокойный характер, экономный слух, сама заговаривает только облачком.",
        "Battery" to
        "30 fps while moving, 20 when idle, calm character, economical hearing, speaks up on her own only in a bubble."),
    ;

    val title: String get() = tr(ru.first, en.first)
    val hint: String get() = tr(ru.second, en.second)
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

enum class DeviceClass(private val ru: String, private val en: String) {
    LOW("слабый", "weak"), MID("средний", "mid-range"), HIGH("мощный", "powerful");

    val title: String get() = tr(ru, en)
}

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
        val fps = (if (movingFps == 0) tr("в движении — как у экрана", "while moving — like the screen") else tr("в движении $movingFps кадров/с", "while moving $movingFps fps")) + tr(", в покое $idleFps", ", idle $idleFps")
        val hz = when (refresh) {
            RefreshPolicy.ALWAYS_HIGH -> tr("экран всё время на максимуме", "screen always at maximum")
            RefreshPolicy.HIGH_WHILE_MOVING -> tr("высокая частота экрана только в движении", "high refresh rate only while moving")
            RefreshPolicy.SYSTEM -> tr("частоту экрана выбирает система", "the system picks the refresh rate")
        }
        return "${mode.title} ($reason): $fps; $hz" + if (forceEconomicalEar) tr("; экономный слух", "; economical hearing") else ""
    }
}

object EconomyPolicy {
    const val LOW_BATTERY_PCT = 20

    fun resolve(choice: EconomyMode, power: PowerState, device: DeviceClass): EconomyPlan {
        val pct = power.batteryPct
        val (mode, reason) = when (choice) {
            EconomyMode.AUTO -> when {
                power.powerSave -> EconomyMode.BATTERY to tr("включена экономия энергии Android", "Android battery saver is on")
                !power.charging && pct != null && pct <= LOW_BATTERY_PCT -> EconomyMode.BATTERY to tr("заряд $pct %", "battery $pct %")
                device == DeviceClass.LOW -> EconomyMode.BATTERY to tr("телефону лучше полегче", "the phone needs it lighter")
                else -> EconomyMode.BALANCED to tr("авто", "auto")
            }
            else -> choice to tr("выбрано в настройках", "chosen in settings")
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
