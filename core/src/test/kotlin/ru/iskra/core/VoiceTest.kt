package ru.iskra.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import ru.iskra.core.llm.LlmException
import ru.iskra.core.persona.Emotion
import ru.iskra.core.voice.ElevenLabsTts
import ru.iskra.core.voice.OpenAiTts
import ru.iskra.core.voice.PcmFormat
import ru.iskra.core.voice.SpeechText
import ru.iskra.core.voice.VoiceGate
import ru.iskra.core.voice.VoiceGate.Action
import ru.iskra.core.voice.WakeWordMatcher
import ru.iskra.core.voice.YandexTts
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeWordTest {
    private val m = WakeWordMatcher(listOf("Искра", "Искорка"))

    @Test fun matchesNameAndCaseForms() {
        assertEquals("поставь таймер на 5 минут", m.match("Искра, поставь таймер на 5 минут")?.remainder)
        assertEquals("включи фонарик", m.match("эй искорка включи фонарик")?.remainder)
        assertNotNull(m.match("искру позови"))
        assertNotNull(m.match("искре привет"))
        assertNotNull(m.match("изкра привет"), "one recognition error is tolerated")
        assertEquals("", m.match("Искра")?.remainder)
        assertEquals("который час", m.match("искра пожалуйста который час")?.remainder)
    }

    @Test fun ignoresMentionsDeepInPhrase() {
        assertNull(m.match("я вчера видел как из розетки вылетела искра"))
        assertNull(m.match("привет как дела"))
        assertNull(m.match("искренне твой"), "different word sharing the stem")
    }
}

class VoiceGateTest {
    private fun gate() = VoiceGate(WakeWordMatcher(listOf("искра")), attentiveMs = 5_000, followUpMs = 4_000)

    @Test fun commandInOnePhrase() {
        val g = gate()
        assertEquals(Action.Command("открой телеграм"), g.onFinal("Искра открой телеграм", 0))
        assertEquals(VoiceGate.Mode.WAITING, g.mode)
        assertEquals(Action.Ignore, g.onFinal("открой ютуб", 100), "no name, not attentive → ignored")
    }

    @Test fun bareNameThenCommand() {
        val g = gate()
        assertEquals(Action.Perk, g.onFinal("искра", 0))
        assertEquals(Action.Command("сколько времени"), g.onFinal("сколько времени", 2_000))
    }

    @Test fun attentiveTimesOut() {
        val g = gate()
        g.onFinal("искра", 0)
        assertNull(g.tick(4_000))
        assertEquals(Action.Timeout, g.tick(5_001))
        assertEquals(Action.Ignore, g.onFinal("сколько времени", 6_000))
    }

    @Test fun followUpAfterReply() {
        val g = gate()
        g.onReplyFinished(10_000, followUp = true)
        assertEquals(Action.Command("а завтра"), g.onFinal("а завтра", 12_000))
        g.onReplyFinished(20_000, followUp = false)
        assertEquals(Action.Ignore, g.onFinal("а завтра", 21_000))
    }

    @Test fun noiseAndStop() {
        val g = gate()
        g.onReplyFinished(0, followUp = true)
        assertEquals(Action.Ignore, g.onFinal("а", 100), "single noise word must not reach the model")
        assertEquals(Action.Stop, g.onFinal("искра стоп", 200))
        assertEquals(Action.Command("стоп машина поехала дальше"), g.onFinal("искра стоп машина поехала дальше", 300))
    }

    @Test fun partialPerksOncePerPhrase() {
        val g = gate()
        assertFalse(g.onPartial("привет", 0))
        assertTrue(g.onPartial("искра", 10))
        assertFalse(g.onPartial("искра поставь", 20))
        g.onFinal("искра поставь будильник на семь", 30)
        assertTrue(g.onPartial("искра", 40))
    }
}

class TtsTest {
    private val server = MockWebServer().apply { start() }
    @AfterTest fun stop() = server.shutdown()

