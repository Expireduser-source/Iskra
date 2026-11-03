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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.data.AppSettings
import ru.iskra.app.data.TtsEngine
import ru.iskra.app.diag.Diag
import ru.iskra.core.llm.LlmException
import ru.iskra.core.persona.Emotion
import ru.iskra.core.voice.ElevenLabsTts
import ru.iskra.core.voice.OpenAiTts
import ru.iskra.core.voice.PcmCache
import ru.iskra.core.voice.PcmFormat
import ru.iskra.core.voice.ReplyStream
import ru.iskra.core.voice.TtsCall
import ru.iskra.core.voice.TtsClient
import ru.iskra.core.voice.YandexTts
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Speaks Iskra's replies, sentence by sentence (stage 2):
 *  - [beginStream] / [enqueue] / [endStream]: chunks arrive while the model is still writing; the
 *    first one is voiced at once, the next is synthesized while the current one plays (≤ 2 requests
 *    in flight), all into one continuous audio track;
 *  - short phrases come from [PcmCache] when heard before: instant, offline, free;
 *  - a neural cloud voice if configured, otherwise / on failure the system TTS. After a key or
 *    balance error the cloud voice is skipped for 15 min instead of failing on every sentence.
 *
 * Only Iskra's reply text goes to the voice service — never the owner's messages or memory.
 * All public methods may be called from any thread.
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
    val cache = PcmCache(File(appContext.cacheDir, "tts"))

    private val _speaking = MutableStateFlow(false)
    /** True from the moment speech starts until the last sample was played. */
    val speaking: StateFlow<Boolean> = _speaking
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // --- system TTS
    private var sysReady = false
    private var tts: TextToSpeech? = null
    private val sysTexts = ConcurrentHashMap<String, String>()
    private var sysSeq = 0

    // --- current speech
    private var session: Session? = null
    @Volatile private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var cloudBlockedUntil = 0L

    /** Counters for diagnostics (since start). */
    @Volatile var phrases = 0; private set
    @Volatile var cacheHits = 0; private set

    private class Clip(val text: String, val emotion: Emotion) {
        val format = CompletableDeferred<PcmFormat>()
        val data = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var call: TtsCall? = null
        @Volatile var fromCache = false
    }

    private inner class Session(val commandAt: Long?, val client: TtsClient?, val emotion: Emotion) {
        val startedAt = SystemClock.elapsedRealtime()
        val clips = Channel<Clip>(Channel.UNLIMITED)
        val all = ArrayList<Clip>()
        val fetchSlots = Semaphore(2)
        var player: Job? = null
        @Volatile var system = client == null
        var closed = false
        var sysOutstanding = 0
        var playerDone = client == null
        var firstSoundLogged = false
        var count = 0
        var hits = 0
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) Diag.e("tts", "Системный синтез речи не запустился (код $status)")
            if (status == TextToSpeech.SUCCESS) {
                val r = t.setLanguage(Locale.forLanguageTag("ru-RU"))
                sysReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!sysReady) Diag.w("tts", "Системный голос: нет русского языка (код $r)")
                t.setPitch(1.25f)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onMain { sysStarted(utteranceId) }
                    override fun onDone(utteranceId: String?) = onMain { sysDone(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onMain { sysDone(utteranceId) }
                })
            }
        }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    // ------------------------------------------------------------------ public API

    /**
     * Speaks a finished [text] (cut into sentences, so long replies also start fast and short ones
     * can come from the cache). [commandAtMs] (elapsedRealtime) is when the owner finished the
     * command — only for the latency journal. Returns false if nothing will be spoken.
     */
    fun speak(text: String, emotion: Emotion = Emotion.NEUTRAL, commandAtMs: Long? = null): Boolean {
        val parts = ReplyStream.split(text, emotion)
        if (parts.isEmpty() || !canSpeak()) return false
        onMain {
            beginNow(commandAtMs, emotion)
            parts.forEach { enqueueNow(it, emotion) }
            endNow()
        }
        return true
    }

    /** Starts a reply that will arrive in pieces. Interrupts whatever she was saying. */
    fun beginStream(commandAtMs: Long?, emotion: Emotion = Emotion.NEUTRAL) = onMain { beginNow(commandAtMs, emotion) }
    fun enqueue(text: String, emotion: Emotion) = onMain { enqueueNow(text, emotion) }
    /** No more pieces will come: finish speaking what is queued. */
    fun endStream() = onMain { endNow() }

    fun canSpeak(): Boolean = sysReady || runCatching { cloudClient(settings()) }.getOrNull() != null

    fun stop() = onMain { stopNow() }

    fun shutdown() { stop(); main.post { tts?.shutdown(); tts = null; scope.cancel() } }

    fun clearCache() { cache.clear() }

    // ------------------------------------------------------------------ session (main thread)

    private fun beginNow(commandAt: Long?, emotion: Emotion) {
        stopNow()
        val s = settings()
        val now = SystemClock.elapsedRealtime()
        val client = if (now < cloudBlockedUntil) null else runCatching { cloudClient(s) }.getOrNull()
        if (client == null && s.ttsEngine != TtsEngine.SYSTEM && now >= cloudBlockedUntil)
            Diag.w("tts", "${s.ttsEngine.title} не настроен (нет ключа или адреса) — говорю системным")
        val ss = Session(commandAt, client, emotion)
        session = ss
        _speaking.value = true
        if (client != null) {
            CompanionBus.emit(AvatarCue.Talk("…", lipSync = true))
            requestFocus()
            ss.player = scope.launch { play(ss) }
        }
    }

    private fun enqueueNow(text: String, emotion: Emotion) {
        val ss = session ?: return
        if (ss.closed || text.isBlank()) return
        ss.count++; phrases++
        if (ss.system) { speakSystem(ss, text); return }
        val clip = Clip(text, emotion)
        ss.all += clip
        ss.clips.trySend(clip)
        scope.launch { fetch(ss, clip) }
    }

    private fun endNow() {
        val ss = session ?: return
        if (ss.closed) return
        ss.closed = true
        ss.clips.close()
        maybeFinish(ss)
    }

    private fun stopNow() {
        val ss = session
        session = null
        main.removeCallbacksAndMessages(MOUTH)
        if (ss != null) {
            ss.closed = true
            ss.clips.close()
            ss.player?.cancel()
            ss.all.forEach { it.call?.cancel(); it.data.cancel() }
        }
        releaseTrack()
        tts?.stop()
        sysTexts.clear()
        if (_speaking.value) finished()
    }

    private fun maybeFinish(ss: Session) {
        if (session !== ss || !ss.closed || !ss.playerDone || ss.sysOutstanding > 0) return
        val ms = SystemClock.elapsedRealtime() - ss.startedAt
        if (ss.count > 0) Diag.i("timing", "Озвучка: ${ss.count} фраз, из кэша ${ss.hits}, ${if (ss.system) "системный голос" else ss.client?.name}, всего $ms мс")
        session = null
        releaseTrack()
        finished()
    }

    private fun finished() {
        abandonFocus()
        _speaking.value = false
        CompanionBus.emit(AvatarCue.StopTalking)
    }

    // ------------------------------------------------------------------ cloud voice

    /** Synthesizes one clip (≤ 2 at a time, in order) or takes it from the cache. */
    private suspend fun fetch(ss: Session, clip: Clip) {
        val client = ss.client ?: return
        val s = settings()
        val key = cache.key(client.name, s.voiceOrDefault, s.ttsModelOrDefault, clip.emotion.name, clip.text)
        try {
            ss.fetchSlots.withPermit {
                if (ss.system || session !== ss) { clip.format.completeExceptionally(Skipped()); clip.data.close(); return }
                cache.get(key)?.let { (fmt, bytes) ->
                    clip.fromCache = true
                    clip.format.complete(fmt)
                    clip.data.trySend(bytes); clip.data.close()
                    return
                }
                val call = client.newCall(clip.text, clip.emotion)
                clip.call = call
                val keep = if (PcmCache.cacheable(clip.text)) ByteArrayOutputStream() else null
                var fmt: PcmFormat? = null
                call.stream(onFormat = { f -> fmt = f; clip.format.complete(f) }) { bytes, n ->
                    clip.data.trySend(bytes.copyOf(n))
                    if (keep != null && keep.size() + n <= PcmCache.MAX_CLIP) keep.write(bytes, 0, n)
                }
                clip.data.close()
                val f = fmt
                if (keep != null && f != null && keep.size() > 0 && session === ss) runCatching { cache.put(key, f, keep.toByteArray()) }
            }
        } catch (e: Exception) {
            clip.format.completeExceptionally(e)
            clip.data.close(e)
        }
    }

    /** Plays the clips in order into one AudioTrack; on a failure the rest goes to the system voice. */
    private suspend fun play(ss: Session) {
        var t: AudioTrack? = null
        var trackFormat: PcmFormat? = null
        var framesWritten = 0L
        try {
            for (clip in ss.clips) {
                if (ss.system) { main.post { speakSystem(ss, clip.text) }; continue }
                val fmt = try { clip.format.await() } catch (e: Exception) {
                    if (session !== ss) return
                    // Skipped: the session already switched to the system voice — not a new failure.
                    if (e !is Skipped && !ss.system) cloudFailed(ss, e)
                    main.post { speakSystem(ss, clip.text) }
                    continue
                }
                if (fmt != trackFormat) {
                    t?.let { drain(ss, it, framesWritten, trackFormat!!.sampleRate); it.release() }
                    t = buildTrack(fmt).also { track = it; it.play() }
                    trackFormat = fmt; framesWritten = 0
                }
                val tr = t!!
                if (clip.fromCache) { ss.hits++; cacheHits++ }
                var leftover: Byte? = null
                try {
                    for (bytes in clip.data) {
                        if (session !== ss) return
                        var data = bytes; var len = bytes.size
                        leftover?.let { lb -> data = byteArrayOf(lb) + bytes; len += 1; leftover = null }
                        if (len % 2 == 1) { leftover = data[len - 1]; len -= 1 }
                        if (len == 0) continue
                        if (!ss.firstSoundLogged) { ss.firstSoundLogged = true; logFirstSound(ss, client = ss.client!!.name, cached = clip.fromCache) }
                        scheduleMouth(data, len, fmt.sampleRate, framesWritten - tr.playbackHeadPosition.toLong())
                        tr.write(data, 0, len)
                        framesWritten += len / (2 * fmt.channels)
                    }
                } catch (e: Exception) {
                    if (session !== ss) return
                    if (!ss.system) cloudFailed(ss, e)
                }
            }
            t?.let { drain(ss, it, framesWritten, trackFormat!!.sampleRate) }
            if (!ss.system) _lastError.value = null
        } finally {
            main.post { ss.playerDone = true; maybeFinish(ss) }
        }
    }

    private fun drain(ss: Session, tr: AudioTrack, framesWritten: Long, rate: Int) {
        val deadline = SystemClock.elapsedRealtime() + 3000 + framesWritten * 1000 / rate
        while (session === ss && tr.playbackHeadPosition < framesWritten && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
    }

    private fun cloudFailed(ss: Session, e: Exception) {
        val name = ss.client?.name ?: "голос"
        val le = e as? LlmException
        val msg = le?.let { "$name: ${it.userMessage().substringBefore(" Пока")} (${it.message?.take(120)})" } ?: "$name: ${e.message ?: e.javaClass.simpleName}"
        _lastError.value = msg
        if (le?.persistent == true) {
            cloudBlockedUntil = SystemClock.elapsedRealtime() + 15 * 60_000L
            Diag.e("tts", "$msg — 15 мин говорю системным голосом")
        } else Diag.e("tts", "$msg — переключаюсь на системный голос")
        ss.system = true
        ss.all.forEach { it.call?.cancel() }
    }

    // ------------------------------------------------------------------ system voice

    private fun speakSystem(ss: Session, text: String) {
        if (session !== ss) return
        val t = tts
        if (t == null || !sysReady) {
            Diag.w("tts", "Системный голос недоступен — фраза не озвучена")
            maybeFinish(ss); return
        }
        val id = "iskra-${++sysSeq}"
        sysTexts[id] = text
        ss.sysOutstanding++
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun sysStarted(id: String?) {
        val ss = session ?: return
        val text = id?.let { sysTexts[it] } ?: return
        if (!ss.firstSoundLogged) { ss.firstSoundLogged = true; logFirstSound(ss, "системный", cached = false) }
        CompanionBus.emit(AvatarCue.Talk(text, lipSync = false))
    }

    private fun sysDone(id: String?) {
        if (id == null || sysTexts.remove(id) == null) return
        val ss = session ?: return
        ss.sysOutstanding = (ss.sysOutstanding - 1).coerceAtLeast(0)
        maybeFinish(ss)
    }

    // ------------------------------------------------------------------ helpers

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

    /** First audio of a reply: "TTS: начал говорить, X мс от конца команды". No text is logged. */
    private fun logFirstSound(ss: Session, client: String, cached: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val synth = now - ss.startedAt
        val src = if (cached) "$client, из кэша" else client
        Diag.i("timing", if (ss.commandAt != null) "TTS ($src): начал говорить, ${now - ss.commandAt} мс от конца команды (синтез $synth мс)"
            else "TTS ($src): начал говорить через $synth мс после запроса")
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
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

    /** ~250 ms of audio, rounded to whole frames (AudioTrack rejects sizes that are not a multiple of the frame). */
    private fun bufferBytes(fmt: PcmFormat, min: Int): Int {
        val frame = 2 * fmt.channels
        val wanted = maxOf(if (min > 0) min else 0, fmt.sampleRate * frame / 4)
        return (wanted + frame - 1) / frame * frame
    }

    /** Mouth follows loudness in 40 ms windows, each scheduled for when it will actually be heard. */
    private fun scheduleMouth(data: ByteArray, len: Int, rate: Int, queuedFrames: Long) {
        val window = rate / 25
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
            main.postAtTime({ CompanionBus.emit(AvatarCue.Mouth(level)) }, MOUTH, now + offsetFrames * 1000 / rate)
            offsetFrames += cnt
            i = end
        }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        focus = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private class Skipped : Exception("skipped")

    private companion object { val MOUTH = Any() }
}
