package ru.iskra.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.iskra.app.IskraApp
import ru.iskra.app.diag.Diag
import ru.iskra.core.i18n.tr
import ru.iskra.core.llm.LlmException
import ru.iskra.core.llm.LoopbackCallback
import ru.iskra.core.llm.OpenRouterConnect
import ru.iskra.core.llm.Pkce

/**
 * «Подключить бесплатно»: sign in on openrouter.ai, get the owner's own key back (PKCE, nothing to copy),
 * pick a free model. Runs in the app process, so it survives the settings screen being recreated while
 * the browser is in front. The key goes straight into [KeyVault]; the journal never sees it.
 */
object FreeBrain {
    sealed interface State {
        data object Idle : State
        /** The browser is open; [manual] = the owner chose to paste the code shown on the page. */
        data class Waiting(val manual: Boolean) : State
        data object Finishing : State
        data class Done(val model: String) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connect = OpenRouterConnect()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var verifier = ""
    private var waiting: Job? = null
    private var callback: LoopbackCallback? = null

    /** A model id from the last known-good list, if OpenRouter's model list cannot be read right now. */
    private const val FALLBACK_MODEL = "deepseek/deepseek-chat-v3-0324:free"

    fun start(ctx: Context) {
        cancel()
        verifier = Pkce.verifier()
        val cb = runCatching { LoopbackCallback(::page) }.getOrNull()
        callback = cb
        open(ctx, connect.authUrl(Pkce.challenge(verifier), cb?.callbackUrl))
        _state.value = State.Waiting(manual = cb == null)
        Diag.i("brain", tr("Бесплатное подключение: открыт вход OpenRouter", "Free connect: OpenRouter sign-in opened"))
        if (cb != null) waiting = scope.launch {
            val code = cb.awaitCode()
            cb.close()
            if (code != null) finish(code)
            else if (_state.value is State.Waiting) _state.value = State.Failed(tr("OpenRouter не прислал код. Попробуй ещё раз.", "OpenRouter didn't send the code. Try again."))
        }
    }

    /** The page could not reach the phone: sign in again without a callback; OpenRouter shows a code to paste. */
    fun manual(ctx: Context) {
        cancelWaiting()
        verifier = Pkce.verifier()
        open(ctx, connect.authUrl(Pkce.challenge(verifier), null))
        _state.value = State.Waiting(manual = true)
    }

    fun paste(code: String) {
        if (code.isNotBlank()) scope.launch { finish(code.trim()) }
    }

    fun cancel() {
        cancelWaiting()
        _state.value = State.Idle
    }

    private fun cancelWaiting() {
        waiting?.cancel(); waiting = null
        callback?.close(); callback = null
    }

    private suspend fun finish(code: String) {
        _state.value = State.Finishing
        val app = IskraApp.instance
        try {
            val key = connect.exchange(code, verifier)
            val models = runCatching { connect.freeModels() }.getOrNull().orEmpty()
            val model = OpenRouterConnect.pick(models)
            use(model?.id ?: FALLBACK_MODEL, key)
            saveStandIns(OpenRouterConnect.standIns(models, model?.id ?: FALLBACK_MODEL))
            _state.value = State.Done(model?.name ?: FALLBACK_MODEL)
            Diag.i("brain", tr("Бесплатный мозг подключён: ", "Free brain connected: ") + (model?.id ?: FALLBACK_MODEL) +
                (if (model?.tools == false) tr(" (без действий с телефоном)", " (no phone actions)") else ""))
        } catch (e: LlmException) {
            Diag.w("brain", tr("Бесплатное подключение не удалось", "Free connect failed"), e)
            _state.value = State.Failed(if (e.kind == LlmException.Kind.NETWORK) tr("Нет связи с OpenRouter. Проверь интернет.", "No connection to OpenRouter. Check the internet.")
            else tr("OpenRouter не принял код (${e.kind}). Попробуй ещё раз.", "OpenRouter didn't accept the code (${e.kind}). Try again."))
        } finally {
            verifier = ""
            app.agent.resumeCloud()
        }
    }

    private fun use(model: String, key: String) {
        val app = IskraApp.instance
        val cur = app.settings.load()
        app.settings.save(cur.copy(preset = ProviderPreset.OPENROUTER, baseUrl = ProviderPreset.OPENROUTER.baseUrl, model = model))
        app.vault.save(key)
    }

    /** The free model disappeared: choose another one quietly (the key stays). */
    fun repick() = scope.launch {
        val s = IskraApp.instance.settings.load()
        if (s.preset != ProviderPreset.OPENROUTER || !s.model.endsWith(":free")) return@launch
        val models = runCatching { connect.freeModels().filter { it.id != s.model } }.getOrNull() ?: return@launch
        val m = OpenRouterConnect.pick(models) ?: return@launch
        IskraApp.instance.settings.save(s.copy(model = m.id))
        saveStandIns(OpenRouterConnect.standIns(models, m.id))
        IskraApp.instance.agent.resumeCloud()
        Diag.i("brain", tr("Бесплатная модель сменилась: ", "Free model changed: ") + m.id)
    }

    /**
     * Free models that quietly take over when the chosen one is busy (rate limits on free models are per model).
     * Kept apart from the settings: they change with OpenRouter's list, not with the owner's choice.
     */
    fun standIns(): List<String> = prefs().getString(STAND_INS, "").orEmpty().split(',').map { it.trim() }.filter { it.endsWith(":free") }

    private fun saveStandIns(ids: List<String>) {
        prefs().edit().putString(STAND_INS, ids.joinToString(",")).putLong(STAND_INS_AT, System.currentTimeMillis()).apply()
    }

    /** Owners who connected before stand-ins existed, or a list older than a week: refresh it in the background. */
    fun refreshStandInsIfStale() {
        val s = IskraApp.instance.settings.load()
        if (s.preset != ProviderPreset.OPENROUTER || !s.model.endsWith(":free")) return
        if (System.currentTimeMillis() - prefs().getLong(STAND_INS_AT, 0) < 7 * 86_400_000L) return
        scope.launch {
            val models = runCatching { connect.freeModels() }.getOrNull() ?: return@launch
            saveStandIns(OpenRouterConnect.standIns(models, s.model))
            Diag.i("brain", tr("Запасные бесплатные модели: ", "Free stand-in models: ") + standIns().joinToString())
        }
    }

    private fun prefs() = IskraApp.instance.getSharedPreferences("free_brain", Context.MODE_PRIVATE)
    private const val STAND_INS = "stand_ins"
    private const val STAND_INS_AT = "stand_ins_at"

    private fun open(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The page the browser shows after the sign-in; the button brings the owner back (deep link of this build). */
    private fun page(ok: Boolean): String {
        val back = IskraApp.instance.packageName + "://connected"
        val title = if (ok) tr("Готово ✓", "Done ✓") else tr("Не получилось", "That didn't work")
        val text = if (ok) tr("Искра получила ключ. Можно вернуться в приложение.", "Iskra got the key. You can go back to the app.")
        else tr("OpenRouter не прислал код. Вернись в Искру и попробуй ещё раз.", "OpenRouter didn't send the code. Go back to Iskra and try again.")
        val button = tr("Вернуться в Искру", "Back to Iskra")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Iskra</title></head><body style="font-family:sans-serif;text-align:center;padding:48px 16px;background:#1c1424;color:#f4eefa">
<h1>$title</h1><p>$text</p><p><a href="$back" style="display:inline-block;margin-top:16px;padding:14px 28px;background:#7a4fb0;color:#fff;
border-radius:28px;text-decoration:none;font-size:18px">$button</a></p></body></html>"""
    }
}
