package ru.iskra.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.brain.CalculateTool
import ru.iskra.core.brain.CurrencyClient
import ru.iskra.core.brain.CurrencyTool
import ru.iskra.core.brain.CurrencyWords
import ru.iskra.core.brain.DateInfoTool
import ru.iskra.core.brain.ForgetTool
import ru.iskra.core.brain.SearchMemoryTool
import ru.iskra.core.brain.WikipediaClient
import ru.iskra.core.brain.WikipediaTool
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.withLang
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.tools.CommandParser
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeTest {
    private val noon = java.time.LocalDateTime.of(2026, 11, 5, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun calculateAndDates() = withLang(Lang.RU) {
        runBlocking {
            val calc = CalculateTool()
            assertEquals("(1200 − 150) × 0,87 = 913,5", calc.execute(buildJsonObject { put("expression", "(1200-150)*0.87") }).message)
            assertEquals("15% от 2300 = 345", calc.execute(buildJsonObject { put("expression", "15 процентов от 2300") }).message)
            assertFalse(calc.execute(buildJsonObject { put("expression", "1/0") }).ok)
            val dates = DateInfoTool({ noon }, { ZoneOffset.UTC })
            assertEquals("Сегодня 2026-11-05. 2027-01-01 — пятница, через 57 дней.", dates.execute(buildJsonObject { put("date", "2027-01-01") }).message)
            assertEquals("Сегодня 2026-11-05. 2027-02-03 — среда, через 90 дней.", dates.execute(buildJsonObject { put("add_days", 90) }).message)
        }
    }

    @Test
    fun memorySearchAndForget() = withLang(Lang.RU) {
        runBlocking {
            val m = MemoryManager(InMemoryMemoryStore())
            m.add("У владельца есть собака Рекс", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, noon)
            m.add("Владелец живёт в Москве", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 4, noon)
            m.add("Владелец любит кофе без сахара", MemoryKind.PREFERENCE, MemorySource.MODEL_PROPOSED, 3, noon)
            val found = SearchMemoryTool(m) { ZoneOffset.UTC }.execute(buildJsonObject { put("query", "собака") }).message
            assertTrue(found.contains("Рекс"), found)
            val forget = ForgetTool(m)
            assertFalse(forget.execute(buildJsonObject { put("query", "владелец") }).ok)   // matches all three: nothing deleted
            assertEquals(3, m.all().size)
            assertTrue(forget.execute(buildJsonObject { put("query", "живёт в Москве") }).ok)
            assertEquals(2, m.all().size)
        }
    }

    @Test
    fun wikipediaLookup() = withLang(Lang.RU) {
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"batchcomplete":true,"query":{"pages":[
                    {"pageid":2,"title":"Кот (значения)","index":2,"extract":"Кот может означать…","fullurl":"https://ru.wikipedia.org/wiki/Кот_(значения)"},
                    {"pageid":1,"title":"Кошка","index":1,"extract":"Ко́шка, или дома́шняя ко́шка, — домашнее животное, одно из наиболее популярных «животных-компаньонов». С точки зрения научной систематики…","fullurl":"https://ru.wikipedia.org/wiki/Кошка"}]}}"""))
                val tool = WikipediaTool(WikipediaClient(endpoint = { server.url("/$it/api.php").toString() }))
                val r = tool.execute(buildJsonObject { put("query", "кошка") })
                assertTrue(r.ok)
                assertTrue(r.message.startsWith("«Кошка»: Ко́шка"), r.message)
                assertTrue(r.message.contains("https://ru.wikipedia.org/wiki/Кошка"))
                val req = server.takeRequest()
                assertTrue(req.path!!.startsWith("/ru/api.php?action=query"), req.path)
                assertTrue(req.path!!.contains("gsrsearch=%D0%BA%D0%BE%D1%88%D0%BA%D0%B0"))
                assertTrue(req.getHeader("User-Agent")!!.startsWith("Iskra"))
            }
            assertNull(WikipediaClient.parse("""{"batchcomplete":true}"""))
        }
    }

    @Test
    fun currencyRates() = withLang(Lang.RU) {
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"result":"success","base_code":"USD","time_last_update_unix":1793836800,"rates":{"USD":1,"RUB":81.25,"EUR":0.92}}"""))
                val tool = CurrencyTool(CurrencyClient(base = server.url("/v6/latest/").toString(), clock = { noon })) { ZoneOffset.UTC }
                val r = tool.execute(buildJsonObject { put("from", "usd"); put("to", "RUB"); put("amount", 100) })
                assertEquals("100 USD = 8125 RUB (курс на 5 ноября)", r.message)
                // Cached: no second request within six hours.
                assertTrue(tool.execute(buildJsonObject { put("from", "USD"); put("to", "EUR") }).ok)
                assertEquals(1, server.requestCount)
                assertEquals("/v6/latest/USD", server.takeRequest().path)
            }
        }
    }

    @Test
    fun currencyCommands() {
        withLang(Lang.RU) {
            assertEquals("""{"from":"USD","to":"RUB","amount":1.0}""", CurrencyWords.parse("курс доллара").toString())
            assertEquals("""{"from":"EUR","to":"RUB","amount":1.0}""", CurrencyWords.parse("Сколько стоит евро?").toString())
            assertEquals("""{"from":"USD","to":"RUB","amount":100.0}""", CurrencyWords.parse("сто долларов в рублях").toString())
            assertEquals("""{"from":"CNY","to":"KZT","amount":1.0}""", CurrencyWords.parse("курс юаня к тенге").toString())
            assertEquals("""{"from":"RUB","to":"USD","amount":1.0}""", CurrencyWords.parse("курс рубля").toString())
            assertNull(CurrencyWords.parse("100 фунтов в кг"))
            assertNull(CurrencyWords.parse("какой курс лечения"))
            val p = CommandParser.parse("курс доллара") as CommandParser.Parsed.Tool
            assertEquals("currency", p.name)
        }
        withLang(Lang.EN) {
            assertEquals("""{"from":"EUR","to":"USD","amount":50.0}""", CurrencyWords.parse("how much is 50 euros in dollars").toString())
            assertEquals("""{"from":"GBP","to":"USD","amount":1.0}""", CurrencyWords.parse("what's the pound exchange rate").toString())
            assertEquals(JsonPrimitive("USD"), CurrencyWords.parse("convert 10 eur to usd")!!["to"])
        }
    }
}
