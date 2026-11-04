package ru.iskra.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import ru.iskra.app.diag.Diag

/**
 * One phrase for the chat, recognised on the phone by the downloaded Vosk model: for phones without
 * a system recognizer (Huawei without Google services, some custom firmware) and for owners who do
 * not want audio to leave the phone. Same callbacks as [Listener]; audio is never stored.
 */
class OfflineDictation(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun available() = SpeechModel.isReady(context)

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Нет доступа к микрофону."); return
        }
        running = true
        onState(true)
        thread = Thread({ loop() }, "iskra-dictation").apply { start() }
    }

    /** Stops listening and keeps what was heard so far. */
    fun stop() { running = false }

    fun destroy() { running = false }

    @SuppressLint("MissingPermission") // checked in start()
    private fun loop() {
        var text = ""
        var error: String? = null
        var model: Model? = null
        var rec: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            model = Model(SpeechModel.dir(context).absolutePath)
            val r = Recognizer(model, RATE.toFloat()).also { rec = it }
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE)).also { audio = it }
            if (a.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон недоступен")
            a.startRecording()
            val buf = ShortArray(RATE / 10)
            val started = SystemClock.elapsedRealtime()
            var heard = false
            while (running) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (r.acceptWaveForm(buf, n)) {
                    text = field(r.result, "text")
                    if (text.isNotBlank()) break
                } else if (field(r.partialResult, "partial").isNotBlank()) heard = true
                val ms = SystemClock.elapsedRealtime() - started
                if ((!heard && ms > 8_000) || ms > 30_000) break
            }
            if (text.isBlank()) text = field(r.finalResult, "text")
        } catch (e: Throwable) {
            error = "Распознавание на телефоне не запустилось: ${e.message ?: e.javaClass.simpleName}"
            main.post { Diag.e("voice", "Офлайн-диктовка не запустилась", e) }
        } finally {
            runCatching { audio?.stop() }; runCatching { audio?.release() }
            runCatching { rec?.close() }; runCatching { model?.close() }
        }
        val result = text.trim()
        main.post {
            running = false
            onState(false)
            when {
                error != null -> onError(error)
                result.isEmpty() -> onError("Не расслышала. Попробуй ещё раз.")
                else -> { Diag.i("voice", "Офлайн-диктовка: ${result.length} симв."); onResult(result) }
            }
        }
    }

    private fun field(json: String?, key: String): String = runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("")

    private companion object { const val RATE = 16_000 }
}
