package ru.iskra.core.voice

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.iskra.core.llm.LlmException
import ru.iskra.core.persona.Emotion
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 16-bit little-endian PCM. */
data class PcmFormat(val sampleRate: Int, val channels: Int = 1)

/** One synthesis request. [stream] blocks until the audio has been fully delivered or [cancel] is called. */
interface TtsCall {
    fun stream(onFormat: (PcmFormat) -> Unit, sink: (ByteArray, Int) -> Unit)
    fun cancel()
}

/** A neural text-to-speech service. Implementations only build HTTP requests; playback is the app's job. */
interface TtsClient {
    val name: String
    fun newCall(text: String, emotion: Emotion): TtsCall
}

/** Strips things that should not be read aloud: *actions*, emoji, markdown. */
object SpeechText {
    fun clean(text: String): String = text
        .replace(Regex("^\\s*\\[[a-zA-Z_]{2,20}]\\s*"), "")         // leading [emotion] tag, if any survived
        .replace(Regex("\\[[^\\]\\n]{1,24}]"), " ")                    // short bracketed tags small models invent: [mrр]
        .replace(Regex("\\*[^*]{1,80}\\*"), " ")                       // *мурлычет*
        .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\u200d\\ufe0f]"), " ")  // emoji
        .replace(Regex("[#_`>~]"), " ")
        .replace(Regex("https?://\\S+"), tr(" ссылка ", " link "))
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_CHARS)

    /** Cloud TTS is billed per character; replies are short anyway. */
    const val MAX_CHARS = 1200
}

private class HttpTtsCall(
    private val client: OkHttpClient,
    private val request: Request,
    /** null → the body is a WAV file (rate read from its header). */
    private val rawFormat: PcmFormat?,
) : TtsCall {
    private val call: Call = client.newCall(request)

    override fun stream(onFormat: (PcmFormat) -> Unit, sink: (ByteArray, Int) -> Unit) {
        val response = try { call.execute() } catch (e: IOException) {
            throw LlmException("TTS network error: ${e.javaClass.simpleName}", LlmException.Kind.NETWORK, e)
        }
        response.use { r ->
            val body = r.body ?: throw LlmException("TTS: empty body", LlmException.Kind.BAD_RESPONSE)
            if (!r.isSuccessful) {
                val snippet = runCatching { body.string().take(300) }.getOrDefault("")
                val kind = when (r.code) {
                    401, 403 -> LlmException.Kind.AUTH
                    429 -> LlmException.Kind.RATE_LIMIT
                    in 500..599 -> LlmException.Kind.SERVER
                    else -> LlmException.Kind.BAD_RESPONSE
                }
                throw LlmException("TTS HTTP ${r.code}: $snippet", kind)
            }
            val ct = r.header("Content-Type").orEmpty()
            if (ct.startsWith("application/json") || ct.startsWith("text/")) {
                throw LlmException("TTS returned $ct instead of audio: ${body.string().take(200)}", LlmException.Kind.BAD_RESPONSE)
            }
            try {
                val input = body.byteStream()
                val fmt = rawFormat ?: WavHeader.read(input)
                onFormat(fmt)
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) sink(buf, n)
                }
            } catch (e: IOException) {
                if (call.isCanceled()) return
                throw LlmException("TTS read error", LlmException.Kind.NETWORK, e)
            }
        }
    }

    override fun cancel() = call.cancel()
}

/** Minimal streaming WAV header reader (sizes may be 0xFFFFFFFF in streamed files, so they are ignored). */
object WavHeader {
    fun read(input: InputStream): PcmFormat {
        val riff = readExact(input, 12)
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw LlmException("TTS: not a WAV stream", LlmException.Kind.BAD_RESPONSE)
        }
        var format: PcmFormat? = null
        while (true) {
            val hdr = readExact(input, 8)
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = le32(hdr, 4)
            when (id) {
                "fmt " -> {
                    val f = readExact(input, size.toInt())
                    val audioFormat = le16(f, 0)
                    val channels = le16(f, 2)
                    val rate = le32(f, 4).toInt()
                    val bits = le16(f, 14)
                    if ((audioFormat != 1 && audioFormat != 0xFFFE) || bits != 16) {
                        throw LlmException("TTS: unsupported WAV (format=$audioFormat, bits=$bits)", LlmException.Kind.BAD_RESPONSE)
                    }
                    format = PcmFormat(rate, channels)
                    if (size % 2 == 1L) input.read()
                }
                "data" -> return format ?: throw LlmException("TTS: WAV without fmt", LlmException.Kind.BAD_RESPONSE)
                else -> {
                    if (size > 1_000_000) throw LlmException("TTS: bad WAV chunk", LlmException.Kind.BAD_RESPONSE)
                    readExact(input, (size + (size and 1)).toInt())
                }
            }
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw LlmException("TTS: truncated WAV", LlmException.Kind.BAD_RESPONSE)
            off += r
        }
        return b
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Long =
        (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
}

internal object TtsHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val json = "application/json".toMediaType()
}

