package ru.iskra.core

import ru.iskra.core.diag.DiagEntry
import ru.iskra.core.diag.DiagInfo
import ru.iskra.core.diag.DiagReport
import ru.iskra.core.diag.Redactor
import ru.iskra.core.diag.RingLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    private val openAi = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
    private val anthropic = "sk-ant-api03-ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210"
    private val openRouter = "sk-or-v1-0123456789abcdef0123456789abcdef0123456789abcdef"
    private val google = "AIzaSyD-1234567890abcdefghijklmnopqrstu"
    private val yandex = "AQVN1abcdefghijklmnopqrstuvwxyz0123456"
    private val eleven = "sk_0123456789abcdef0123456789abcdef0123456789abcdef"
    private val odd = "MyOwnServerKey!2024"

    @Test fun maskKeepsOnlyPrefixAndLength() {
        val m = Redactor.mask(openAi)
        assertEquals("sk-p…***(${openAi.length})", m)
        assertFalse(m.contains(openAi.substring(4, 10)))
        assertEquals("***", Redactor.mask("short"))
        assertEquals("", Redactor.mask("  "))
    }

    @Test fun knownFormatsAreMaskedInsideText() {
        for (k in listOf(openAi, anthropic, openRouter, google, yandex, eleven)) {
            val out = Redactor.redact("HTTP 401: invalid key $k, retry")
            assertFalse(out.contains(k), "leaked: $out")
            assertTrue(out.contains("…***"), "not masked: $out")
            assertTrue(out.startsWith("HTTP 401: invalid key "), "context lost: $out")
        }
    }

    @Test fun headersAndQueryParamsAreMasked() {
        val cases = listOf(
            "Authorization: Bearer abcdefghijklmnop123",
            "Authorization: Api-Key abcdefghijklmnop123",
            "xi-api-key: abcdefghijklmnop123",
            """{"api_key": "abcdefghijklmnop123"}""",
            "https://example.com/v1/tts?key=abcdefghijklmnop123&voice=jane",
            "password=abcdefghijklmnop123",
        )
        for (c in cases) {
            val out = Redactor.redact(c)
            assertFalse(out.contains("abcdefghijklmnop123"), "leaked: $out")
        }
        assertTrue(Redactor.redact("https://example.com/v1/tts?key=abcdefghijklmnop123&voice=jane").endsWith("&voice=jane"))
    }

    @Test fun savedKeyIsMaskedEvenWithoutKnownFormat() {
        val out = Redactor.redact("server said: $odd is wrong", known = listOf(odd))
        assertFalse(out.contains(odd))
        assertTrue(out.contains("MyOw…***"))
    }

    @Test fun ordinaryTextIsUntouched() {
        val t = "Слух остановлен: микрофон недоступен (код -3), модель vosk-model-small-ru-0.22, голос cgSgspJ2msm6clMCkdW9"
        assertEquals(t, Redactor.redact(t))
    }

    @Test fun redactingTwiceIsStable() {
        val once = Redactor.redact("Bearer $openAi")
        assertEquals(once, Redactor.redact(once))
    }
}

class RingLogTest {
    @Test fun keepsOnlyTheLastEntries() {
        var t = 0L
        val log = RingLog(capacity = 5, clock = { t++ })
        repeat(12) { log.add(DiagEntry.Level.INFO, "t", "event $it") }
        assertEquals((7..11).map { "event $it" }, log.entries().map { it.message })
    }

    @Test fun secretsNeverEnterTheLog() {
        val saved = "plain-secret-value-42"
        val log = RingLog(knownSecrets = { listOf(saved) })
        log.add(DiagEntry.Level.ERROR, "tts", "failed with $saved and sk-abcdefghijklmnopqrstuvwxyz")
        val stored = log.encode()
        assertFalse(stored.contains(saved))
        assertFalse(stored.contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }

    @Test fun longMessagesAreCapped() {
        val log = RingLog(maxMessage = 50)
        val e = log.add(DiagEntry.Level.WARN, "x", "ж".repeat(500))
        assertEquals(50, e.message.length)
    }

    @Test fun survivesSaveAndLoad() {
        var t = 1000L
        val a = RingLog(capacity = 3, clock = { t++ })
        a.add(DiagEntry.Level.INFO, "app", "start\twith tab\nand newline")
        a.add(DiagEntry.Level.ERROR, "ear", "mic busy")
        val b = RingLog(capacity = 3)
        b.load(a.encode() + "\ngarbage line\n")
        assertEquals(a.entries(), b.entries())
        assertEquals("start with tab and newline", b.entries()[0].message)
    }
}

class DiagReportTest {
    @Test fun reportHasFactsButNoSecrets() {
        val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
        val info = DiagInfo(
            appVersion = "0.3.1", build = "9", device = "Xiaomi 24117RN76G", android = "15 (API 35)", vendorOs = "HyperOS OS2.0",
            permissions = listOf("Поверх приложений" to true, "Микрофон" to false),
            services = listOf("Слух" to "выключен"),
            // A key put into a field by mistake must still leave masked.
            settings = listOf("Мозг" to "OpenRouter", "Ключ мозга" to key),
        )
        val log = listOf(DiagEntry(1, DiagEntry.Level.ERROR, "llm", "AUTH: HTTP 401 for $key"))
        val r = DiagReport.build(info, log, knownSecrets = listOf(key))
        assertFalse(r.contains(key))
        assertTrue(r.contains("0.3.1 (сборка 9)"))
        assertTrue(r.contains("[ ] Микрофон"))
        assertTrue(r.contains("ошибок 1"))
        assertTrue(r.contains("sk-o…***"))
    }
}
