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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import ru.iskra.core.voice.VoiceGate
import ru.iskra.core.voice.WakeWordMatcher

/**
 * Always-on listening, fully on the device: the microphone stream goes into the offline Vosk
 * recogniser, and [VoiceGate] decides which phrases are meant for Iskra. Nothing is recorded or
 * sent anywhere; only the recognised command text continues to the chat.
 *
 * Audio runs on its own thread; every callback is delivered on the main thread.
 */
class VoiceEar(
    private val context: Context,
    names: List<String>,
    private val followUp: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onPerk()                 // her name was heard
        fun onCommand(text: String)
        fun onStopRequested()
        fun onAttentionLost()        // nothing came after the name
        fun onEarError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val gate = VoiceGate(WakeWordMatcher(names))
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    val isRunning get() = running

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onEarError("Нет доступа к микрофону."); return
        }
        if (!SpeechModel.isReady(context)) { listener.onEarError("Модель распознавания не скачана."); return }
        running = true
        thread = Thread({ loop() }, "iskra-ear").apply { priority = Thread.NORM_PRIORITY - 1; start() }
        main.post(ticker)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        thread?.interrupt()
        thread = null
    }

    /** Pause while she speaks (so she does not hear herself) or while another recogniser uses the mic. */
    fun setPaused(p: Boolean) { paused = p }

    /** She finished an answer: listen for a reply without her name for a few seconds. */
    fun replyFinished() = gate.onReplyFinished(SystemClock.elapsedRealtime(), followUp)

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (gate.tick(SystemClock.elapsedRealtime()) == VoiceGate.Action.Timeout) listener.onAttentionLost()
            main.postDelayed(this, 500)
        }
    }

    private fun onPartial(text: String) {
        if (gate.onPartial(text, SystemClock.elapsedRealtime())) listener.onPerk()
    }

    private fun onFinal(text: String) {
        when (val a = gate.onFinal(text, SystemClock.elapsedRealtime())) {
            VoiceGate.Action.Perk -> listener.onPerk()
            is VoiceGate.Action.Command -> listener.onCommand(a.text)
            VoiceGate.Action.Stop -> listener.onStopRequested()
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(SpeechModel.dir(context).absolutePath)
            rec = Recognizer(model, RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, RATE * 2 / 2), // ≥0.5 s of audio
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон недоступен")
            val buf = ShortArray(RATE / 10) // 100 ms
            var recording = false
            var n = 0
            while (running) {
                if (paused) {
                    if (recording) { audio.stop(); recording = false; rec.reset() }
                    Thread.sleep(100)
                    continue
                }
                if (!recording) { audio.startRecording(); recording = true }
                val read = audio.read(buf, 0, buf.size)
                if (read < 0) throw IllegalStateException("ошибка чтения микрофона ($read)")
                if (read == 0) continue
                if (rec.acceptWaveForm(buf, read)) {
                    val text = field(rec.result, "text")
                    if (text.isNotBlank()) main.post { if (running) onFinal(text) }
                } else if (++n % 3 == 0) {
                    val partial = field(rec.partialResult, "partial")
                    if (partial.isNotBlank()) main.post { if (running) onPartial(partial) }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            main.post { listener.onEarError("Слух остановлен: $msg") }
            running = false
        } finally {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { rec?.close() }
            runCatching { model?.close() }
        }
    }

    private fun field(json: String, key: String): String =
        runCatching { Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private companion object { const val RATE = 16_000 }
}
