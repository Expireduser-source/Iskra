package ru.iskra.core.skills

import ru.iskra.core.i18n.tr
import java.time.LocalDateTime
import kotlin.random.Random

/** How often she may start talking on her own. */
enum class ProactiveFrequency(private val ru: String, private val en: String, val minGapMin: Int, val perDay: Int) {
    OFF("Не заговаривать самой", "Never speak up on her own", 0, 0),
    RARE("Редко (до 3 раз в день)", "Rarely (up to 3 times a day)", 150, 3),
    NORMAL("Обычно (до 6 раз в день)", "Normal (up to 6 times a day)", 75, 6),
    OFTEN("Часто (до 10 раз в день)", "Often (up to 10 times a day)", 40, 10),
    ;

    val title: String get() = tr(ru, en)
}

enum class ProactiveTrigger { MORNING, RETURN, IDLE, EVENING }

/** What the policy remembers between checks (persisted by the app). */
data class ProactiveState(
    val day: String = "",
    val countToday: Int = 0,
    val lastAtMs: Long = 0,
    val morningDay: String = "",
    val eveningDay: String = "",
)

/**
 * When Iskra may say something unprompted. Hard limits first, triggers second:
 *  - never during quiet hours, never with the screen off, at most [ProactiveFrequency.perDay] times
 *    a day and not more often than [ProactiveFrequency.minGapMin];
 *  - MORNING: the first unlock of the day after the quiet hours (until 11:00);
 *  - RETURN: the screen came on after ≥ 3 h without talking to her;
 *  - EVENING: once in the evening (21:00 until the quiet hours), sometimes;
 *  - IDLE: rarely, after a long time without contact while the screen is on.
 * Pure logic; the app calls [decide] every few minutes and [record] after speaking.
 */
class ProactivePolicy(
    private val frequency: ProactiveFrequency,
    private val quietFromHour: Int = 23,
    private val quietToHour: Int = 8,
) {
    fun isQuiet(hour: Int): Boolean =
        if (quietFromHour == quietToHour) false
        else if (quietFromHour < quietToHour) hour in quietFromHour until quietToHour
        else hour >= quietFromHour || hour < quietToHour

    /**
     * [screenOnSinceMs]: when the screen was last turned on, null if it is off.
     * [lastContactMs]: last message or tap from the owner.
     * [unlockedSinceMs]: when the owner unlocked the screen; distinct from merely waking it.
     * [listening] covers both wake-word attention and the chat's microphone.
     */
    fun decide(
        now: LocalDateTime, nowMs: Long, st: ProactiveState, lastContactMs: Long,
        screenOnSinceMs: Long?, random: Random,
        unlockedSinceMs: Long? = screenOnSinceMs, screenLocked: Boolean = false, listening: Boolean = false,
    ): ProactiveTrigger? {
        if (frequency == ProactiveFrequency.OFF || screenOnSinceMs == null || screenLocked || listening || isQuiet(now.hour)) return null
        val today = now.toLocalDate().toString()
        val count = if (st.day == today) st.countToday else 0
        if (count >= frequency.perDay) return null
        if (nowMs - st.lastAtMs < frequency.minGapMin * 60_000L) return null
        val justUnlocked = unlockedSinceMs != null && nowMs - unlockedSinceMs in 0L until 2 * 60_000L
        val sinceContact = nowMs - lastContactMs
        return when {
            justUnlocked && st.morningDay != today && now.hour < 11 -> ProactiveTrigger.MORNING
            justUnlocked && sinceContact >= 3 * 3_600_000L -> ProactiveTrigger.RETURN
            now.hour >= 21 && st.eveningDay != today && random.nextFloat() < 0.3f -> ProactiveTrigger.EVENING
            sinceContact >= 2L * frequency.minGapMin * 60_000L && nowMs - screenOnSinceMs >= 10 * 60_000L && random.nextFloat() < 0.25f -> ProactiveTrigger.IDLE
            else -> null
        }
    }

    fun record(trigger: ProactiveTrigger, now: LocalDateTime, nowMs: Long, st: ProactiveState): ProactiveState {
        val today = now.toLocalDate().toString()
        return st.copy(
            day = today,
            countToday = (if (st.day == today) st.countToday else 0) + 1,
            lastAtMs = nowMs,
            morningDay = if (trigger == ProactiveTrigger.MORNING) today else st.morningDay,
            eveningDay = if (trigger == ProactiveTrigger.EVENING) today else st.eveningDay,
        )
    }
}

