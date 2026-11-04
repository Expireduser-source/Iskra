package ru.iskra.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ru.iskra.core.skills.AddNoteTool
import ru.iskra.core.skills.CancelRemindersTool
import ru.iskra.core.skills.InMemoryNoteStore
import ru.iskra.core.skills.InMemoryReminderStore
import ru.iskra.core.skills.ListNotesTool
import ru.iskra.core.skills.ListRemindersTool
import ru.iskra.core.skills.PhraseBank
import ru.iskra.core.skills.Place
import ru.iskra.core.skills.ProactiveFrequency
import ru.iskra.core.skills.ProactivePolicy
import ru.iskra.core.skills.ProactiveState
import ru.iskra.core.skills.ProactiveTrigger
import ru.iskra.core.skills.Reminder
import ru.iskra.core.skills.ReminderParser
import ru.iskra.core.skills.ReminderScheduler
import ru.iskra.core.skills.SetReminderTool
import ru.iskra.core.skills.SkillNames
import ru.iskra.core.skills.WeatherClient
import ru.iskra.core.skills.WeatherText
import ru.iskra.core.skills.WeatherTool
import ru.iskra.core.tools.CommandParser
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderParserTest {
    private val now = LocalDateTime.of(2026, 11, 3, 14, 0)
    private fun p(s: String) = ReminderParser.parse(s, now)

    @Test fun relativeTimes() {
        assertEquals(ReminderParser.Result("позвонить маме", now.plusMinutes(10), 10), p("Напомни через 10 минут позвонить маме"))
        assertEquals(now.plusMinutes(60), p("напомни через час выключить плиту")!!.at)
        assertEquals(now.plusMinutes(30), p("напомни через полчаса")!!.at)
        assertEquals(now.plusMinutes(120), p("напомни мне через 2 часа что надо полить цветы")!!.at)
        assertEquals("надо полить цветы", p("напомни мне через 2 часа что надо полить цветы")!!.text)
        val tail = p("напомни купить хлеб через пять минут")!!
        assertEquals("купить хлеб", tail.text); assertEquals(5L, tail.inMinutes)
        assertEquals(now.plusMinutes(2), p("напомни через пару минут проверить чайник")!!.at)
    }

    @Test fun clockTimesAndDays() {
        assertEquals(ReminderParser.Result("купить хлеб", now.withHour(18).withMinute(30)), p("напомни в 18:30 купить хлеб"))
        assertEquals(now.plusDays(1).withHour(9).withMinute(0), p("напомни завтра в 9 про встречу")!!.at)
        assertEquals("про встречу", p("напомни завтра в 9 про встречу")!!.text)
        assertEquals(now.withHour(19).withMinute(0), p("напомни в 7 вечера позвонить")!!.at)
        assertEquals(now.withHour(19).withMinute(0), p("напомни в 7 часов вечера позвонить")!!.at)
        // "в 7" at 14:00 without "утра" → 19:00 today; "в 9 утра" → tomorrow.
        assertEquals(now.withHour(19).withMinute(0), p("напомни в 7 позвонить")!!.at)
        assertEquals(now.plusDays(1).withHour(9).withMinute(0), p("напомни в 9 утра зарядку")!!.at)
        assertEquals(now.plusDays(1).withHour(9).withMinute(0), p("напомни завтра утром полить цветы")!!.at)
        assertEquals("полить цветы", p("напомни завтра утром полить цветы")!!.text)
        assertEquals(now.withHour(19), p("напомни вечером вынести мусор")!!.at)
        assertEquals("Позвонить Ане", p("Искра, напомни в 16:15 Позвонить Ане")!!.text)
    }

    @Test fun rejectsWithoutTimeOrInThePast() {
        assertNull(p("напомни купить хлеб"))
        assertNull(p("напомни в 25:00 что-то"))
        assertNull(p("расскажи сказку"))
        assertEquals("напоминание", p("напомни через 3 минуты")!!.text)
    }
}

class SkillCommandsTest {
    private val now = LocalDateTime.of(2026, 11, 3, 14, 0)
    private fun tool(s: String) = CommandParser.parse(s, now) as? CommandParser.Parsed.Tool

    @Test fun notesRemindersWeatherOffline() {
        tool("Запиши заметку: Купить молоко и Хлеб")!!.let {
            assertEquals(SkillNames.ADD_NOTE, it.name); assertEquals("Купить молоко и Хлеб", it.args["text"]!!.jsonPrimitive.content)
        }
        assertEquals(SkillNames.LIST_NOTES, tool("что в заметках?")!!.name)
        assertEquals("all", tool("удали все заметки")!!.args["which"]!!.jsonPrimitive.content)
        tool("Искра, напомни через 10 минут позвонить маме")!!.let {
            assertEquals(SkillNames.SET_REMINDER, it.name)
            assertEquals(10, it.args["in_minutes"]!!.jsonPrimitive.content.toInt())
        }
        assertEquals("2026-11-03T18:30", tool("напомни в 18:30 купить хлеб")!!.args["at"]!!.jsonPrimitive.content)
        assertEquals(SkillNames.LIST_REMINDERS, tool("какие напоминания")!!.name)
        tool("какая погода завтра")!!.let { assertEquals("tomorrow", it.args["day"]!!.jsonPrimitive.content) }
        tool("погода в москве")!!.let { assertEquals("москве", it.args["city"]!!.jsonPrimitive.content) }
        assertEquals(SkillNames.WEATHER, tool("нужен ли зонт")!!.name)
        assertNull(CommandParser.parse("напомни мне про тот случай", now), "no time → goes to the model")
        // Old commands still work.
        assertEquals("set_timer", tool("таймер на 5 минут")!!.name)
    }
}