/** How she should sound for each emotion, in words (for models that accept style instructions). */
internal fun emotionHint(e: Emotion): String = when (e) {
    Emotion.HAPPY -> tr("радостно, с улыбкой", "cheerfully, with a smile")
    Emotion.EXCITED -> tr("очень воодушевлённо, быстро и звонко", "very excitedly, fast and bright")
    Emotion.CURIOUS -> tr("заинтересованно, с вопросительной интонацией", "with interest, in a questioning tone")
    Emotion.SHY -> tr("смущённо, тихо и мягко", "shyly, softly and gently")
    Emotion.POUTY -> tr("капризно-обиженно, но мило", "in a sulky-pouty but cute way")
    Emotion.SAD -> tr("грустно и тихо", "sadly and quietly")
    Emotion.SURPRISED -> tr("удивлённо", "in surprise")
    Emotion.SLEEPY -> tr("сонно и медленно", "sleepily and slowly")
    Emotion.THINKING -> tr("задумчиво", "thoughtfully")
    Emotion.NEUTRAL -> tr("спокойно и дружелюбно", "calmly and friendly")
}

/**
 * OpenAI `/audio/speech` and compatible self-hosted servers. Asks for WAV so any server works,
 * and passes style [instructions] (honoured by gpt-4o-mini-tts, ignored by others).
 */
class OpenAiTts(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini-tts",
    private val voice: String = "nova",
    private val client: OkHttpClient = TtsHttp.client,
) : TtsClient {
    override val name get() = tr("OpenAI-совместимый", "OpenAI-compatible")

    override fun newCall(text: String, emotion: Emotion): TtsCall {
        val body = buildJsonObject {
            put("model", model)
            put("voice", voice)
            put("input", text)
            put("response_format", "wav")
            put("instructions", tr("Ты — Искра, милая игривая девушка-кошка из аниме. Говори по-русски естественно и живо, " +
                "молодым тёплым голосом. Сейчас говори ${emotionHint(emotion)}.", "You are Iskra, a sweet playful anime cat girl. Speak the text naturally and lively, with a young warm voice. Now speak ${emotionHint(emotion)}."))
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(TtsHttp.json))
            .build()
        return HttpTtsCall(client, req, rawFormat = null)
    }
}

/** ElevenLabs streaming TTS; raw PCM 22.05 kHz keeps latency and parsing trivial. */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String = DEFAULT_VOICE,
    private val model: String = "eleven_flash_v2_5",
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val client: OkHttpClient = TtsHttp.client,
) : TtsClient {
    override val name = "ElevenLabs"

    override fun newCall(text: String, emotion: Emotion): TtsCall {
        // Lower stability = more expressive. Calm emotions get steadier delivery.
        val stability = when (emotion) {
            Emotion.EXCITED, Emotion.POUTY, Emotion.SURPRISED -> 0.3
            Emotion.SAD, Emotion.SLEEPY, Emotion.NEUTRAL -> 0.55
            else -> 0.42
        }
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model)
            // Only the v2.5 models accept language enforcement; others reject the field.
            if (model.contains("v2_5")) put("language_code", "ru")
            put("voice_settings", buildJsonObject {
                put("stability", stability)
                put("similarity_boost", 0.75)
                put("style", 0.35)
            })
        }
        val url = (baseUrl.trimEnd('/') + "/v1/text-to-speech/$voiceId/stream").toHttpUrl().newBuilder()
            .addQueryParameter("output_format", "pcm_22050")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .post(body.toString().toRequestBody(TtsHttp.json))
            .build()
        return HttpTtsCall(client, req, rawFormat = PcmFormat(22050))
    }

    companion object {
        /** "Jessica" from the ElevenLabs default library — young, playful; speaks Russian via multilingual models. */
        const val DEFAULT_VOICE = "cgSgspJ2msm6clMCkdW9"
    }
}

/** Yandex SpeechKit v1 (good Russian voices, paid in roubles). Auth: service-account API key. */
class YandexTts(
    private val apiKey: String,
    private val voice: String = "jane",
    private val baseUrl: String = "https://tts.api.cloud.yandex.net",
    private val client: OkHttpClient = TtsHttp.client,
) : TtsClient {
    override val name = "Yandex SpeechKit"

    override fun newCall(text: String, emotion: Emotion): TtsCall {
        val form = FormBody.Builder()
            .add("text", text)
            .add("lang", "ru-RU")
            .add("voice", voice)
            .add("format", "lpcm")
            .add("sampleRateHertz", "48000")
            .add("speed", if (emotion == Emotion.EXCITED) "1.15" else if (emotion == Emotion.SLEEPY || emotion == Emotion.SAD) "0.9" else "1.05")
        // Only some v1 voices support an emotion parameter; sending it to others is an error.
        val emotions = EMOTION_VOICES[voice]
        if (emotions != null) {
            val wanted = when (emotion) {
                Emotion.HAPPY, Emotion.EXCITED, Emotion.SHY, Emotion.CURIOUS, Emotion.SURPRISED -> "good"
                Emotion.POUTY -> "evil"
                else -> "neutral"
            }
            if (wanted in emotions) form.add("emotion", wanted)
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/speech/v1/tts:synthesize")
            .header("Authorization", "Api-Key $apiKey")
            .post(form.build())
            .build()
        return HttpTtsCall(client, req, rawFormat = PcmFormat(48000))
    }

    private companion object {
        val EMOTION_VOICES = mapOf(
            "jane" to setOf("neutral", "good", "evil"),
            "omazh" to setOf("neutral", "evil"),
            "alena" to setOf("neutral", "good"),
        )
    }
}
