package ru.iskra.app.data

import android.content.Context
import ru.iskra.core.usage.UsageDay
import ru.iskra.core.usage.UsageStore

/** Today's cloud counters (numbers only, never text) in a small preferences file. */
class PrefsUsageStore(context: Context) : UsageStore {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)

    @Synchronized override fun load() = UsageDay(
        day = prefs.getString("day", "") ?: "",
        llmRequests = prefs.getInt("requests", 0),
        inputTokens = prefs.getLong("in", 0L),
        outputTokens = prefs.getLong("out", 0L),
        estimated = prefs.getBoolean("estimated", false),
        ttsChars = prefs.getLong("tts", 0L),
        warned = prefs.getBoolean("warned", false),
    )

    @Synchronized override fun save(d: UsageDay) {
        prefs.edit().putString("day", d.day).putInt("requests", d.llmRequests).putLong("in", d.inputTokens)
            .putLong("out", d.outputTokens).putBoolean("estimated", d.estimated).putLong("tts", d.ttsChars)
            .putBoolean("warned", d.warned).apply()
    }
}