class NotesAndRemindersToolsTest {
    private var nowMs = 1_790_000_000_000L // 2026-09-21
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun notesAreKeptButNotPasswords() = runTest {
        val store = InMemoryNoteStore()
        val add = AddNoteTool(store) { nowMs }
        assertTrue(add.execute(buildJsonObject { put("text", JsonPrimitive("купить кофе")) }).ok)
        assertTrue(!add.execute(buildJsonObject { put("text", JsonPrimitive("мой пароль qwerty123")) }).ok)
        nowMs += 1000
        add.execute(buildJsonObject { put("text", JsonPrimitive("позвонить в банк")) })
        val list = ListNotesTool(store).execute(buildJsonObject { })
        assertTrue(list.message.indexOf("позвонить в банк") < list.message.indexOf("купить кофе"), "newest first: ${list.message}")
    }

    @Test fun reminderIsStoredScheduledListedAndCancelled() = runTest {
        val store = InMemoryReminderStore()
        val scheduled = mutableListOf<Reminder>(); val cancelled = mutableListOf<Long>()
        val scheduler = object : ReminderScheduler {
            override fun schedule(r: Reminder) { scheduled += r }
            override fun cancel(id: Long) { cancelled += id }
        }
        val set = SetReminderTool(store, scheduler, { nowMs }, { zone })
        val r = set.execute(buildJsonObject { put("text", JsonPrimitive("позвонить маме")); put("in_minutes", JsonPrimitive(10)) })
        assertTrue(r.ok && r.message.contains("через 10 мин"), r.message)
        assertEquals(nowMs + 600_000, scheduled.single().atMs)
        val at = set.execute(buildJsonObject { put("text", JsonPrimitive("встреча")); put("at", JsonPrimitive("2027-01-15T09:30")) })
        assertTrue(at.ok && at.message.contains("15.01 в 09:30"), at.message)
        assertTrue(!set.execute(buildJsonObject { put("text", JsonPrimitive("x")); put("at", JsonPrimitive("2020-01-01T10:00")) }).ok)
        val list = ListRemindersTool(store, { nowMs }, { zone }).execute(buildJsonObject { })
        assertTrue(list.message.indexOf("позвонить маме") < list.message.indexOf("встреча"))
        CancelRemindersTool(store, scheduler).execute(buildJsonObject { put("which", JsonPrimitive("next")) })
        assertEquals(listOf(scheduled[0].id), cancelled)
        assertEquals(1, store.pending().size)
    }
}

class WeatherTest {
    private val server = MockWebServer().apply { start() }
    @AfterTest fun stop() = server.shutdown()
    private val client = WeatherClient(forecastUrl = server.url("/v1/forecast").toString(), geoUrl = server.url("/v1/search").toString())

    private val forecastJson = """{"current":{"temperature_2m":-2.4,"weather_code":3,"wind_speed_10m":4.2},
        "daily":{"temperature_2m_max":[1.2,5.0],"temperature_2m_min":[-4.0,0.4],"weather_code":[3,63],"precipitation_probability_max":[10,80]}}"""

    @Test fun todayAndTomorrowInRussian() = runTest {
        server.enqueue(MockResponse().setBody(forecastJson))
        val f = client.forecast(Place("Москва", 55.75, 37.62))
        assertEquals("Москва: сейчас −2°, пасмурно, ветер 4 м/с. Сегодня от −4° до +1°.", WeatherText.say(f, 0))
        assertEquals("Москва, завтра: от 0° до +5°, дождь, вероятность осадков 80 %. Зонт пригодится.", WeatherText.say(f, 1))
        val url = server.takeRequest().requestUrl!!
        assertEquals("55.7500", url.queryParameter("latitude"))
        assertNull(url.queryParameter("apikey"))
    }

    @Test fun cityInPrepositionalCaseIsFoundAndCached() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))                                   // "Казани"
        server.enqueue(MockResponse().setBody("""{"results":[{"name":"Казань","latitude":55.79,"longitude":49.12}]}"""))
        server.enqueue(MockResponse().setBody(forecastJson))
        val tool = WeatherTool(client, homePlace = { null }, clock = { 0L })
        val r = tool.execute(buildJsonObject { put("city", JsonPrimitive("казани")) })
        assertTrue(r.ok && r.message.startsWith("Казань:"), r.message)
        assertEquals(listOf("Казани", "Казань", "Казана"), WeatherTool.cityCandidates("казани"))
        val noHome = WeatherTool(client, homePlace = { null }).execute(buildJsonObject { })
        assertTrue(!noHome.ok && noHome.message.contains("Настройки"))
    }
}

