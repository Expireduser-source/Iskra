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
import ru.iskra.core.llm.LlmProvider
import ru.iskra.core.llm.local.ChatFormat
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
    ) {
        val note: String get() = tr(ru, en)
    }

    /** Qwen models: Apache-2.0, official GGUF files on Hugging Face, good Russian and English for their size. */
    val models = listOf(
        Model("qwen2.5-0.5b", "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf", 400, 3,
            ChatFormat.CHATML, "Лёгкий: быстрый на любом телефоне, но отвечает просто.", "Light: fast on any phone, but answers simply."),
        Model("qwen2.5-1.5b", "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf", 1100, 6,
            ChatFormat.CHATML, "Обычный: заметно умнее, нужен телефон с 6 ГБ памяти.", "Standard: noticeably smarter, needs a phone with 6 GB of memory."),
        Model("qwen3-4b", "Qwen3 4B", "Qwen3-4B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", 2500, 10,
            ChatFormat.CHATML_NO_THINK, "Сильный: для флагманов с 10+ ГБ памяти, отвечает медленнее.", "Strong: for flagships with 10+ GB of memory, slower to answer."),
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
        val dir = dir()
        val saved = prefs().getString(KEY_MODEL, null)?.let { id -> models.firstOrNull { it.id == id } }
        val ready = saved?.takeIf { File(dir, it.file).isFile } ?: custom.takeIf { File(dir, it.file).isFile }
        return ready?.let { State.Ready(it, File(dir, it.file).length()) }
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
        var expectedSha: String? = null
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
        return LocalLlmProvider(::engine, st.model.format, "local:${st.model.id}").also { provider = st.model.id to it }
    }

    private suspend fun engine(): LlamaEngine? {
        val st = state.value as? State.Ready ?: return null
        return engineLock.withLock { loaded(st) }
    }

    private suspend fun loaded(st: State.Ready): LlamaEngine? {
        if (engine == null || engineFor != st.model.id) {
            engine?.close()
            val cores = Runtime.getRuntime().availableProcessors()
            val threads = (cores / 2).coerceIn(2, 4)
            val ctx = if (totalRamGb() >= 7.5) 4096 else 2048
            val t0 = System.currentTimeMillis()
            engine = withContext(Dispatchers.IO) { NativeLlamaEngine.open(File(dir(), st.model.file).path, ctx, threads) }
            engineFor = st.model.id
            if (engine == null) Diag.w("local", tr("Модель не загрузилась в память", "The model didn't load into memory"))
            else Diag.i("local", tr("Модель загружена за ", "Model loaded in ") + "${System.currentTimeMillis() - t0} ms: ${engine?.info}")
        }
        scheduleUnload()
        return engine
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
