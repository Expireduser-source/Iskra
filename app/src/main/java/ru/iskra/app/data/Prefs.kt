package ru.iskra.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import ru.iskra.core.agent.CompanionState
import ru.iskra.core.agent.CompanionStateStore
import ru.iskra.core.persona.MoodState
import ru.iskra.core.persona.StyleProfile

enum class ProviderPreset(val title: String, val baseUrl: String, val defaultModel: String, val anthropic: Boolean = false) {
    OFFLINE("Без облака (только команды)", "", ""),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", "claude-haiku-5-5", anthropic = true),
    CUSTOM("Свой OpenAI-совместимый сервер", "https://", ""),
}

data class AppSettings(
    val preset: ProviderPreset = ProviderPreset.OFFLINE,
    val baseUrl: String = "",
    val model: String = "",
    val ttsEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val wander: Boolean = true,
    val avatarSizeDp: Int = 120,
)

class SettingsRepo(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        preset = runCatching { ProviderPreset.valueOf(prefs.getString("preset", null) ?: "OFFLINE") }.getOrDefault(ProviderPreset.OFFLINE),
        baseUrl = prefs.getString("baseUrl", "") ?: "",
        model = prefs.getString("model", "") ?: "",
        ttsEnabled = prefs.getBoolean("tts", true),
        overlayEnabled = prefs.getBoolean("overlay", false),
        wander = prefs.getBoolean("wander", true),
        avatarSizeDp = prefs.getInt("avatarSize", 120).coerceIn(72, 220),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("preset", s.preset.name)
            .putString("baseUrl", s.baseUrl.trim())
            .putString("model", s.model.trim())
            .putBoolean("tts", s.ttsEnabled)
            .putBoolean("overlay", s.overlayEnabled)
            .putBoolean("wander", s.wander)
            .putInt("avatarSize", s.avatarSizeDp)
            .apply()
    }
}

/** Mood/style persisted as a small JSON document. */
class PrefsStateStore(context: Context) : CompanionStateStore {
    private val prefs = context.getSharedPreferences("companion_state", Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): CompanionState {
        val raw = prefs.getString("state", null) ?: return CompanionState()
        return try {
            val o = Json.parseToJsonElement(raw).jsonObject
            CompanionState(
                mood = MoodState(
                    valence = o["valence"]!!.jsonPrimitive.double,
                    arousal = o["arousal"]!!.jsonPrimitive.double,
                    bond = o["bond"]!!.jsonPrimitive.double,
                    updatedAtMs = o["moodAt"]!!.jsonPrimitive.long,
                ).clamp(),
                style = StyleProfile(
                    verbosity = o["verbosity"]?.jsonPrimitive?.double ?: 0.0,
                    humor = o["humor"]?.jsonPrimitive?.double ?: 0.0,
                    catSpeech = o["catSpeech"]?.jsonPrimitive?.double ?: 0.0,
                ).clamp(),
                lastUserMessageAtMs = o["lastMsg"]?.jsonPrimitive?.long ?: 0L,
                totalUserMessages = o["total"]?.jsonPrimitive?.long ?: 0L,
            )
        } catch (e: Exception) {
            CompanionState()
        }
    }

    @Synchronized
    override fun save(state: CompanionState) {
        val o = buildJsonObject {
            put("valence", state.mood.valence); put("arousal", state.mood.arousal); put("bond", state.mood.bond)
            put("moodAt", state.mood.updatedAtMs)
            put("verbosity", state.style.verbosity); put("humor", state.style.humor); put("catSpeech", state.style.catSpeech)
            put("lastMsg", state.lastUserMessageAtMs); put("total", state.totalUserMessages)
        }
        prefs.edit().putString("state", o.toString()).apply()
    }

    fun reset() = prefs.edit().clear().apply()
}
