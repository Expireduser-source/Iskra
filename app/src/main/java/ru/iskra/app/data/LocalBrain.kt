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
import ru.iskra.core.llm.local.Gguf
import ru.iskra.core.llm.local.detect
import ru.iskra.core.llm.local.LlamaEngine
import ru.iskra.core.llm.local.LocalLlmProvider
import ru.iskra.core.llm.local.NativeLlamaEngine
import ru.iskra.core.llm.local.ThreadTuning
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

    /**
     * Chosen on real conversations in Russian and English (CI evaluation on ARM built like the phone library, 2 threads,
     * docs/reports/2026-11-05-0.12.3.md and the 0.12.8 report). All Q4_0 from Google's quantization-aware training, which
     * runs through llama.cpp's fastest ARM path; official ggml-org GGUF files. Gemma 4 E2B (2026, made for phones) answers
     * in 1.5 s / 4.2 s there against 2.4 s / 8.7 s for Gemma 3 4B, briefly and in natural Russian; E4B writes the best and
     * the slowest. Per-layer embeddings make their files big, but they are read from the file only as needed.
     */
    val models = listOf(
        Model("gemma-3-1b-qat", "Gemma 3 1B", "gemma-3-1b-it-qat-Q4_0.gguf",
            "https://huggingface.co/ggml-org/gemma-3-1b-it-qat-GGUF/resolve/main/gemma-3-1b-it-qat-Q4_0.gguf", 720, 4,
            ChatFormat.GEMMA, "Лёгкая: отвечает быстро на любом телефоне, но проще в разговоре и иногда путается.",
            "Light: answers quickly on any phone, but is simpler and sometimes gets confused."),
        Model("gemma-4-e2b", "Gemma 4 E2B", "gemma-4-E2B-it-Q4_0.gguf",
            "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf", 2842, 6,
            ChatFormat.GEMMA4, "Умная: новая модель Google для телефонов — много знает, хорошо говорит по-русски и отвечает коротко. Нужно 6 ГБ памяти.",
            "Smart: Google's new model made for phones — knows a lot, speaks Russian well and answers briefly. Needs 6 GB of memory."),
        Model("gemma-4-e4b", "Gemma 4 E4B", "gemma-4-E4B-it-Q4_0.gguf",
            "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf", 4591, 10,
            ChatFormat.GEMMA4, "Самая умная: лучшие ответы, но вдвое медленнее умной и весит 4,6 ГБ. Для мощных телефонов от 10 ГБ памяти.",
            "Smartest: the best answers, but half as fast as the smart one and 4.6 GB. For powerful phones with 10 GB of memory or more."),
    )

    /** Earlier catalogs: still work if downloaded; the card suggests the better one. */
    private val legacy = listOf(
        Model("gemma-3-1b", "Gemma 3 1B (Q4_K_M)", "gemma-3-1b-it-Q4_K_M.gguf", "", 806, 4, ChatFormat.GEMMA, "", ""),
        Model("gemma-3-4b-qat", "Gemma 3 4B", "gemma-3-4b-it-qat-Q4_0.gguf", "", 2526, 6, ChatFormat.GEMMA, "", ""),
        Model("qwen3.5-2b", "Qwen3.5 2B", "Qwen3.5-2B-Q4_K_M.gguf", "", 1280, 6, ChatFormat.CHATML_NO_THINK, "", ""),
        Model("qwen3.5-4b", "Qwen3.5 4B", "Qwen3.5-4B-Q4_K_M.gguf", "", 2740, 10, ChatFormat.CHATML_NO_THINK, "", ""),
        Model("qwen2.5-0.5b", "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf", "", 491, 3, ChatFormat.CHATML, "", ""),
        Model("qwen2.5-1.5b", "Qwen2.5 1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf", "", 1117, 6, ChatFormat.CHATML, "", ""),
        Model("qwen3-4b", "Qwen3 4B", "Qwen3-4B-Q4_K_M.gguf", "", 2497, 10, ChatFormat.CHATML_NO_THINK, "", ""),
    )

    fun isLegacy(m: Model) = legacy.any { it.id == m.id }

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
        val saved = prefs().getString(KEY_MODEL, null)?.let { id -> (models + legacy).firstOrNull { it.id == id } }
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

    /**
     * The model for this phone: the strongest one its memory comfortably holds — but with only two fast cores (Redmi
     * Note 14 and most mid-range phones) the light one, which writes about three times faster there.
     */
    fun suggested(): Model {
        val ram = totalRamGb()
        if (fewFastCores()) return models.first()
        return models.lastOrNull { ram + 0.5 >= it.minRamGb } ?: models.first()
    }

    /** Two fast cores or fewer: bigger models write slowly here (2–3 words a second for the 4B, estimated from the ARM check). */
    fun fewFastCores(): Boolean = runCatching { performanceCores() <= 2 }.getOrDefault(false)

    /** For a model from an earlier catalog: why the suggested one is better. */
    fun whyBetter(old: Model): String = when {
        old.id == "gemma-3-1b" -> tr(
            "та же модель, но обученная Google для такого сжатия: быстрее и меньше",
            "the same model, trained by Google for this compression: faster and smaller")
        old.id == "gemma-3-4b-qat" -> tr(
            "новое поколение, сделанное для телефонов: отвечает вдвое быстрее и больше знает",
            "the new generation made for phones: answers twice as fast and knows more")
        else -> tr("быстрее отвечает и естественнее говорит по-русски", "answers faster and speaks Russian more naturally")
    }

    /**
     * What replaces a model from an earlier catalog: one of the same class (light for light, smart for smart) — not this
     * phone's pick, which on two fast cores is the light one and would be a step down for an owner of a smart model.
     */
    fun replacement(old: Model): Model = when (old.id) {
        "gemma-3-1b", "qwen2.5-0.5b", "qwen2.5-1.5b" -> models.first()
        else -> models.first { it.id == "gemma-4-e2b" }
    }

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
        (models + legacy).filter { it.id != model.id }.forEach { File(dir, it.file).delete(); File(dir, it.file + ".part").delete() }
        prefs().edit().putString(KEY_MODEL, model.id).apply()
        unload()
        _state.value = State.Ready(model, target.length())
        Diag.i("local", tr("Модель на телефоне готова: ", "On-phone model ready: ") + model.id + " (${target.length() / 1_000_000} MB)")
        // Right away, while the owner looks at the card: which threads suit this phone (seconds, once per model).
        scope.launch { runCatching { tune() }.onFailure { Diag.w("local", "tune: ${it.message}") } }
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
        forgetSession()
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
    private var engineSession: File? = null
    private var idle: Job? = null
    private var provider: Pair<String, LocalLlmProvider>? = null

    /** The provider for the agent, or null if there is no model or the owner switched it off. */
    fun provider(): LlmProvider? {
        val st = state.value as? State.Ready ?: return null
        if (!enabled()) return null
        provider?.takeIf { it.first == st.model.id }?.let { return it.second }
        return LocalLlmProvider(::engine, formatOf(st.model), "local:${st.model.id}", onStats = ::logStats).also { provider = st.model.id to it }
    }

    private val formats = HashMap<String, ChatFormat>()

    /** The dialogue format from the model file's own chat template (a hand-placed file can be any model), else the catalog's. */
    private fun formatOf(m: Model): ChatFormat = synchronized(formats) {
        val f = fileOf(m)
        formats.getOrPut(f.path + ":" + f.length()) { ChatFormat.detect(Gguf.chatTemplate(f), m.format) }
    }

    /** Every reply's speed goes to the journal: the numbers that tell which model suits this phone. */
    private fun logStats(g: GenStats) {
        Diag.i("local", tr("Ответ модели на телефоне: первые слова через ${g.firstPieceMs} мс, ${g.pieces} частей за ${g.totalMs} мс " +
            "(${"%.1f".format(java.util.Locale.ROOT, g.piecesPerSecond)} в секунду), запрос ${g.promptChars} символов, " +
            "прочитано заново ${g.newTokens} из ${g.promptTokens} токенов",
            "On-phone model reply: first words after ${g.firstPieceMs} ms, ${g.pieces} pieces in ${g.totalMs} ms " +
                "(${"%.1f".format(java.util.Locale.ROOT, g.piecesPerSecond)} per second), prompt ${g.promptChars} characters, " +
                "read ${g.newTokens} new of ${g.promptTokens} tokens"))
    }

    class SpeedCheck(val firstWordsMs: Long, val wordsPerSecond: Double, val slow: Boolean, val text: String)

    /**
     * «Проверить скорость»: a short fixed question, timed. Tells how soon the first words come and how fast the rest
     * follows, and suggests the light model when this one is too slow for this phone.
     */
    suspend fun speedCheck(): SpeedCheck? {
        val st = state.value as? State.Ready ?: return null
        val tunedNow = runCatching { tune() }.getOrNull()
        val e = engine() ?: return null
        val prompt = formatOf(st.model).render(LlmRequest(
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
            (tunedNow?.let { " " + tunedText(it.gen, it.batch, it.speed.read, it.speed.write) } ?: "") +
            if (slow && lighter) tr(" Для этого телефона лучше модель полегче: удали эту и скачай «Лёгкий».", " A lighter model suits this phone better: delete this one and download “Light”.") else ""
        Diag.i("local", tr("Проверка скорости (${st.model.id}): ", "Speed check (${st.model.id}): ") + text)
        return SpeedCheck(first, wps, slow, text)
    }

    private suspend fun engine(): LlamaEngine? {
        val st = state.value as? State.Ready ?: return null
        return engineLock.withLock { loaded(st) }
    }

    /** Threads measured on this phone for a model, and the speed they gave (tokens per second). */
    private class Tuned(val gen: Int, val batch: Int, val read: Double, val write: Double)

    private fun tuned(m: Model): Tuned? = prefs().getString("tuned:${m.id}", null)?.split(',')?.let { p ->
        runCatching { Tuned(p[0].toInt(), p[1].toInt(), p[2].toDouble(), p[3].toDouble()) }.getOrNull()
    }

    private val _tuning = MutableStateFlow(false)
    /** True while the threads are being measured (the card says so). */
    val tuning: StateFlow<Boolean> = _tuning

    /**
     * Measures which threads suit this phone and this model (reading and writing separately; seconds, a few tens for a
     * 4B model on a mid-range phone), remembers and applies them. Phones mix fast and slow cores, so this is measured,
     * not guessed. Empties the model's dialogue cache.
     */
    suspend fun tune(): ThreadTuning.Result? {
        val st = state.value as? State.Ready ?: return null
        _tuning.value = true
        try {
            return engineLock.withLock {
                val e = loaded(st) ?: return@withLock null
                val all = Runtime.getRuntime().availableProcessors()
                val r = withContext(Dispatchers.IO) { ThreadTuning.calibrate(e, performanceCores().coerceIn(1, all), all) } ?: return@withLock null
                prefs().edit().putString("tuned:${st.model.id}", "${r.gen},${r.batch},${r.speed.read},${r.speed.write}").apply()
                Diag.i("local", tr("Подбор потоков (${st.model.id}): ", "Thread tuning (${st.model.id}): ") +
                    r.tried.entries.joinToString("; ") { (t, v) ->
                        "$t: " + tr("чтение %.0f, ответ %.1f ток/с", "reading %.0f, writing %.1f tok/s").format(java.util.Locale.ROOT, v.read, v.write)
                    } + tr(" → для ответа ${r.gen}, для чтения ${r.batch}", " → ${r.gen} for writing, ${r.batch} for reading"))
                r
            }
        } finally {
            _tuning.value = false
        }
    }

    private fun tunedText(gen: Int, batch: Int, read: Double, write: Double): String = tr(
        "На этом телефоне модель читает ≈ ${read.toInt()} и пишет ≈ ${"%.1f".format(java.util.Locale.getDefault(), write)} токена в секунду (потоков: $gen для ответа, $batch для чтения).",
        "On this phone the model reads ≈ ${read.toInt()} and writes ≈ ${"%.1f".format(java.util.Locale.getDefault(), write)} tokens a second (threads: $gen for writing, $batch for reading).")

    /** What was measured for the ready model, for the card; null before the first measurement. */
    fun tunedSpeed(): String? {
        val st = state.value as? State.Ready ?: return null
        return tuned(st.model)?.let { tunedText(it.gen, it.batch, it.read, it.write) }
    }

    private suspend fun loaded(st: State.Ready): LlamaEngine? {
        if (engine == null || engineFor != st.model.id) {
            closeEngine(save = true)
            val threads = threads()
            val ctx = if (totalRamGb() >= 7.5) 4096 else 2048
            val t0 = System.currentTimeMillis()
            val session = sessionFile(st.model, ctx)
            engine = withContext(Dispatchers.IO) { NativeLlamaEngine.open(fileOf(st.model).path, ctx, threads) }
            engineFor = st.model.id
            engineSession = session
            val e = engine
            if (e == null) Diag.w("local", tr("Модель не загрузилась в память", "The model didn't load into memory"))
            else {
                tuned(st.model)?.let { e.setThreads(it.gen, it.batch) }
                // Where the last conversation stopped: the next message doesn't read the whole dialogue again.
                val restored = if (session.exists()) withContext(Dispatchers.IO) { e.loadSession(session.path) } else 0
                Diag.i("local", tr("Модель загружена за ", "Model loaded in ") + "${System.currentTimeMillis() - t0} ms: ${e.info}" +
                    if (restored > 0) tr(", кэш разговора восстановлен ($restored токенов)", ", conversation cache restored ($restored tokens)") else "")
            }
        }
        scheduleUnload()
        return engine
    }

    /**
     * The dialogue cache saved when the model leaves memory (app-private cache folder; it holds the conversation's
     * tokens, so it is deleted together with the conversation or a forgotten memory). Per model file and context size.
     */
    private fun sessionFile(m: Model, ctx: Int): File = File(app.cacheDir, "llm-session-${m.id}-${fileOf(m).length()}-$ctx.bin")

    private fun deleteSessions() {
        app.cacheDir.listFiles { f -> f.name.startsWith("llm-session-") }?.forEach { it.delete() }
    }

    /** Closes the model; with [save], first keeps its dialogue cache for the next load. Call under [engineLock]. */
    private suspend fun closeEngine(save: Boolean) {
        val e = engine ?: return
        val session = engineSession
        if (save && session != null) {
            val t0 = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) { runCatching { e.saveSession(session.path) }.getOrDefault(false) }
            if (ok) Diag.i("local", tr("Кэш разговора сохранён: ", "Conversation cache saved: ") +
                "${session.length() / 1_000_000} MB, ${System.currentTimeMillis() - t0} ms")
        }
        e.close()
        engine = null; engineFor = null; engineSession = null
    }

    /**
     * The conversation or a memory was erased: the saved dialogue cache (it may still hold them) goes too, and the
     * model in memory is closed, so it starts clean.
     */
    fun forgetSession() {
        scope.launch {
            engineLock.withLock { closeEngine(save = false) }
            deleteSessions()
        }
    }

    /**
     * One thread per performance core (2–4). Phones mix fast and slow cores (Redmi Note 14: 2 fast + 6 slow); threads on
     * slow cores make the fast ones wait. The kernel tells each core's capacity (1024 = the fastest) in /sys.
     */
    private fun threads(): Int = performanceCores().coerceIn(2, 4)

    /** Cores with at least half the capacity of the fastest one (the kernel reports 1024 for it). */
    private fun performanceCores(): Int {
        val caps = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpu_capacity").readText().trim().toInt() }.getOrNull()
        }
        return if (caps.isNotEmpty()) caps.count { it >= caps.max() / 2 } else Runtime.getRuntime().availableProcessors() / 2
    }

    /** Ten quiet minutes: give the memory back to the phone. The next message loads it again in a few seconds. */
    private fun scheduleUnload() {
        idle?.cancel()
        idle = scope.launch { delay(10 * 60_000L); unload() }
    }

    fun unload() {
        scope.launch { engineLock.withLock { closeEngine(save = true) } }
    }

    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
}
