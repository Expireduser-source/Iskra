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
import ru.iskra.core.voice.ElevenLabsTts

enum class ProviderPreset(val title: String, val baseUrl: String, val defaultModel: String, val anthropic: Boolean = false) {
    OFFLINE("Без облака (только команды)", "", ""),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", "claude-haiku-5-5", anthropic = true),
    CUSTOM("Свой OpenAI-совместимый сервер", "https://", ""),
}

/** Who speaks Iskra's replies. Cloud engines get only the reply text, never the dialogue. */
enum class TtsEngine(val title: String, val defaultVoice: String, val defaultModel: String, val needsKey: Boolean, val note: String) {
    SYSTEM("Голос Android", "", "", false, "Бесплатно и без интернета, но звучит механически."),
    YANDEX("Yandex SpeechKit", "jane", "", true,
        "Лучшие русские голоса, оплата в рублях (~1–2 ₽ за длинный ответ). Ключ: Yandex Cloud → сервисный аккаунт → API-ключ. Голоса: jane, alena, omazh, filipp…"),
    ELEVENLABS("ElevenLabs", ElevenLabsTts.DEFAULT_VOICE, "eleven_flash_v2_5", true,
        "Очень живые голоса, есть бесплатный лимит (~10 000 символов в месяц). Голос — это voice_id из их библиотеки."),
    OPENAI("OpenAI", "nova", "gpt-4o-mini-tts", true,
        "Голос слушается указаний по эмоциям. Голоса: nova, shimmer, coral, sage… Если «мозг» тоже OpenAI, ключ можно не вводить."),
    CUSTOM("Свой сервер (OpenAI-совместимый)", "", "", false,
        "Например, свой сервер с Silero/XTTS, отвечающий на /v1/audio/speech. Только https."),
}

/** How often the character is redrawn. */
enum class FpsMode(val title: String) {
    AUTO("Как у экрана (до 120 Гц)"),
    HZ60("60 кадров/с"),
    ECO("Экономия (30 кадров/с)"),
}

data class AppSettings(
    val preset: ProviderPreset = ProviderPreset.OFFLINE,
    val baseUrl: String = "",
    val model: String = "",
    val ttsEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val wander: Boolean = true,
    val avatarSizeDp: Int = 120,
    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    val ttsVoice: String = "",
    val ttsModel: String = "",
    val ttsBaseUrl: String = "",
    val alwaysListen: Boolean = false,
    val listenScreenOff: Boolean = false,
    val followUp: Boolean = true,
    val wakeNames: String = "Искра, Искорка",
    val fpsMode: FpsMode = FpsMode.AUTO,
    val liveliness: Int = 1,
) {
    val voiceOrDefault get() = ttsVoice.ifBlank { ttsEngine.defaultVoice }
    val ttsModelOrDefault get() = ttsModel.ifBlank { ttsEngine.defaultModel }
    val wakeNameList get() = wakeNames.split(',', ';').map { it.trim() }.filter { it.length >= 3 }
}

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
        ttsEngine = runCatching { TtsEngine.valueOf(prefs.getString("ttsEngine", null) ?: "SYSTEM") }.getOrDefault(TtsEngine.SYSTEM),
        ttsVoice = prefs.getString("ttsVoice", "") ?: "",
        ttsModel = prefs.getString("ttsModel", "") ?: "",
        ttsBaseUrl = prefs.getString("ttsBaseUrl", "") ?: "",
        alwaysListen = prefs.getBoolean("alwaysListen", false),
        listenScreenOff = prefs.getBoolean("listenScreenOff", false),
        followUp = prefs.getBoolean("followUp", true),
        wakeNames = prefs.getString("wakeNames", null) ?: "Искра, Искорка",
        fpsMode = runCatching { FpsMode.valueOf(prefs.getString("fpsMode", null) ?: "AUTO") }.getOrDefault(FpsMode.AUTO),
        liveliness = prefs.getInt("liveliness", 1).coerceIn(0, 2),
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
            .putString("ttsEngine", s.ttsEngine.name)
            .putString("ttsVoice", s.ttsVoice.trim())
            .putString("ttsModel", s.ttsModel.trim())
            .putString("ttsBaseUrl", s.ttsBaseUrl.trim())
            .putBoolean("alwaysListen", s.alwaysListen)
            .putBoolean("listenScreenOff", s.listenScreenOff)
            .putBoolean("followUp", s.followUp)
            .putString("wakeNames", s.wakeNames)
            .putString("fpsMode", s.fpsMode.name)
            .putInt("liveliness", s.liveliness)
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
