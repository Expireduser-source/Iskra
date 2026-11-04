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
import ru.iskra.app.diag.Diag
import ru.iskra.core.voice.EnergyVad
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
    private val names: List<String>,
    private val followUp: Boolean,
    private val listener: Listener,
    /** Stage 2 "экономный слух": skip silence, and while waiting recognise only her name. */
    private val economical: Boolean = true,
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
    private val nameMatcher = WakeWordMatcher(names)
    /** Main thread → audio thread: a command is expected without the name, so the full recogniser runs. */
    @Volatile private var attentive = false
    /** Includes the name-free follow-up window after Iskra finishes speaking. */
    val isAttentive: Boolean get() = attentive
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
    fun replyFinished() {
        gate.onReplyFinished(SystemClock.elapsedRealtime(), followUp)
        attentive = gate.mode == VoiceGate.Mode.ATTENTIVE
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (gate.tick(SystemClock.elapsedRealtime()) == VoiceGate.Action.Timeout) { attentive = false; listener.onAttentionLost() }
            main.postDelayed(this, 500)
        }
    }

    private fun onPartial(text: String) {
        if (gate.onPartial(text, SystemClock.elapsedRealtime())) { attentive = true; listener.onPerk() }
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
        var full: Recognizer? = null
        var wake: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = VoskModels.acquire(context)
            val fullRec = Recognizer(model, RATE.toFloat()).also { full = it }
            val wakeRec: Recognizer? = if (!economical) null else try {
                Recognizer(model, RATE.toFloat(), wakeGrammar(names))
            } catch (e: Throwable) {
                main.post { Diag.w("ear", "Экономный слух недоступен для этой модели (${e.javaClass.simpleName}) — слушаю полностью") }
                null
            }
            wake = wakeRec
            val vad = if (economical) EnergyVad() else null
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, RATE * 2 / 2), // ≥0.5 s of audio
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("микрофон недоступен")
            EarStats.mode = when {
                wakeRec != null -> "экономный: в ожидании только имя, тишина пропускается"
                vad != null -> "тишина пропускается, распознавание полное"
                else -> "полный (экономный слух выключен)"
            }
            main.post { Diag.i("ear", "Слух: ${EarStats.mode}") }
            val buf = ShortArray(RATE / 10) // 100 ms
            val history = ArrayDeque<ShortArray>() // last 1.5 s, replayed into the full recogniser after the name
            var recording = false
            var n = 0
            var afterName = false               // the wake recogniser heard the name: full until the phrase ends
            var useFull = wakeRec == null
            fun current(): Recognizer = if (useFull || wakeRec == null) fullRec else wakeRec

            fun feed(chunk: ShortArray) {
                val rec = current()
                EarStats.fedChunks++
                if (rec.acceptWaveForm(chunk, chunk.size)) {
                    val text = field(rec.result, "text")
                    finalText(rec === fullRec, text)
                } else if (++n % 3 == 0) {
                    val partial = field(rec.partialResult, "partial")
                    if (partial.isNotBlank()) partialText(rec === fullRec, partial)
                }
            }

            // Wake recogniser found the name: replay the last 1.5 s into the full one, so
            // "Искра, поставь таймер" said in one breath is recognised whole.
            fun switchToFull() {
                EarStats.wakeHits++
                afterName = true; useFull = true
                fullRec.reset()
                history.forEach { fullRec.acceptWaveForm(it, it.size) }
                main.post { if (running) listener.onPerk() }
            }

            this.onWakeHit = ::switchToFull
            this.onFullFinal = {
                afterName = false
                if (wakeRec != null && !attentive) { useFull = false; wakeRec.reset() }
            }

            while (running) {
                if (paused) {
                    if (recording) { audio.stop(); recording = false; fullRec.reset(); wakeRec?.reset() }
                    vad?.reset(); history.clear(); afterName = false
                    useFull = wakeRec == null || attentive
                    Thread.sleep(100)
                    continue
                }
                if (!recording) { audio.startRecording(); recording = true }
                val read = audio.read(buf, 0, buf.size)
                if (read < 0) throw IllegalStateException("ошибка чтения микрофона ($read)")
                if (read == 0) continue
                val chunk = buf.copyOf(read)
                EarStats.totalChunks++
                // Wanted mode can change from the main thread (follow-up after a reply, timeout).
                if (wakeRec != null && !afterName) {
                    val want = attentive
                    if (want != useFull) {
                        useFull = want
                        if (!want) wakeRec.reset()
                        else { fullRec.reset(); history.takeLast(3).forEach { fullRec.acceptWaveForm(it, it.size) } }
                    }
                }
                val decision = vad?.update(EnergyVad.rms(chunk, read))
                when (decision) {
                    EnergyVad.Decision.SILENCE -> {
                        history.addLast(chunk); while (history.size > 15) history.removeFirst()
                        continue
                    }
                    EnergyVad.Decision.START -> history.takeLast(vad!!.preRollChunks).forEach(::feed)
                    else -> Unit
                }
                history.addLast(chunk); while (history.size > 15) history.removeFirst()
                feed(chunk)
                if (decision == EnergyVad.Decision.END) {
                    // Speech is over: take the result now instead of waiting for more audio.
                    val rec = current()
                    finalText(rec === fullRec, field(rec.finalResult, "text"))
                    // The name was found only now, at the end of the phrase: the replayed audio is
                    // all there is, so finish the full recogniser too instead of waiting for more speech.
                    if (rec !== fullRec && useFull) finalText(true, field(fullRec.finalResult, "text"))
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
            runCatching { wake?.close() }
            runCatching { full?.close() }
            if (model != null) VoskModels.release()
        }
    }

    private var onWakeHit: () -> Unit = {}
    private var onFullFinal: () -> Unit = {}

    /** Audio thread: a final phrase from the full or the wake recogniser. */
    private fun finalText(fromFull: Boolean, text: String) {
        if (!fromFull) { if (hasName(text)) onWakeHit(); return }
        onFullFinal()
        if (text.isBlank()) return
        main.post { if (running) { onFinal(text); attentive = gate.mode == VoiceGate.Mode.ATTENTIVE } }
    }

    private fun partialText(fromFull: Boolean, text: String) {
        if (!fromFull) { if (hasName(text)) onWakeHit(); return }
        main.post { if (running) onPartial(text) }
    }

    private fun hasName(text: String) = WakeWordMatcher.tokens(text).any(nameMatcher::isName)

    private fun field(json: String, key: String): String =
        runCatching { Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private companion object {
        const val RATE = 16_000

        /** Vosk grammar: the names and their case forms (искра → искру, искры, искре, искрой), anything else is [unk]. */
        fun wakeGrammar(names: List<String>): String {
            val words = LinkedHashSet<String>()
            for (raw in names.ifEmpty { listOf("Искра") }) {
                val n = WakeWordMatcher.norm(raw)
                if (n.length < 3 || n.any { !it.isLetter() }) continue
                words += n
                if (n.endsWith("а")) {
                    val stem = n.dropLast(1)
                    val soft = stem.last() in "кгхжшчщ"
                    words += listOf(stem + "у", stem + (if (soft) "и" else "ы"), stem + "е", stem + "ой")
                }
            }
            if (words.isEmpty()) words += listOf("искра", "искру", "искры", "искре", "искрой")
            return (words.map { "\"$it\"" } + "\"[unk]\"").joinToString(",", "[", "]")
        }
    }
}

/** How much the ear actually worked: for diagnostics and the battery report. */
object EarStats {
    @Volatile var mode = "—"
    @Volatile var totalChunks = 0L
    @Volatile var fedChunks = 0L
    @Volatile var wakeHits = 0L

    fun describe(): String {
        if (totalChunks == 0L) return mode
        val share = fedChunks * 100 / totalChunks
        return "$mode; распознавание работало $share % времени, имя услышано $wakeHits раз (за ${totalChunks / 600} мин)"
    }
}