/**
 * Offline lines in Iskra's voice (docs/CHARACTER.md): short, warm, a little cheeky, never guilt-tripping.
 * [pick] avoids repeating the last few lines of a category.
 */
class PhraseBank(private val random: Random = Random.Default) {
    private val recent = HashMap<String, ArrayDeque<Int>>()

    fun pick(category: String, lines: List<String>): String {
        if (lines.size == 1) return lines[0]
        val last = recent.getOrPut(category) { ArrayDeque() }
        val choices = lines.indices.filter { it !in last }.ifEmpty { lines.indices.toList() }
        val i = choices[random.nextInt(choices.size)]
        last.addLast(i); while (last.size > minOf(3, lines.size - 1)) last.removeFirst()
        return lines[i]
    }

    fun proactive(t: ProactiveTrigger): String = pick(t.name, when (t) {
        ProactiveTrigger.MORNING -> MORNING
        ProactiveTrigger.RETURN -> RETURN
        ProactiveTrigger.IDLE -> IDLE
        ProactiveTrigger.EVENING -> EVENING
    })

    fun reminder(text: String): String = pick("REMINDER", tr(
        listOf("Напоминаю: $text.", "Эй, напоминаю, как договаривались: $text.", "Мрр, время: $text.", "Не забудь: $text."),
        listOf("Reminder: $text.", "Hey, you asked me to remind you: $text.", "Mrr, it's time: $text.", "Don't forget: $text."),
    ))

    companion object {
        val MORNING: List<String> get() = tr(MORNING_RU, MORNING_EN)
        val RETURN: List<String> get() = tr(RETURN_RU, RETURN_EN)
        val IDLE: List<String> get() = tr(IDLE_RU, IDLE_EN)
        val EVENING: List<String> get() = tr(EVENING_RU, EVENING_EN)

        private val MORNING_EN = listOf(
            "Good morning! I'm already awake. Well, almost.",
            "Morning! If you need me — I'm here, just call “Iskra”.",
            "Good morning. Alarms, timers, weather — all in my paws.",
            "Mrr… morning. Shall I check today's weather?",
            "Good morning! Don't forget breakfast.",
        )
        private val RETURN_EN = listOf(
            "Oh, you're back! I didn't miss you. Well, almost.",
            "Welcome back. If you need something — say “Iskra”.",
            "Meow, hi! Glad you stopped by.",
            "I'm right here. Any tasks for me?",
        )
        private val IDLE_EN = listOf(
            "Mrr… I'm here if you need me.",
            "Maybe a break? I can set a five-minute timer.",
            "If you need to remember something — say “note: …”.",
            "It's quiet. Just checking you're still here.",
            "Drink some water. Not an order, just care. Almost an order.",
        )
        private val EVENING_EN = listOf(
            "It's evening already. Maybe time to rest?",
            "Evening! Shall I set an alarm for the morning?",
            "Mrr, it's getting late. Don't stay up too long, okay?",
        )

        private val MORNING_RU = listOf(
            "Доброе утро! Я уже проснулась. Ну, почти.",
            "Утро! Если что — я тут, зови: «Искра».",
            "Доброе утро. Будильники, таймеры, погода — всё в лапках.",
            "Мрр… утро. Спросить погоду на сегодня?",
            "С добрым утром! Не забудь позавтракать.",
        )
        private val RETURN_RU = listOf(
            "О, ты снова тут! Я не скучала. Ну, почти.",
            "С возвращением. Если что-то нужно — скажи «Искра».",
            "Мяу, привет! Хорошо, что ты заглядываешь.",
            "Я тут, на месте. Есть дела для меня?",
        )
        private val IDLE_RU = listOf(
            "Мрр… я тут, если что.",
            "Может, перерыв? Могу поставить таймер на пять минут.",
            "Если надо что-то запомнить — скажи «запиши заметку».",
            "Тихо как-то. Я просто проверяю, что ты на месте.",
            "Попей воды. Это не приказ, это забота. Почти приказ.",
        )
        private val EVENING_RU = listOf(
            "Уже вечер. Может, пора отдохнуть?",
            "Вечер! Поставить будильник на утро?",
            "Мрр, поздновато. Не засиживайся, ладно?",
        )
    }
}
