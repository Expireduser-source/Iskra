package ru.iskra.app.data

import ru.iskra.core.persona.ModelRule
import ru.iskra.core.i18n.tr
import ru.iskra.core.power.EconomyMode
import ru.iskra.core.usage.UsageLimits
import ru.iskra.core.power.EconomyPolicy
import ru.iskra.core.skills.Place
import ru.iskra.core.skills.ProactiveFrequency

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

enum class ProviderPreset(private val ruTitle: String, val baseUrl: String, val defaultModel: String, val anthropic: Boolean = false,
    private val enTitle: String? = null) {
    OFFLINE("Без облака (только команды)", "", "", enTitle = "No cloud (commands only)"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", "claude-haiku-5-5", anthropic = true),
    CUSTOM("Свой OpenAI-совместимый сервер", "https://", "", enTitle = "Own OpenAI-compatible server"),
    ;

    val title: String get() = tr(ruTitle, enTitle ?: ruTitle)
}

/** Who speaks Iskra's replies. Cloud engines get only the reply text, never the dialogue. */
enum class TtsEngine(private val ruTitle: String, val defaultVoice: String, val defaultModel: String, val needsKey: Boolean,
    private val ruNote: String, private val enTitle: String? = null, private val enNote: String = ruNote) {
    SYSTEM("Голос Android", "", "", false, "Бесплатно и без интернета, но звучит механически.",
        "Android voice", "Free and offline, but sounds mechanical."),
    YANDEX("Yandex SpeechKit", "jane", "", true,
        "Лучшие русские голоса, оплата в рублях (~1–2 ₽ за длинный ответ). Ключ: Yandex Cloud → сервисный аккаунт → API-ключ. Голоса: jane, alena, omazh, filipp…",
        enNote = "Natural Russian voices, paid in rubles (~1–2 ₽ per long reply). Key: Yandex Cloud → service account → API key. English voice: john."),
    ELEVENLABS("ElevenLabs", ElevenLabsTts.DEFAULT_VOICE, "eleven_flash_v2_5", true,
        "Очень живые голоса, есть бесплатный лимит (~10 000 символов в месяц). Голос — это voice_id из их библиотеки.",
        enNote = "Very lively voices in many languages, with a free tier (~10,000 characters a month). The voice is a voice_id from their library."),
    OPENAI("OpenAI", "nova", "gpt-4o-mini-tts", true,
        "Голос слушается указаний по эмоциям. Голоса: nova, shimmer, coral, sage… Если «мозг» тоже OpenAI, ключ можно не вводить.",
        enNote = "The voice follows emotion hints. Voices: nova, shimmer, coral, sage… If the “brain” is OpenAI too, you can skip the key."),
    CUSTOM("Свой сервер (OpenAI-совместимый)", "", "", false,
        "Например, свой сервер с Silero/XTTS, отвечающий на /v1/audio/speech. Только https.",
        "Own server (OpenAI-compatible)", "For example, your own Silero/XTTS server answering /v1/audio/speech. HTTPS only."),
    ;

    val title: String get() = tr(ruTitle, enTitle ?: ruTitle)
    val note: String get() = tr(ruNote, enNote)
}

/** How the character is drawn. */
enum class AvatarLook(private val ru: String, private val en: String) {
    RIG("Живой риг (мимика, уши, хвост)", "Live rig (expressions, ears, tail)"),
    SPRITES("Спрайты (рисованные кадры)", "Sprites (drawn frames)"),
    CLASSIC("Старый вид (рисунок кодом)", "Classic look (drawn by code)"),
    ;

    val title: String get() = tr(ru, en)
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
    val wakeNames: String = tr("Искра, Искорка", "Iskra, Spark"),
    /** «Экономия»: frame rate, display refresh, hearing; replaces the 0.8 frame-rate choice. */
    val economy: EconomyMode = EconomyMode.AUTO,
    /** Fewer big movements (no walking around); also on when Android's "remove animations" is set. */
    val calmMotion: Boolean = false,
    /** Chat microphone through the on-phone model only (the system recognizer may send audio to a server). */
    val voiceInputOffline: Boolean = false,
    /** Daily cloud limits (0 = none) and the owner's prices per million, for «Экономия». */
    val limitRequests: Int = 0,
    val limitTokens: Long = 0,
    val limitTtsChars: Long = 0,
    val priceInPerM: Double = 0.0,
    val priceOutPerM: Double = 0.0,
    val priceTtsPerM: Double = 0.0,
    val currency: String = "₽",
    val liveliness: Int = 1,
    val avatarLook: AvatarLook = AvatarLook.RIG,
    val economicalEar: Boolean = true,
    val weatherCity: String = "",
    val proactive: ProactiveFrequency = ProactiveFrequency.RARE,
    val quietFrom: Int = 23,
    val quietTo: Int = 8,
    val proactiveVoice: Boolean = false,
    /**
     * «Свобода модели»: rules the owner may give the model ([ModelRule]). All off by default — the model answers freely,
     * nothing replaces its answers and no prohibitions go into its instructions.
     */
    val crisisHelp: Boolean = false,
    val aiHonesty: Boolean = false,
    val noGuilt: Boolean = false,
    val specialists: Boolean = false,
    /** «Свои указания»: the owner's own instructions to the model, in their words (up to 1000 characters). */
    val ownWords: String = "",
) {
    val modelRules: Set<ModelRule> get() = buildSet {
        if (crisisHelp) add(ModelRule.CRISIS)
        if (aiHonesty) add(ModelRule.AI_HONESTY)
        if (noGuilt) add(ModelRule.NO_GUILT)
        if (specialists) add(ModelRule.SPECIALISTS)
    }
    val voiceOrDefault get() = ttsVoice.ifBlank { ttsEngine.defaultVoice }
    val ttsModelOrDefault get() = ttsModel.ifBlank { ttsEngine.defaultModel }
    val wakeNameList get() = wakeNames.split(',', ';').map { it.trim() }.filter { it.length >= 3 }
    val usageLimits get() = UsageLimits(limitRequests, limitTokens, limitTtsChars, priceInPerM, priceOutPerM, priceTtsPerM, currency.ifBlank { "₽" })
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
        wakeNames = prefs.getString("wakeNames", null) ?: tr("Искра, Искорка", "Iskra, Spark"),
        economy = prefs.getString("economy", null)?.let { runCatching { EconomyMode.valueOf(it) }.getOrNull() }
            ?: EconomyPolicy.fromLegacyFps(prefs.getString("fpsMode", null)),
        calmMotion = prefs.getBoolean("calmMotion", false),
        voiceInputOffline = prefs.getBoolean("voiceInputOffline", false),
        limitRequests = prefs.getInt("limitRequests", 0).coerceAtLeast(0),
        limitTokens = prefs.getLong("limitTokens", 0L).coerceAtLeast(0L),
        limitTtsChars = prefs.getLong("limitTtsChars", 0L).coerceAtLeast(0L),
        priceInPerM = prefs.getFloat("priceInPerM", 0f).toDouble(),
        priceOutPerM = prefs.getFloat("priceOutPerM", 0f).toDouble(),
        priceTtsPerM = prefs.getFloat("priceTtsPerM", 0f).toDouble(),
        currency = prefs.getString("currency", null) ?: "₽",
        liveliness = prefs.getInt("liveliness", 1).coerceIn(0, 2),
        avatarLook = runCatching { AvatarLook.valueOf(prefs.getString("avatarLook", null) ?: "RIG") }.getOrDefault(AvatarLook.RIG),
        economicalEar = prefs.getBoolean("economicalEar", true),
        weatherCity = prefs.getString("weatherCity", "") ?: "",
        proactive = runCatching { ProactiveFrequency.valueOf(prefs.getString("proactive", null) ?: "RARE") }.getOrDefault(ProactiveFrequency.RARE),
        quietFrom = prefs.getInt("quietFrom", 23).coerceIn(0, 23),
        quietTo = prefs.getInt("quietTo", 8).coerceIn(0, 23),
        proactiveVoice = prefs.getBoolean("proactiveVoice", false),
        crisisHelp = prefs.getBoolean("crisisHelp", false),
        aiHonesty = prefs.getBoolean("aiHonesty", false),
        noGuilt = prefs.getBoolean("noGuilt", false),
        specialists = prefs.getBoolean("specialists", false),
        ownWords = prefs.getString("ownWords", "") ?: "",
    )

    /** Read on every message by the agent. */
    fun ownWords(): String = prefs.getString("ownWords", "") ?: ""

    /** Read on every message by the agent, so a switch works as soon as it is saved. None by default. */
    fun modelRules(): Set<ModelRule> = buildSet {
        if (prefs.getBoolean("crisisHelp", false)) add(ModelRule.CRISIS)
        if (prefs.getBoolean("aiHonesty", false)) add(ModelRule.AI_HONESTY)
        if (prefs.getBoolean("noGuilt", false)) add(ModelRule.NO_GUILT)
        if (prefs.getBoolean("specialists", false)) add(ModelRule.SPECIALISTS)
    }

    /** Coordinates found for [AppSettings.weatherCity] (geocoded once, on «Найти»). */
    fun weatherPlace(): Place? {
        val name = prefs.getString("weatherPlaceName", null) ?: return null
        val lat = prefs.getString("weatherLat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("weatherLon", null)?.toDoubleOrNull() ?: return null
        return Place(name, lat, lon)
    }

    fun saveWeatherPlace(p: Place?) {
        prefs.edit().apply {
            if (p == null) { remove("weatherPlaceName"); remove("weatherLat"); remove("weatherLon") }
            else { putString("weatherPlaceName", p.name); putString("weatherLat", p.lat.toString()); putString("weatherLon", p.lon.toString()) }
        }.apply()
    }

    /**
     * Settings for a backup file. Keys live in [KeyVault], not here, so they cannot get in.
     * "overlay" is left out: it depends on a system permission on this particular phone.
     */
    fun exportMap(s: AppSettings = load()): Map<String, Any> = linkedMapOf(
        "preset" to s.preset.name, "baseUrl" to s.baseUrl, "model" to s.model,
        "tts" to s.ttsEnabled, "wander" to s.wander, "avatarSize" to s.avatarSizeDp,
        "ttsEngine" to s.ttsEngine.name, "ttsVoice" to s.ttsVoice, "ttsModel" to s.ttsModel, "ttsBaseUrl" to s.ttsBaseUrl,
        "alwaysListen" to s.alwaysListen, "listenScreenOff" to s.listenScreenOff, "followUp" to s.followUp,
        "wakeNames" to s.wakeNames, "economy" to s.economy.name, "calmMotion" to s.calmMotion,
        "voiceInputOffline" to s.voiceInputOffline, "liveliness" to s.liveliness,
        "limitRequests" to s.limitRequests, "limitTokens" to s.limitTokens, "limitTtsChars" to s.limitTtsChars,
        "priceInPerM" to s.priceInPerM, "priceOutPerM" to s.priceOutPerM, "priceTtsPerM" to s.priceTtsPerM, "currency" to s.currency,
        "avatarLook" to s.avatarLook.name, "economicalEar" to s.economicalEar,
        "weatherCity" to s.weatherCity, "proactive" to s.proactive.name, "quietFrom" to s.quietFrom, "quietTo" to s.quietTo,
        "proactiveVoice" to s.proactiveVoice, "crisisHelp" to s.crisisHelp, "aiHonesty" to s.aiHonesty, "noGuilt" to s.noGuilt,
        "specialists" to s.specialists, "ownWords" to s.ownWords,
    )

    /** Applies known, well-typed values from a backup over [base]; unknown names and wrong types are ignored. */
    fun fromMap(m: Map<String, Any>, base: AppSettings = load()): AppSettings {
        fun str(k: String) = m[k] as? String
        fun bool(k: String) = m[k] as? Boolean
        fun int(k: String) = (m[k] as? Int) ?: (m[k] as? Long)?.toInt()
        fun long(k: String) = (m[k] as? Long) ?: (m[k] as? Int)?.toLong()
        fun num(k: String) = (m[k] as? Number)?.toDouble()
        fun https(v: String?) = v?.takeIf { it.isEmpty() || it.startsWith("https://") }
        return base.copy(
            preset = str("preset")?.let { runCatching { ProviderPreset.valueOf(it) }.getOrNull() } ?: base.preset,
            baseUrl = https(str("baseUrl")) ?: base.baseUrl,
            model = str("model")?.take(200) ?: base.model,
            ttsEnabled = bool("tts") ?: base.ttsEnabled,
            wander = bool("wander") ?: base.wander,
            avatarSizeDp = int("avatarSize")?.coerceIn(72, 220) ?: base.avatarSizeDp,
            ttsEngine = str("ttsEngine")?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() } ?: base.ttsEngine,
            ttsVoice = str("ttsVoice")?.take(120) ?: base.ttsVoice,
            ttsModel = str("ttsModel")?.take(120) ?: base.ttsModel,
            ttsBaseUrl = https(str("ttsBaseUrl")) ?: base.ttsBaseUrl,
            alwaysListen = bool("alwaysListen") ?: base.alwaysListen,
            listenScreenOff = bool("listenScreenOff") ?: base.listenScreenOff,
            followUp = bool("followUp") ?: base.followUp,
            wakeNames = str("wakeNames")?.take(120) ?: base.wakeNames,
            economy = str("economy")?.let { runCatching { EconomyMode.valueOf(it) }.getOrNull() }
                ?: str("fpsMode")?.let { EconomyPolicy.fromLegacyFps(it) } ?: base.economy,
            calmMotion = bool("calmMotion") ?: base.calmMotion,
            voiceInputOffline = bool("voiceInputOffline") ?: base.voiceInputOffline,
            limitRequests = int("limitRequests")?.coerceAtLeast(0) ?: base.limitRequests,
            limitTokens = long("limitTokens")?.coerceAtLeast(0L) ?: base.limitTokens,
            limitTtsChars = long("limitTtsChars")?.coerceAtLeast(0L) ?: base.limitTtsChars,
            priceInPerM = num("priceInPerM")?.coerceAtLeast(0.0) ?: base.priceInPerM,
            priceOutPerM = num("priceOutPerM")?.coerceAtLeast(0.0) ?: base.priceOutPerM,
            priceTtsPerM = num("priceTtsPerM")?.coerceAtLeast(0.0) ?: base.priceTtsPerM,
            currency = str("currency")?.take(8) ?: base.currency,
            liveliness = int("liveliness")?.coerceIn(0, 2) ?: base.liveliness,
            avatarLook = str("avatarLook")?.let { runCatching { AvatarLook.valueOf(it) }.getOrNull() } ?: base.avatarLook,
            economicalEar = bool("economicalEar") ?: base.economicalEar,
            weatherCity = str("weatherCity")?.take(80) ?: base.weatherCity,
            proactive = str("proactive")?.let { runCatching { ProactiveFrequency.valueOf(it) }.getOrNull() } ?: base.proactive,
            quietFrom = int("quietFrom")?.coerceIn(0, 23) ?: base.quietFrom,
            quietTo = int("quietTo")?.coerceIn(0, 23) ?: base.quietTo,
            proactiveVoice = bool("proactiveVoice") ?: base.proactiveVoice,
            crisisHelp = bool("crisisHelp") ?: base.crisisHelp,
            aiHonesty = bool("aiHonesty") ?: base.aiHonesty,
            noGuilt = bool("noGuilt") ?: base.noGuilt,
            specialists = bool("specialists") ?: base.specialists,
            ownWords = str("ownWords")?.take(ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX) ?: base.ownWords,
        )
    }

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
            .putString("economy", s.economy.name)
            .putBoolean("calmMotion", s.calmMotion)
            .putBoolean("voiceInputOffline", s.voiceInputOffline)
            .putInt("limitRequests", s.limitRequests)
            .putLong("limitTokens", s.limitTokens)
            .putLong("limitTtsChars", s.limitTtsChars)
            .putFloat("priceInPerM", s.priceInPerM.toFloat())
            .putFloat("priceOutPerM", s.priceOutPerM.toFloat())
            .putFloat("priceTtsPerM", s.priceTtsPerM.toFloat())
            .putString("currency", s.currency.trim().take(8))
            .putInt("liveliness", s.liveliness)
            .putString("avatarLook", s.avatarLook.name)
            .putBoolean("economicalEar", s.economicalEar)
            .putString("weatherCity", s.weatherCity.trim())
            .putString("proactive", s.proactive.name)
            .putInt("quietFrom", s.quietFrom)
            .putInt("quietTo", s.quietTo)
            .putBoolean("proactiveVoice", s.proactiveVoice)
            .putBoolean("crisisHelp", s.crisisHelp)
            .putBoolean("aiHonesty", s.aiHonesty)
            .putBoolean("noGuilt", s.noGuilt)
            .putBoolean("specialists", s.specialists)
            .putString("ownWords", s.ownWords.take(ru.iskra.core.agent.PromptComposer.OWN_WORDS_MAX))
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
