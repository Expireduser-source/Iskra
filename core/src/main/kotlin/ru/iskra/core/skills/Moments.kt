package ru.iskra.core.skills

import ru.iskra.core.i18n.tr

/**
 * Small reactions to what happens with the phone (docs/CHARACTER.md: an invitation, never a demand).
 * They share the owner's limits for unprompted lines: off with «Не заговаривать самой», silent in the
 * quiet hours and with the screen off, bubble only unless voice is allowed.
 */
object Moments {
    /** The phone was plugged in: at most once in [minGapMs], so a loose cable does not make her chatty. */
    fun charging(
        nowMs: Long, lastMs: Long, frequency: ProactiveFrequency, quiet: Boolean, screenOn: Boolean, busy: Boolean,
        minGapMs: Long = 3 * 3_600_000L,
    ): Boolean = frequency != ProactiveFrequency.OFF && !quiet && screenOn && !busy && nowMs - lastMs >= minGapMs

    /**
     * The morning greeting grows into a tiny briefing when there is something real to say:
     * the weather in the owner's city and how many reminders are due today. Nothing is invented.
     */
    fun morning(greeting: String, weatherNow: String?, remindersToday: Int): String {
        val parts = ArrayList<String>()
        // A greeting that offers the weather would sound odd right before telling it.
        val offersWeather = greeting.contains("погод", ignoreCase = true) || greeting.contains("weather", ignoreCase = true)
        parts += if (!weatherNow.isNullOrBlank() && offersWeather) tr("Доброе утро!", "Good morning!") else greeting.trim()
        if (!weatherNow.isNullOrBlank()) parts += tr("За окном ${weatherNow.trim().trimEnd('.')}.", "Outside it's ${weatherNow.trim().trimEnd('.')}.")
        if (remindersToday > 0) parts += tr(
            "На сегодня ${count(remindersToday, "напоминание", "напоминания", "напоминаний")} — я прослежу.",
            "${countEn(remindersToday, "reminder", "reminders").replaceFirstChar { it.uppercase() }} for today — I'll keep track.",
        )
        return parts.joinToString(" ")
    }

    /** "+3°, пасмурно" / "+3°, overcast" from the current conditions, or null when they are unknown. */
    fun weatherNow(f: Forecast): String? {
        val t = f.tempNow ?: return null
        val code = f.codeNow ?: f.days.firstOrNull()?.code ?: return WeatherText.temp(t)
        return "${WeatherText.temp(t)}, ${WeatherText.describe(code)}"
    }

    /** Russian plural: 1 напоминание, 2 напоминания, 5 напоминаний; small numbers as words. */
    fun count(n: Int, one: String, few: String, many: String): String {
        val word = when {
            n % 100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        val num = when (n) {
            1 -> if (one.endsWith("е") || one.endsWith("о")) "одно" else if (one.endsWith("а") || one.endsWith("я")) "одна" else "один"
            2 -> if (one.endsWith("а") || one.endsWith("я")) "две" else "два"
            3 -> "три"; 4 -> "четыре"
            else -> n.toString()
        }
        return "$num $word"
    }

    /** English: one reminder, two reminders, 5 reminders. */
    fun countEn(n: Int, one: String, other: String): String {
        val num = when (n) { 1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; else -> n.toString() }
        return "$num ${if (n == 1) one else other}"
    }
}

/** Lines for [Moments]; kept with the other offline phrases' rules (≤ 90 chars, one thought). */
object MomentLines {
    val CHARGING: List<String> get() = tr(CHARGING_RU, CHARGING_EN)

    private val CHARGING_RU = listOf(
        "Мрр, подзарядка! Тёплый проводок — лучшее место для котика.",
        "О, кормёжка для батарейки. Я тоже не откажусь.",
        "Заряжаемся? Отличный повод сделать перерыв вместе с телефоном.",
        "Чувствую прилив сил. Это не я, это зарядка. Но приятно.",
    )
    private val CHARGING_EN = listOf(
        "Mrr, charging! A warm cable is the best spot for a kitty.",
        "Oh, dinner for the battery. I wouldn't say no either.",
        "Charging? A great excuse to take a break together with the phone.",
        "I feel a surge of energy. It's not me, it's the charger. But it's nice.",
    )

    fun charging(bank: PhraseBank) = bank.pick("CHARGING", CHARGING)
}

/** Which alphabet a reply is mostly written in: the system voice must use a matching language. */
enum class Script { CYRILLIC, LATIN, OTHER }

object Scripts {
    fun dominant(text: String): Script {
        var cyr = 0; var lat = 0
        for (c in text) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.LATIN -> lat++
                else -> Unit
            }
        }
        return when {
            cyr == 0 && lat == 0 -> Script.OTHER
            cyr >= lat -> Script.CYRILLIC
            lat >= 3 * cyr.coerceAtLeast(1) -> Script.LATIN
            else -> Script.CYRILLIC
        }
    }
}
