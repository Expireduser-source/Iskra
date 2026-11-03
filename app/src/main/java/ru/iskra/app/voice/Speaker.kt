package ru.iskra.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.data.AppSettings
import ru.iskra.app.data.TtsEngine
import ru.iskra.core.llm.LlmException
import ru.iskra.core.persona.Emotion
import ru.iskra.core.voice.ElevenLabsTts
import ru.iskra.core.voice.OpenAiTts
import ru.iskra.core.voice.PcmFormat
import ru.iskra.core.voice.SpeechText
import ru.iskra.core.voice.TtsCall
import ru.iskra.core.voice.TtsClient
import ru.iskra.core.voice.YandexTts
import java.util.Locale
import kotlin.math.sqrt

/**
 * Speaks Iskra's replies. A neural cloud voice if the owner configured one (streamed PCM, real
 * lip-sync from the audio level), otherwise / on failure the system TTS.
 *
 * Only the reply text goes to the voice service — never the owner's messages or memory.
 */
class Speaker(
    context: Context,
    private val settings: () -> AppSettings,
    private val ttsKey: () -> String?,
    private val llmKeyIfOpenAi: () -> String?,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _speaking = MutableStateFlow(false)
    /** True from the moment speech starts until the last sample was played. */
    val speaking: StateFlow<Boolean> = _speaking
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // --- system TTS
    private var sysReady = false
    private var tts: TextToSpeech? = null

    // --- cloud TTS
    private var job: Job? = null
    @Volatile private var call: TtsCall? = null
    @Volatile private var track: AudioTrack? = null
    private var token = Any()
    private var focus: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale.forLanguageTag("ru-RU"))
                sysReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setPitch(1.25f)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _speaking.value = true }
                    override fun onDone(utteranceId: String?) = finished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished()
                })
            }
        }
    }

    /** Speaks [text]. Returns false if nothing will be spoken (no voice available / empty text). */
    fun speak(text: String, emotion: Emotion = Emotion.NEUTRAL): Boolean {
        stop()
        val clean = SpeechText.clean(text)
        if (clean.isEmpty()) return false
        val client = runCatching { cloudClient(settings()) }.getOrNull()
        if (client == null) return speakSystem(clean)
        startCloud(client, clean, emotion)
        return true
    }

    fun stop() {
        token = Any()
        main.removeCallbacksAndMessages(null)
        job?.cancel(); job = null
        call?.cancel(); call = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        tts?.stop()
        if (_speaking.value) finished()
    }

    fun shutdown() { stop(); tts?.shutdown(); tts = null }

    private fun finished() {
        abandonFocus()
        _speaking.value = false
        CompanionBus.emit(AvatarCue.StopTalking)
    }

    private fun speakSystem(clean: String): Boolean {
        val t = tts ?: return false
        if (!sysReady) return false
        CompanionBus.emit(AvatarCue.Talk(clean, lipSync = false))
        _speaking.value = true
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "iskra-${System.nanoTime()}")
        return true
    }

    private fun cloudClient(s: AppSettings): TtsClient? {
        val voice = s.voiceOrDefault
        val model = s.ttsModelOrDefault
        return when (s.ttsEngine) {
            TtsEngine.SYSTEM -> null
            TtsEngine.YANDEX -> YandexTts(apiKey = ttsKey() ?: return null, voice = voice)
            TtsEngine.ELEVENLABS -> ElevenLabsTts(apiKey = ttsKey() ?: return null, voiceId = voice, model = model)
            TtsEngine.OPENAI -> OpenAiTts(apiKey = ttsKey() ?: llmKeyIfOpenAi() ?: return null, model = model, voice = voice)
            TtsEngine.CUSTOM -> {
                val base = s.ttsBaseUrl.trim()
                if (!base.startsWith("https://") || model.isBlank() || voice.isBlank()) return null
                OpenAiTts(apiKey = ttsKey().orEmpty(), baseUrl = base, model = model, voice = voice)
            }
        }
    }

    private fun startCloud(client: TtsClient, clean: String, emotion: Emotion) {
        val myToken = Any().also { token = it }
        _speaking.value = true
        CompanionBus.emit(AvatarCue.Talk(clean, lipSync = true))
        requestFocus()
        job = scope.launch {
            var t: AudioTrack? = null
            var framesWritten = 0L
            var rate = 24000
            var leftover: Byte? = null
            try {
                val c = client.newCall(clean, emotion)
                call = c
                c.stream(onFormat = { fmt ->
                    rate = fmt.sampleRate
                    t = buildTrack(fmt).also { track = it; it.play() }
                }) { bytes, n ->
                    val tr = t ?: return@stream
                    // Keep 16-bit alignment across network chunks.
                    var data = bytes; var len = n
                    leftover?.let { lb -> data = byteArrayOf(lb) + bytes.copyOf(n); len = n + 1; leftover = null }
                    if (len % 2 == 1) { leftover = data[len - 1]; len -= 1 }
                    if (len == 0) return@stream
                    scheduleMouth(myToken, data, len, rate, framesWritten - tr.playbackHeadPosition.toLong())
                    tr.write(data, 0, len)
                    framesWritten += len / 2
                }
                // Wait for the buffered tail to actually play.
                val tr = t
                if (tr != null) {
                    val deadline = SystemClock.elapsedRealtime() + 3000 + framesWritten * 1000 / rate
                    while (myToken === token && tr.playbackHeadPosition < framesWritten && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(30)
                    }
                }
                _lastError.value = null
                if (myToken === token) main.post { if (myToken === token) cleanupCloud() }
            } catch (e: Exception) {
                if (myToken !== token) return@launch // cancelled by stop()
                val msg = (e as? LlmException)?.let { "${client.name}: ${it.userMessage()} (${it.message?.take(120)})" }
                    ?: "${client.name}: ${e.message ?: e.javaClass.simpleName}"
                _lastError.value = msg
                main.post {
                    if (myToken !== token) return@post
                    releaseTrack()
                    // Fall back to the system voice so the reply is still heard.
                    if (!speakSystem(clean)) finished()
                }
            }
        }
    }

    private fun cleanupCloud() {
        releaseTrack()
        finished()
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null; call = null
    }

    private fun buildTrack(fmt: PcmFormat): AudioTrack {
        val mask = if (fmt.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(fmt.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(fmt.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
            .setBufferSizeInBytes(bufferBytes(fmt, min))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * ~250 ms of audio, rounded to whole frames: AudioTrack rejects sizes that are not a multiple of
     * the frame size ("Invalid audio buffer size" — e.g. 22050 Hz / 4 = 11025 bytes, odd).
     */
    private fun bufferBytes(fmt: PcmFormat, min: Int): Int {
        val frame = 2 * fmt.channels
        val wanted = maxOf(if (min > 0) min else 0, fmt.sampleRate * frame / 4)
        return (wanted + frame - 1) / frame * frame
    }

    /**
     * Mouth follows loudness in 40 ms windows, each scheduled for when it will actually be heard
     * (after the audio already queued in the track).
     */
    private fun scheduleMouth(myToken: Any, data: ByteArray, len: Int, rate: Int, queuedFrames: Long) {
        val window = rate / 25 // 40 ms in samples
        val now = SystemClock.uptimeMillis()
        var offsetFrames = queuedFrames.coerceAtLeast(0)
        var i = 0
        while (i < len) {
            val end = minOf(len, i + window * 2)
            var sum = 0.0; var cnt = 0
            var j = i
            while (j + 1 < end) {
                val s = ((data[j + 1].toInt() shl 8) or (data[j].toInt() and 0xff)).toShort().toDouble()
                sum += s * s; cnt++; j += 2
            }
            val rms = if (cnt > 0) sqrt(sum / cnt) else 0.0
            val level = ((rms - 300.0) / 5000.0).coerceIn(0.0, 1.0).toFloat()
            val at = now + offsetFrames * 1000 / rate
            main.postAtTime({ if (myToken === token) CompanionBus.emit(AvatarCue.Mouth(level)) }, myToken, at)
            offsetFrames += cnt
            i = end
        }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).build()
        focus = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }
}
