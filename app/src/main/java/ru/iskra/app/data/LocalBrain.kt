package ru.iskra.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.iskra.app.diag.Diag
import ru.iskra.core.i18n.tr
import ru.iskra.core.llm.ChatMessage
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.LlmRequest
import ru.iskra.core.llm.local.ChatFormat
import ru.iskra.core.llm.local.GenStats
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.llm.local.NativeLlamaEngine
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * «Мозг на телефоне»: a small language model downloaded once and run by llama.cpp on the phone's processor.
 * Works without the internet and without any account; nothing leaves the phone. Slower and simpler than the
 * cloud, so it answers when there is no cloud brain or the cloud fails (no internet, free messages used up).
 */
object LocalBrain {
    class Model(
        val id: String,
        val title: String,
        val file: String,
        val url: String,
        val approxMb: Int,
        /** Phones with less memory get a warning (the model and the dialogue cache must fit next to the system). */
        val minRamGb: Int,
        val format: ChatFormat,
        private val ru: String,
        private val en: String,
        /** Known SHA-256, used when Hugging Face doesn't announce one (it normally does, and that one wins). */
        val sha256: String? = null,
    ) {
        val note: String get() = tr(ru, en)
    }

    /** Qwen models: Apache-2.0, official GGUF files on Hugging Face, good Russian and English for their size. */
    val models = listOf(
        Model("qwen2.5-0.5b", "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf", 491, 3,
            ChatFormat.CHATML, "Лёгкий: быстрый на любом телефоне, но отвечает просто.", "Light: fast on any phone, but answers simply.",
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"),
        Model("qwen2.5-1.5b", "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf", 1117, 6,
            ChatFormat.CHATML, "Обычный: заметно умнее, нужен телефон с 6 ГБ памяти.", "Standard: noticeably smarter, needs a phone with 6 GB of memory.",
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"),
        Model("qwen3-4b", "Qwen3 4B", "Qwen3-4B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", 2497, 10,
            ChatFormat.CHATML_NO_THINK, "Сильный: для флагманов с 10+ ГБ памяти, отвечает медленнее.", "Strong: for flagships with 10+ GB of memory, slower to answer.",
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5"),
    )

    /** A GGUF file put into the folder by hand (or by the CI check): any ChatML model works. */
    private val custom = Model("custom", tr("Свой файл", "Own file"), "custom.gguf", "", 0, 0, ChatFormat.CHATML, "", "")

    sealed interface State {
        data object None : State
        data class Downloading(val model: Model, val done: Long, val total: Long, val checking: Boolean = false) : State
        data class Ready(val model: Model, val bytes: Long) : State
        data class Failed(val message: String) : State
        data class Unsupported(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state
    private lateinit var app: Context
    private var download: Job? = null

    @Volatile private var libraryLoaded: Boolean? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val why = unsupportedReason()
        if (why != null) { _state.value = State.Unsupported(why); return }
        _state.value = installed() ?: State.None
    }

    /** The model already on the phone, if any. */
    private fun installed(): State.Ready? {
        val saved = prefs().getString(KEY_MODEL, null)?.let { id -> models.firstOrNull { it.id == id } }
        val ready = saved?.takeIf { fileOf(it).isFile } ?: custom.takeIf { fileOf(it).isFile }
        return ready?.let { State.Ready(it, fileOf(it).length()) }
    }

    /**
     * Where a model lives: downloads go to the app's own folder on the phone's storage; a hand-placed custom.gguf may
     * also be in the app's internal folder (that's where the emulator check puts it).
     */
    private fun fileOf(m: Model): File {
        val primary = File(dir(), m.file)
        if (m !== custom || primary.isFile) return primary
        return File(File(app.filesDir, "llm"), m.file).takeIf { it.isFile } ?: primary
    }

    fun enabled(): Boolean = prefs().getBoolean(KEY_ENABLED, true)
    fun setEnabled(on: Boolean) = prefs().edit().putBoolean(KEY_ENABLED, on).apply()

    fun totalRamGb(): Double {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1e9
    }

    /** The model for this phone: the strongest one its memory comfortably holds. */
    fun suggested(): Model { val ram = totalRamGb(); return models.lastOrNull { ram + 0.5 >= it.minRamGb } ?: models.first() }

    private fun unsupportedReason(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        when (abi) {
            "arm64-v8a" -> {
                // The library is built for ARMv8.2 (dot product, half precision): all phone cores since 2018.
                val cpu = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
                if (cpu.isNotEmpty() && (!cpu.contains("asimddp") || !cpu.contains("asimdhp")))
                    return tr("Процессор этого телефона слишком старый для модели (нужен ARMv8.2, телефоны примерно с 2018 года).",
                        "This phone's processor is too old for the model (it needs ARMv8.2, phones from about 2018 on).")
            }
            "x86_64" -> Unit
            else -> return tr("Модели на телефоне нужен 64-битный процессор.", "The on-phone model needs a 64-bit processor.")
        }
        if (totalRamGb() < 2.7) return tr("Для модели нужно хотя бы 3 ГБ оперативной памяти.", "The model needs at least 3 GB of RAM.")
        if (!loadLibrary()) return tr("В этой сборке нет модуля модели.", "This build has no model module.")
        return null
    }

    private fun loadLibrary(): Boolean = libraryLoaded ?: runCatching { System.loadLibrary("iskra_llama"); true }
        .getOrElse { Diag.w("local", tr("Модуль модели не загрузился", "The model module didn't load"), it); false }.also { libraryLoaded = it }

    private fun dir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "llm").also { it.mkdirs() }
    private fun prefs() = app.getSharedPreferences("local_brain", Context.MODE_PRIVATE)
    private fun freeBytes(): Long = runCatching { StatFs(dir().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun download(model: Model) {
        if (download?.isActive == true) return
        download = scope.launch {
            try {
                fetch(model)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = installed() ?: State.None
                throw e
            } catch (e: Exception) {
                Diag.w("local", tr("Модель не скачалась", "The model didn't download"), e)
                _state.value = State.Failed(tr("Не получилось скачать: ${e.message ?: e.javaClass.simpleName}. Попробуй ещё раз — загрузка продолжится с того же места.",
                    "Couldn't download: ${e.message ?: e.javaClass.simpleName}. Try again — it will continue where it stopped."))
            }
        }
    }

    fun cancelDownload() { download?.cancel(); download = null; _state.value = installed() ?: State.None }

    private suspend fun fetch(model: Model) {
        val dir = dir()
        val target = File(dir, model.file)
        val part = File(dir, model.file + ".part")
        if (freeBytes() < model.approxMb * 1_100_000L + 200_000_000L - part.length())
            throw IllegalStateException(tr("мало места: нужно около ${model.approxMb + 200} МБ", "not enough space: about ${model.approxMb + 200} MB needed"))
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        var have = part.length()
        _state.value = State.Downloading(model, have, model.approxMb * 1_000_000L)
        val request = Request.Builder().url(model.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        var expectedSha: String? = model.sha256
        var expectedSize: Long? = null
        client.newCall(request).execute().use { r ->
            // Hugging Face names the file's SHA-256 and size on the redirect it sends first.
            generateSequence(r) { it.priorResponse }.forEach { step ->
                step.header("X-Linked-Etag")?.trim('"', ' ')?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { expectedSha = it }
                step.header("X-Linked-Size")?.toLongOrNull()?.let { expectedSize = it }
            }
            if (r.code == 200 && have > 0) { part.delete(); have = 0 }        // the server ignored the range: start over
            if (r.code != 200 && r.code != 206) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException(tr("пустой ответ", "empty answer"))
            val total = expectedSize ?: (have + body.contentLength()).takeIf { body.contentLength() > 0 } ?: (model.approxMb * 1_000_000L)
            FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                val input = body.byteStream()
                var done = have
                var shown = 0L
                while (true) {
                    if (!currentCoroutineContextActive()) throw kotlinx.coroutines.CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - shown > 400) { shown = now; _state.value = State.Downloading(model, done, total) }
                }
            }
        }
        val size = part.length()
        expectedSize?.let { if (size != it) throw IllegalStateException(tr("файл скачался не полностью", "the file is incomplete")) }
        if (!isGguf(part)) { part.delete(); throw IllegalStateException(tr("это не файл модели", "this is not a model file")) }
        expectedSha?.let { sha ->
            _state.value = State.Downloading(model, size, size, checking = true)
            if (sha256(part) != sha) { part.delete(); throw IllegalStateException(tr("контрольная сумма не совпала — файл повреждён", "the checksum didn't match — the file is damaged")) }
        }
        File(dir, model.file).delete()
        if (!part.renameTo(target)) throw IllegalStateException(tr("не удалось сохранить файл", "couldn't save the file"))
        // Only one model is kept: switching frees the space of the previous one.
        models.filter { it.id != model.id }.forEach { File(dir, it.file).delete(); File(dir, it.file + ".part").delete() }
        prefs().edit().putString(KEY_MODEL, model.id).apply()
        unload()
        _state.value = State.Ready(model, target.length())
        Diag.i("local", tr("Модель на телефоне готова: ", "On-phone model ready: ") + model.id + " (${target.length() / 1_000_000} MB)")
    }

    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun isGguf(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { val b = ByteArray(4); it.readFully(b); String(b, Charsets.US_ASCII) == "GGUF" }
    }.getOrDefault(false)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { s -> val buf = ByteArray(1 shl 20); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete() {
        cancelDownload()
        unload()
        dir().listFiles()?.forEach { it.delete() }
        File(app.filesDir, "llm").listFiles()?.forEach { it.delete() }
        prefs().edit().remove(KEY_MODEL).apply()
        _state.value = State.None
        Diag.i("local", tr("Модель на телефоне удалена", "On-phone model deleted"))
    }

    // --- running it ---

    private val engineLock = Mutex()
    private var engine: NativeLlamaEngine? = null
    private var engineFor: String? = null
    private var idle: Job? = null
    private var provider: Pair<String, LocalLlmProvider>? = null

    /** The provider for the agent, or null if there is no model or the owner switched it off. */
    fun provider(): LlmProvider? {
        val st = state.value as? State.Ready ?: return null
        if (!enabled()) return null
        provider?.takeIf { it.first == st.model.id }?.let { return it.second }
        return LocalLlmProvider(::engine, st.model.format, "local:${st.model.id}", onStats = ::logStats).also { provider = st.model.id to it }
    }

    /** Every reply's speed goes to the journal: the numbers that tell which model suits this phone. */
    private fun logStats(g: GenStats) {
        Diag.i("local", tr("Ответ модели на телефоне: первые слова через ${g.firstPieceMs} мс, ${g.pieces} частей за ${g.totalMs} мс " +
            "(${"%.1f".format(java.util.Locale.ROOT, g.piecesPerSecond)} в секунду), запрос ${g.promptChars} символов",
            "On-phone model reply: first words after ${g.firstPieceMs} ms, ${g.pieces} pieces in ${g.totalMs} ms " +
                "(${"%.1f".format(java.util.Locale.ROOT, g.piecesPerSecond)} per second), prompt ${g.promptChars} characters"))
    }

    class SpeedCheck(val firstWordsMs: Long, val wordsPerSecond: Double, val slow: Boolean, val text: String)

    /**
     * «Проверить скорость»: a short fixed question, timed. Tells how soon the first words come and how fast the rest
     * follows, and suggests the light model when this one is too slow for this phone.
     */
    suspend fun speedCheck(): SpeedCheck? {
        val st = state.value as? State.Ready ?: return null
        val e = engine() ?: return null
        val prompt = st.model.format.render(LlmRequest(
            system = tr("Ты — Искра, кошкодевочка в телефоне. Отвечай тепло и коротко.", "You are Iskra, a cat-girl in the phone. Answer warmly and briefly."),
            messages = listOf(ChatMessage.User(tr("Расскажи о себе в двух предложениях.", "Tell me about yourself in two sentences."))),
        ))
        val t0 = System.nanoTime()
        var first = -1L
        val out = withContext(Dispatchers.IO) {
            e.generate(prompt, 64, 0.6f) { if (first < 0) first = (System.nanoTime() - t0) / 1_000_000; true }
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        val words = out.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
        val wps = if (words > 1 && total > first) (words - 1) * 1000.0 / (total - first) else 0.0
        val slow = wps < 1.5 || first > 15_000
        val verdict = when {
            wps >= 3 && first < 8_000 -> tr("быстро", "fast")
            !slow -> tr("нормально", "fine")
            else -> tr("медленно", "slow")
        }
        val lighter = models.indexOfFirst { it.id == st.model.id } > 0
        val text = tr("Первые слова через ${"%.1f".format(java.util.Locale.getDefault(), first / 1000.0)} с, дальше ≈ ${"%.1f".format(java.util.Locale.getDefault(), wps)} слова в секунду — $verdict.",
            "First words after ${"%.1f".format(java.util.Locale.getDefault(), first / 1000.0)} s, then ≈ ${"%.1f".format(java.util.Locale.getDefault(), wps)} words a second — $verdict.") +
            if (slow && lighter) tr(" Для этого телефона лучше модель полегче: удали эту и скачай «Лёгкий».", " A lighter model suits this phone better: delete this one and download “Light”.") else ""
        Diag.i("local", tr("Проверка скорости (${st.model.id}): ", "Speed check (${st.model.id}): ") + text)
        return SpeedCheck(first, wps, slow, text)
    }

    private suspend fun engine(): LlamaEngine? {
        val st = state.value as? State.Ready ?: return null
        return engineLock.withLock { loaded(st) }
    }

    private suspend fun loaded(st: State.Ready): LlamaEngine? {
        if (engine == null || engineFor != st.model.id) {
            engine?.close()
            val threads = threads()
            val ctx = if (totalRamGb() >= 7.5) 4096 else 2048
            val t0 = System.currentTimeMillis()
            engine = withContext(Dispatchers.IO) { NativeLlamaEngine.open(fileOf(st.model).path, ctx, threads) }
            engineFor = st.model.id
            if (engine == null) Diag.w("local", tr("Модель не загрузилась в память", "The model didn't load into memory"))
            else Diag.i("local", tr("Модель загружена за ", "Model loaded in ") + "${System.currentTimeMillis() - t0} ms: ${engine?.info}")
        }
        scheduleUnload()
        return engine
    }

    /**
     * One thread per performance core (2–4). Phones mix fast and slow cores (Redmi Note 14: 2 fast + 6 slow); threads on
     * slow cores make the fast ones wait. The kernel tells each core's capacity (1024 = the fastest) in /sys.
     */
    private fun threads(): Int {
        val caps = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpu_capacity").readText().trim().toInt() }.getOrNull()
        }
        val fast = if (caps.isNotEmpty()) caps.count { it >= caps.max() / 2 } else Runtime.getRuntime().availableProcessors() / 2
        return fast.coerceIn(2, 4)
    }

    /** Ten quiet minutes: give the memory back to the phone. The next message loads it again in a few seconds. */
    private fun scheduleUnload() {
        idle?.cancel()
        idle = scope.launch { delay(10 * 60_000L); unload() }
    }

    fun unload() {
        scope.launch {
            engineLock.withLock {
                engine?.close(); engine = null; engineFor = null
            }
        }
    }

    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
}
