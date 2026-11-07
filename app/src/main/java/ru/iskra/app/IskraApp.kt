package ru.iskra.app

import ru.iskra.core.i18n.tr
import ru.iskra.app.data.PrefsUsageStore
import ru.iskra.app.data.AppLanguage
import ru.iskra.core.usage.MeteredProvider
import ru.iskra.core.usage.UsageMeter
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.iskra.app.data.IskraDb
import ru.iskra.app.diag.Diag
import ru.iskra.app.diag.DiagCollector
import ru.iskra.app.data.KeyVault
import ru.iskra.app.data.PrefsStateStore
import ru.iskra.app.data.ProviderPreset
import ru.iskra.app.data.SettingsRepo
import ru.iskra.app.data.SqliteConversationStore
import ru.iskra.app.data.SqliteMemoryStore
import ru.iskra.app.data.AutoBackup
import ru.iskra.app.data.SqliteNoteStore
import ru.iskra.app.data.SqliteReminderStore
import ru.iskra.app.skills.AndroidReminderScheduler
import ru.iskra.core.skills.AddNoteTool
import ru.iskra.core.skills.CancelRemindersTool
import ru.iskra.core.skills.DeleteNotesTool
import ru.iskra.core.skills.ListNotesTool
import ru.iskra.core.skills.ListRemindersTool
import ru.iskra.core.skills.PhraseBank
import ru.iskra.core.skills.SetReminderTool
import ru.iskra.core.skills.WeatherClient
import ru.iskra.core.skills.WeatherTool
import ru.iskra.core.skills.upcomingReminders
import ru.iskra.core.brain.CurrencyClient
import ru.iskra.core.brain.CurrencyTool
import ru.iskra.core.brain.WikipediaClient
import ru.iskra.core.brain.WikipediaTool
import ru.iskra.app.system.CompanionAccessibilityService
import ru.iskra.app.tools.AndroidTools
import ru.iskra.app.ui.ChatController
import ru.iskra.app.voice.Speaker
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.llm.AnthropicProvider
import ru.iskra.core.llm.FallbackProvider
import ru.iskra.app.data.FreeBrain
import ru.iskra.app.data.LocalBrain
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.OpenAiCompatibleProvider
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.persona.Personas
import ru.iskra.core.tools.SafetyPolicy
import ru.iskra.core.tools.ToolRegistry

/** Manual dependency graph — small enough that a DI framework would only add build complexity. */
class IskraApp : Application() {
    lateinit var settings: SettingsRepo; private set
    lateinit var vault: KeyVault; private set
    /** Separate key for the voice service (may be a different vendor than the "brain"). */
    lateinit var ttsVault: KeyVault; private set
    /** One of the app's own screens is in front (not just the overlay): the character lets taps through. */
    val ownScreenInFront = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var resumedScreens = 0

    /** How many chat screens are in the foreground (they show confirmation dialogs). */
    val chatScreensResumed = java.util.concurrent.atomic.AtomicInteger(0)
    lateinit var db: IskraDb; private set
    lateinit var memory: MemoryManager; private set
    lateinit var conversation: SqliteConversationStore; private set
    lateinit var stateStore: PrefsStateStore; private set
    lateinit var confirm: ConfirmBridge; private set
    lateinit var agent: CompanionAgent; private set
    lateinit var chat: ChatController; private set
    lateinit var speaker: Speaker; private set
    /** Today's cloud usage and the owner's daily limits («Экономия»). */
    lateinit var usage: UsageMeter; private set
    lateinit var notes: SqliteNoteStore; private set
    lateinit var reminders: SqliteReminderStore; private set
    /** Iskra's own diary (0.15.0). */
    lateinit var diary: ru.iskra.app.data.SqliteDiaryStore; private set
    lateinit var reminderScheduler: AndroidReminderScheduler; private set
    val phrases = PhraseBank()
    /** Last message or touch from the owner (for proactive lines). */
    @Volatile var lastContactMs = System.currentTimeMillis()

