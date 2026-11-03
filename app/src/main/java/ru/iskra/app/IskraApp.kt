package ru.iskra.app

import android.app.Application
import ru.iskra.app.data.IskraDb
import ru.iskra.app.diag.Diag
import ru.iskra.app.diag.DiagCollector
import ru.iskra.app.data.KeyVault
import ru.iskra.app.data.PrefsStateStore
import ru.iskra.app.data.ProviderPreset
import ru.iskra.app.data.SettingsRepo
import ru.iskra.app.data.SqliteConversationStore
import ru.iskra.app.data.SqliteMemoryStore
import ru.iskra.app.system.CompanionAccessibilityService
import ru.iskra.app.tools.AndroidTools
import ru.iskra.app.ui.ChatController
import ru.iskra.app.voice.Speaker
import ru.iskra.core.agent.CompanionAgent
import ru.iskra.core.llm.AnthropicProvider
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepo(this)
        vault = KeyVault(this)
        ttsVault = KeyVault(this, "tts_key")
        Diag.init(this) { DiagCollector.knownSecrets(this) }
        Diag.i("app", "Запуск ${BuildConfig.VERSION_NAME}, сборка ${DiagCollector.build()}")
        // After an update HyperOS may not restart the accessibility service: record its state a bit
        // after start (the system needs a moment to bind it) to tell "switched off" from "not running".
        android.os.Handler(mainLooper).postDelayed({
            Diag.i("access", "Через 5 с после запуска: ${CompanionAccessibilityService.describe(this)}")
            // Counts only: lets an update check show whether memory and chat survived.
            Diag.i("app", "Данные: память ${runCatching { memory.all().size }.getOrDefault(-1)}, " +
                "реплик в переписке ${runCatching { conversation.count() }.getOrDefault(-1)}")
        }, 5000)
        db = IskraDb(this)
        memory = MemoryManager(SqliteMemoryStore(db))
        conversation = SqliteConversationStore(db)
        stateStore = PrefsStateStore(this)
        confirm = ConfirmBridge()
        val disabledTools = emptySet<String>()
        agent = CompanionAgent(
            persona = Personas.ISKRA,
            memory = memory,
            conversation = conversation,
            stateStore = stateStore,
            tools = ToolRegistry(AndroidTools.all(this)),
            policy = SafetyPolicy(gate = confirm, disabled = { disabledTools }),
            providerFactory = ::currentProvider,
            deviceContext = {
                "Служба управления телефоном (спец. возможности): " +
                    if (CompanionAccessibilityService.isConnected()) "включена." else "выключена — системная навигация недоступна."
            },
        )
        speaker = Speaker(
            this,
            settings = { settings.load() },
            ttsKey = { ttsVault.load() },
            llmKeyIfOpenAi = { if (settings.load().preset == ProviderPreset.OPENAI) vault.load() else null },
        )
        chat = ChatController(this)
    }

    /** Built per request so settings/key changes apply immediately. */
    private fun currentProvider(): LlmProvider? {
        val s = settings.load()
        if (s.preset == ProviderPreset.OFFLINE) return null
        val key = vault.load() ?: return null
        val model = s.model.ifBlank { s.preset.defaultModel }
        if (model.isBlank()) return null
        val base = s.baseUrl.ifBlank { s.preset.baseUrl }
        if (!base.startsWith("https://")) return null
        return if (s.preset.anthropic) AnthropicProvider(apiKey = key, model = model, baseUrl = base)
        else OpenAiCompatibleProvider(
            baseUrl = base, apiKey = key, model = model,
            extraHeaders = if (s.preset == ProviderPreset.OPENROUTER) mapOf("X-Title" to "Iskra") else emptyMap(),
        )
    }

    /** "Forget everything": memory, dialogue, mood, keys, diagnostics journal. Settings stay. */
    fun wipeAllData() {
        memory.deleteAll()
        conversation.clear()
        stateStore.reset()
        vault.clear()
        ttsVault.clear()
        speaker.clearCache()
        Diag.clear()
        chat.reload()
        Diag.i("app", "Все данные стёрты владельцем")
    }

    companion object {
        lateinit var instance: IskraApp; private set
    }
}
