package ru.iskra.core.skills

import java.time.LocalDateTime
import kotlin.random.Random

/** How often she may start talking on her own. */
enum class ProactiveFrequency(val title: String, val minGapMin: Int, val perDay: Int) {
    OFF("Не заговаривать самой", 0, 0),
    RARE("Редко (до 3 раз в день)", 150, 3),
    NORMAL("Обычно (до 6 раз в день)", 75, 6),
    OFTEN("Часто (до 10 раз в день)", 40, 10),
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
     */
    fun decide(now: LocalDateTime, nowMs: Long, st: ProactiveState, lastContactMs: Long, screenOnSinceMs: Long?, random: Random): ProactiveTrigger? {
        if (frequency == ProactiveFrequency.OFF || screenOnSinceMs == null || isQuiet(now.hour)) return null
        val today = now.toLocalDate().toString()
        val count = if (st.day == today) st.countToday else 0
        if (count >= frequency.perDay) return null
        if (nowMs - st.lastAtMs < frequency.minGapMin * 60_000L) return null
        val justUnlocked = nowMs - screenOnSinceMs < 2 * 60_000L
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

    fun reminder(text: String): String = pick("REMINDER", listOf(
        "Напоминаю: $text.", "Эй, ты просил напомнить: $text.", "Мрр, время: $text.", "Не забудь: $text.",
    ))

    companion object {
        val MORNING = listOf(
            "Доброе утро! Я уже проснулась. Ну, почти.",
            "Утро! Если что — я тут, зови: «Искра».",
            "Доброе утро. Будильники, таймеры, погода — всё в лапках.",
            "Мрр… утро. Спросить погоду на сегодня?",
            "С добрым утром! Не забудь позавтракать.",
        )
        val RETURN = listOf(
            "О, ты вернулся! Я тут не скучала. Ну, почти.",
            "С возвращением. Если что-то нужно — скажи «Искра».",
            "Мяу, привет! Хорошо, что заглянул.",
            "Я тут, на месте. Есть дела для меня?",
        )
        val IDLE = listOf(
            "Мрр… я тут, если что.",
            "Может, перерыв? Могу поставить таймер на пять минут.",
            "Если надо что-то запомнить — скажи «запиши заметку».",
            "Тихо как-то. Я просто проверяю, что ты на месте.",
            "Попей воды. Это не приказ, это забота. Почти приказ.",
        )
        val EVENING = listOf(
            "Уже вечер. Может, пора отдохнуть?",
            "Вечер! Поставить будильник на утро?",
            "Мрр, поздновато. Не засиживайся, ладно?",
        )
    }
}