    /** The phone needs memory back: the on-phone model (hundreds of MB) goes first; it reloads on the next message. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) LocalBrain.unload()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.apply(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Before anything produces text: Russian or English (the phone's language or the owner's choice).
        AppLanguage.apply(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) {
                if (a is ru.iskra.app.system.ToggleActivity) return
                resumedScreens++; ownScreenInFront.value = true
            }
            override fun onActivityPaused(a: android.app.Activity) {
                if (a is ru.iskra.app.system.ToggleActivity) return
                resumedScreens = maxOf(0, resumedScreens - 1); ownScreenInFront.value = resumedScreens > 0
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: android.app.Activity) = Unit
            override fun onActivityStopped(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
        settings = SettingsRepo(this)
        usage = UsageMeter(PrefsUsageStore(this), limits = { settings.load().usageLimits }, today = { java.time.LocalDate.now().toString() })
        vault = KeyVault(this)
        ttsVault = KeyVault(this, "tts_key")
        Diag.init(this) { DiagCollector.knownSecrets(this) }
        Diag.i("app", tr("Запуск ${BuildConfig.VERSION_NAME}, сборка ${DiagCollector.build()}", "Start ${BuildConfig.VERSION_NAME}, build ${DiagCollector.build()}"))
        // After an update HyperOS may not restart the accessibility service: record its state a bit
        // after start (the system needs a moment to bind it) to tell "switched off" from "not running".
        android.os.Handler(mainLooper).postDelayed({
            Diag.i("access", tr("Через 5 с после запуска: ${CompanionAccessibilityService.describe(this)}", "5 s after start: ${CompanionAccessibilityService.describe(this)}"))
            // Counts only: lets an update check show whether memory and chat survived.
            Diag.i("app", tr("Данные: память ${runCatching { memory.all().size }.getOrDefault(-1)}, " +
                "реплик в переписке ${runCatching { conversation.count() }.getOrDefault(-1)}", "Data: memory ${runCatching { memory.all().size }.getOrDefault(-1)}, chat turns ${runCatching { conversation.count() }.getOrDefault(-1)}"))
        }, 5000)
        db = IskraDb(this)
        // A deleted memory may still be in the phone model's saved dialogue cache: that goes too.
        memory = MemoryManager(SqliteMemoryStore(db), onForget = { LocalBrain.forgetSession() })
        conversation = SqliteConversationStore(db)
        stateStore = PrefsStateStore(this)
        confirm = ConfirmBridge()
        notes = SqliteNoteStore(db)
        reminders = SqliteReminderStore(db)
        diary = ru.iskra.app.data.SqliteDiaryStore(db)
        reminderScheduler = AndroidReminderScheduler(this)
        val weather = WeatherClient()
        val skills = listOf(
            AddNoteTool(notes) { System.currentTimeMillis() }, ListNotesTool(notes), DeleteNotesTool(notes),
            SetReminderTool(reminders, reminderScheduler, { System.currentTimeMillis() }),
            ListRemindersTool(reminders, { System.currentTimeMillis() }),
            CancelRemindersTool(reminders, reminderScheduler),
            WeatherTool(weather, homePlace = { settings.weatherPlace() }),
            WikipediaTool(WikipediaClient()), CurrencyTool(CurrencyClient()),
        )
        LocalBrain.init(this)
        val disabledTools = emptySet<String>()
        agent = CompanionAgent(
            persona = Personas.ISKRA,
            memory = memory,
            conversation = conversation,
            stateStore = stateStore,
            tools = ToolRegistry(AndroidTools.all(this) + skills),
            policy = SafetyPolicy(gate = confirm, disabled = { disabledTools }),
            providerFactory = ::currentProvider,
            localFactory = { LocalBrain.provider() },
            deviceContext = {
                tr("Служба управления телефоном (спец. возможности): ", "Phone control service (accessibility): ") +
                    if (CompanionAccessibilityService.isConnected()) tr("включена.", "on.") else tr("выключена — системная навигация недоступна.", "off — system navigation unavailable.")
            },
            upcoming = { upcomingReminders(reminders, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) },
            rules = { settings.modelRules() },
            ownWords = { settings.ownWords() },
            diary = diary,
        )
        speaker = Speaker(
            this,
            usage = usage,
            settings = { settings.load() },
            ttsKey = { ttsVault.load() },
            llmKeyIfOpenAi = { if (settings.load().preset == ProviderPreset.OPENAI) vault.load() else null },
        )
        chat = ChatController(this)
        AutoBackup.schedule(this, onlyIfMissing = true)
        ru.iskra.app.data.NightMemory.schedule(this)
        FreeBrain.refreshStandInsIfStale()
    }

    /** Built per request so settings/key changes apply immediately. */
    /** A cloud «brain» is set up (it also recaps long conversations itself). */
    fun hasCloud(): Boolean = runCatching { currentProvider() != null }.getOrDefault(false)

    private fun currentProvider(): LlmProvider? {
        val s = settings.load()
        if (s.preset == ProviderPreset.OFFLINE) return null
        val key = vault.load() ?: return null
        val model = s.model.ifBlank { s.preset.defaultModel }
        if (model.isBlank()) return null
        val base = s.baseUrl.ifBlank { s.preset.baseUrl }
        if (!base.startsWith("https://")) return null
        fun openAi(m: String) = OpenAiCompatibleProvider(
            baseUrl = base, apiKey = key, model = m,
            extraHeaders = if (s.preset == ProviderPreset.OPENROUTER) mapOf("X-Title" to "Iskra") else emptyMap(),
            streamUsage = s.preset == ProviderPreset.OPENAI || s.preset == ProviderPreset.DEEPSEEK || s.preset == ProviderPreset.OPENROUTER,
        )
        val primary = if (s.preset.anthropic) AnthropicProvider(apiKey = key, model = model, baseUrl = base) else openAi(model)
        // A busy free model hands the message to another free one; paid ones get one quiet retry after a hiccup.
        val standIns = if (s.preset == ProviderPreset.OPENROUTER && model.endsWith(":free")) FreeBrain.standIns().filter { it != model } else emptyList()
        val provider = FallbackProvider(listOf(primary) + standIns.map(::openAi))
        return MeteredProvider(provider, usage) // counts tokens, enforces the owner's daily limit
    }

    /** "Forget everything": memory, dialogue, mood, keys, diagnostics journal. Settings stay. */
    fun wipeAllData(onComplete: () -> Unit = {}) {
        chat.clearData(erase = {
            speaker.withSpeechStopped {
                withContext(Dispatchers.IO) {
                    memory.deleteAll()
                    conversation.clear()
                    stateStore.reset()
                    vault.clear()
                    ttsVault.clear()
                    speaker.clearCache()
                    Diag.clearCrashes()
                    notes.deleteAll()
                    diary.clear()
                    reminders.pending().forEach { reminderScheduler.cancel(it.id) }
                    reminders.deleteAll()
                    Diag.clear()
                    Diag.i("app", tr("Все данные стёрты владельцем", "All data erased by the owner"))
                }
            }
        }, onComplete = onComplete)
    }

    companion object {
        lateinit var instance: IskraApp; private set
    }
}