    private fun wav(rate: Int, pcm: ByteArray): Buffer {
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        fun le32(v: Int) = le16(v and 0xffff) + le16(v ushr 16)
        val b = Buffer()
        b.write("RIFF".toByteArray()); b.write(le32(-1)); b.write("WAVE".toByteArray())
        b.write("LIST".toByteArray()); b.write(le32(4)); b.write("INFO".toByteArray()) // extra chunk to skip
        b.write("fmt ".toByteArray()); b.write(le32(16))
        b.write(le16(1)); b.write(le16(1)); b.write(le32(rate)); b.write(le32(rate * 2)); b.write(le16(2)); b.write(le16(16))
        b.write("data".toByteArray()); b.write(le32(-1))
        b.write(pcm)
        return b
    }

    private fun collect(call: ru.iskra.core.voice.TtsCall): Pair<PcmFormat?, ByteArray> {
        var fmt: PcmFormat? = null
        val out = ByteArrayOutputStream()
        call.stream({ fmt = it }) { b, n -> out.write(b, 0, n) }
        return fmt to out.toByteArray()
    }

    @Test fun openAiParsesWavAndSendsInstructions() {
        val pcm = ByteArray(1000) { it.toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(wav(24000, pcm)))
        val tts = OpenAiTts("sk-test", baseUrl = server.url("/v1").toString(), voice = "nova")
        val (fmt, data) = collect(tts.newCall("Привет!", Emotion.HAPPY))
        assertEquals(PcmFormat(24000, 1), fmt)
        assertTrue(pcm.contentEquals(data))
        val r = server.takeRequest()
        assertEquals("/v1/audio/speech", r.path)
        assertEquals("Bearer sk-test", r.getHeader("Authorization"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("радостно"))
    }

    @Test fun elevenLabsRawPcm() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm").setBody(Buffer().write(ByteArray(64))))
        val tts = ElevenLabsTts("xi", voiceId = "v1", baseUrl = server.url("/").toString())
        val (fmt, data) = collect(tts.newCall("Мяу", Emotion.NEUTRAL))
        assertEquals(PcmFormat(22050), fmt)
        assertEquals(64, data.size)
        val r = server.takeRequest()
        assertEquals("/v1/text-to-speech/v1/stream?output_format=pcm_22050", r.path)
        assertEquals("xi", r.getHeader("xi-api-key"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("ru", body["language_code"]!!.jsonPrimitive.content)
    }

    @Test fun yandexFormAndEmotion() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-pcm").setBody(Buffer().write(ByteArray(10))))
        val tts = YandexTts("ak", voice = "jane", baseUrl = server.url("/").toString())
        val (fmt, _) = collect(tts.newCall("Привет", Emotion.POUTY))
        assertEquals(PcmFormat(48000), fmt)
        val r = server.takeRequest()
        assertEquals("Api-Key ak", r.getHeader("Authorization"))
        val form = r.body.readUtf8()
        assertTrue("emotion=evil" in form && "voice=jane" in form && "format=lpcm" in form, form)

        server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-pcm").setBody(Buffer().write(ByteArray(10))))
        collect(YandexTts("ak", voice = "filipp", baseUrl = server.url("/").toString()).newCall("Привет", Emotion.HAPPY))
        assertFalse("emotion=" in server.takeRequest().body.readUtf8(), "voices without emotions must not get the field")
    }

    @Test fun errorsAreTyped() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val e = assertFailsWith<LlmException> { collect(OpenAiTts("bad", baseUrl = server.url("/v1").toString()).newCall("x", Emotion.NEUTRAL)) }
        assertEquals(LlmException.Kind.AUTH, e.kind)

        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"detail":"oops"}"""))
        assertFailsWith<LlmException> { collect(ElevenLabsTts("k", baseUrl = server.url("/").toString()).newCall("x", Emotion.NEUTRAL)) }
    }

    @Test fun cleansTextForSpeech() {
        assertEquals("Привет! Как ты?", SpeechText.clean("[happy] Привет! *машет хвостом* Как ты? 😺"))
        assertEquals("смотри ссылка", SpeechText.clean("смотри https://example.com/x"))
    }
}
