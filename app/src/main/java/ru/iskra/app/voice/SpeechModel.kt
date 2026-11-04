package ru.iskra.app.voice

import ru.iskra.core.i18n.tr
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The offline speech model for always-on listening and on-phone dictation, in the UI language:
 * Vosk "small-ru" (~45 MB) or "small-en-us" (~40 MB), both Apache-2.0. Downloaded once on the owner's
 * request into app-private storage; audio never leaves the phone.
 */
object SpeechModel {
    private class Spec(val url: String, val sizeMb: Int, val dir: String)
    private val RU = Spec("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45, "vosk-ru")
    private val EN = Spec("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40, "vosk-en")
    private val spec get() = tr(RU, EN)

    val URL: String get() = spec.url
    val SIZE_MB: Int get() = spec.sizeMb
    private const val MAX_UNPACKED = 300L * 1024 * 1024

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    fun dir(ctx: Context) = File(ctx.filesDir, spec.dir)
    private fun marker(ctx: Context) = File(dir(ctx), ".ready")

    fun isReady(ctx: Context) = marker(ctx).exists()

    fun refresh(ctx: Context) {
        if (_state.value !is State.Downloading) _state.value = if (isReady(ctx)) State.Ready else State.Missing
    }

    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
        _state.value = State.Missing
    }

    suspend fun download(ctx: Context) = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        _state.value = State.Downloading(0f)
        val zip = File(ctx.cacheDir, spec.dir + ".zip")
        val tmp = File(ctx.filesDir, spec.dir + ".tmp")
        try {
            val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException(tr("пустой ответ", "empty response"))
                val total = body.contentLength().takeIf { it > 0 } ?: (SIZE_MB * 1024L * 1024L)
                body.byteStream().use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            _state.value = State.Downloading((done.toFloat() / total).coerceAtMost(0.99f))
                        }
                    }
                }
            }
            tmp.deleteRecursively(); tmp.mkdirs()
            unzip(zip, tmp)
            val root = findModelRoot(tmp) ?: throw IOException(tr("в архиве нет модели", "no model in the archive"))
            val target = dir(ctx)
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException(tr("не удалось сохранить модель", "couldn't save the model"))
            File(target, ".ready").writeText(URL)
            _state.value = State.Ready
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            ru.iskra.app.diag.Diag.e("model", tr("Модель распознавания не скачалась", "The speech model didn't download"), e)
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    /** Extracts with zip-slip and zip-bomb protection. */
    private fun unzip(zip: File, into: File) {
        val base = into.canonicalPath + File.separator
        var unpacked = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(base)) throw IOException(tr("подозрительный путь в архиве", "suspicious path in the archive"))
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        unpacked += n
                        if (unpacked > MAX_UNPACKED) throw IOException(tr("архив слишком большой", "the archive is too big"))
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun findModelRoot(dir: File, depth: Int = 0): File? {
        if (File(dir, "am").isDirectory && File(dir, "conf").isDirectory) return dir
        if (depth >= 2) return null
        return dir.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { findModelRoot(it, depth + 1) }
    }
}