class ProactiveTest {
    private val morning = LocalDateTime.of(2026, 11, 3, 8, 30)
    private val ms = 1_800_000_000_000L

    @Test fun morningGreetingOnceAfterUnlockNotInQuietHours() {
        val p = ProactivePolicy(ProactiveFrequency.NORMAL)
        val st = ProactiveState()
        assertEquals(ProactiveTrigger.MORNING, p.decide(morning, ms, st, lastContactMs = ms - 10 * 3_600_000, screenOnSinceMs = ms - 30_000, random = Random(1)))
        val after = p.record(ProactiveTrigger.MORNING, morning, ms, st)
        assertNull(p.decide(morning.plusHours(2), ms + 2 * 3_600_000, after, ms - 10 * 3_600_000, ms + 2 * 3_600_000 - 10_000, Random(1)).takeIf { it == ProactiveTrigger.MORNING })
        assertNull(p.decide(morning.withHour(23).withMinute(30), ms, st, 0, ms - 10_000, Random(1)), "quiet hours")
        assertNull(p.decide(morning, ms, st, 0, screenOnSinceMs = null, random = Random(1)), "screen off")
        assertNull(ProactivePolicy(ProactiveFrequency.OFF).decide(morning, ms, st, 0, ms, Random(1)))
    }

    @Test fun rateLimitAndDailyCap() {
        val p = ProactivePolicy(ProactiveFrequency.RARE)
        var st = ProactiveState()
        val noon = morning.withHour(12)
        var t = ms
        var spoken = 0
        repeat(24 * 12) { // every 5 min for 24 h, screen always just unlocked, owner absent
            val now = noon.plusMinutes(5L * it)
            val trig = p.decide(now, t, st, lastContactMs = t - 4 * 3_600_000, screenOnSinceMs = t - 30_000, random = Random(it))
            if (trig != null) { spoken++; st = p.record(trig, now, t, st) }
            t += 5 * 60_000
        }
        assertTrue(spoken in 3..6, "spoken $spoken in a day and a half of checks") // ≤ 3 per calendar day
        assertTrue(ProactivePolicy(ProactiveFrequency.NORMAL, 22, 7).isQuiet(3) && !ProactivePolicy(ProactiveFrequency.NORMAL, 22, 7).isQuiet(12))
    }

    @Test fun morningGreetingWaitsForUnlockEvenAfterLongLockScreen() {
        val policy = ProactivePolicy(ProactiveFrequency.NORMAL)
        val state = ProactiveState()
        val screenOnAt = ms - 3 * 60_000
        assertNull(policy.decide(
            morning, ms, state, 0, screenOnAt, Random(1),
            unlockedSinceMs = null, screenLocked = true,
        ))
        assertEquals(ProactiveTrigger.MORNING, policy.decide(
            morning, ms, state, 0, screenOnAt, Random(1), unlockedSinceMs = ms - 15_000,
        ))
        // A stale screen-on timestamp must not be mistaken for a fresh unlock.
        assertNull(policy.decide(
            morning, ms, state, ms, screenOnAt, Random(1), unlockedSinceMs = null,
        ))
    }

    @Test fun microphoneAttentionDefersGreetingWithoutConsumingIt() {
        val policy = ProactivePolicy(ProactiveFrequency.NORMAL)
        val state = ProactiveState()
        assertNull(policy.decide(morning, ms, state, 0, ms, Random(1), listening = true))
        val trigger = policy.decide(morning, ms + 30_000, state, 0, ms, Random(1))
        assertEquals(ProactiveTrigger.MORNING, trigger)
        val after = policy.record(trigger!!, morning, ms + 30_000, state)
        assertEquals(1, after.countToday)
        assertNull(policy.decide(morning, ms + 60_000, after, 0, ms, Random(1)))
    }

    @Test fun lockAndMicrophoneBlockEveryTrigger() {
        val policy = ProactivePolicy(ProactiveFrequency.OFTEN)
        // Repeated evening/idle checks must not eventually pass their random gate while blocked.
        for (seed in 0..30) {
            assertNull(policy.decide(
                morning.withHour(21), ms, ProactiveState(), 0, ms - 3 * 3_600_000,
                Random(seed), screenLocked = true,
            ))
            assertNull(policy.decide(
                morning.withHour(21), ms, ProactiveState(), 0, ms - 3 * 3_600_000,
                Random(seed), listening = true,
            ))
        }
    }

    @Test fun phraseBankDoesNotRepeatRightAway() {
        val bank = PhraseBank(Random(3))
        val lines = (1..8).map { bank.proactive(ProactiveTrigger.IDLE) }
        lines.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        assertTrue(bank.reminder("полить цветы").contains("полить цветы"))
        assertNotNull(PhraseBank.MORNING.firstOrNull())
    }
}
