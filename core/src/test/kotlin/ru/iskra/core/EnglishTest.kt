package ru.iskra.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import ru.iskra.core.i18n.I18n
import ru.iskra.core.i18n.Lang
import ru.iskra.core.i18n.Plural
import ru.iskra.core.i18n.tr
import ru.iskra.core.i18n.withLang
import ru.iskra.core.persona.Personas
import ru.iskra.core.skills.ReminderParser
import ru.iskra.core.skills.SkillNames
import ru.iskra.core.tools.CommandParser
import ru.iskra.core.tools.ToolNames
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.10: Iskra speaks English too — offline commands, reminders, persona, plurals. */
class EnglishTest {
    private val now = LocalDateTime.of(2026, 11, 4, 10, 0)

    private fun tool(text: String): CommandParser.Parsed.Tool = assertIs(CommandParser.parse(text, now), text)
    private fun CommandParser.Parsed.Tool.int(key: String) = args[key]!!.jsonPrimitive.int
    private fun CommandParser.Parsed.Tool.str(key: String) = args[key]!!.jsonPrimitive.content

    @Test fun languageFollowsThePhoneAndTrPicks() {
        assertEquals(Lang.RU, Lang.forLocale("ru"))
        assertEquals(Lang.RU, Lang.forLocale("kk"))
        assertEquals(Lang.EN, Lang.forLocale("en"))
        assertEquals(Lang.EN, Lang.forLocale("de"))
        assertEquals(Lang.EN, Lang.forLocale(null))
        assertEquals("Привет", tr("Привет", "Hi"))
        assertEquals("Hi", withLang(Lang.EN) { tr("Привет", "Hi") })
        assertEquals(Lang.RU, I18n.lang) // restored
    }

    @Test fun plurals() {
        assertEquals("минута", Plural.ru(21, "минута", "минуты", "минут"))
        assertEquals("минут", Plural.ru(12, "минута", "минуты", "минут"))
        assertEquals("минуты", Plural.ru(3, "минута", "минуты", "минут"))
        assertEquals("1 minute", withLang(Lang.EN) { Plural.count(1, "минута", "минуты", "минут", "minute", "minutes") })
        assertEquals("5 minutes", withLang(Lang.EN) { Plural.count(5, "минута", "минуты", "минут", "minute", "minutes") })
    }

    @Test fun timersAndAlarms() {
        assertEquals(300, tool("Set a timer for 5 minutes").int("seconds"))
        assertEquals(30, tool("timer 30 seconds").int("seconds"))
        assertEquals(1800, tool("Iskra, set a timer for half an hour, please").int("seconds"))
        assertEquals(600, tool("start a 10 minute timer").int("seconds"))
        assertEquals(5400, tool("timer for 1 hour and 30 minutes").int("seconds"))
        assertEquals(3600, tool("set a timer for an hour").int("seconds"))
        assertEquals(120, tool("set a timer for two minutes").int("seconds"))
        tool("Set an alarm for 7:30").let { assertEquals(ToolNames.SET_ALARM, it.name); assertEquals(7, it.int("hour")); assertEquals(30, it.int("minute")) }
        assertEquals(19, tool("wake me up at 7 pm").int("hour"))
        assertEquals(0, tool("alarm at 12 am").int("hour"))
        assertEquals(18, tool("set alarm for 6 in the evening").int("hour"))
        assertEquals(7, tool("Wake me up tomorrow at 7").int("hour"))
        assertEquals(30, tool("Can you wake me up at 6:30 tomorrow?").int("minute"))
        assertEquals(7, tool("set an alarm for tomorrow at 7 am").int("hour"))
    }

    @Test fun phoneCommands() {
        assertEquals(ToolNames.FLASHLIGHT, tool("turn on the flashlight").name)
        assertEquals(false, tool("flashlight off").args["on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("up", tool("volume up").str("direction"))
        assertEquals("home", tool("go home").str("action"))
        assertEquals("wifi", tool("open wi-fi settings").str("section"))
        assertEquals("YouTube".lowercase(), tool("open YouTube").str("name"))
        assertEquals("kotlin coroutines", tool("search for kotlin coroutines").str("query"))
        assertEquals("+15550100", tool("call +1 555 0100").str("number"))
        assertEquals(ToolNames.OPEN_CAMERA, tool("take a photo").name)
    }

    @Test fun memoryNotesAndWeather() {
        assertEquals("my sister's birthday is May 5", (CommandParser.parse("Remember: my sister's birthday is May 5", now) as CommandParser.Parsed.Remember).text)
        assertIs<CommandParser.Parsed.ListMemory>(CommandParser.parse("What do you know about me?", now))
        tool("Note: buy milk and Bread").let { assertEquals(SkillNames.ADD_NOTE, it.name); assertEquals("buy milk and Bread", it.str("text")) }
        assertEquals(SkillNames.LIST_NOTES, tool("what's in my notes").name)
        assertEquals("last", tool("delete the last note").str("which"))
        tool("What's the weather like in New York tomorrow?").let {
            assertEquals(SkillNames.WEATHER, it.name); assertEquals("tomorrow", it.str("day")); assertEquals("New York", it.str("city"))
        }
        assertEquals("today", tool("do I need an umbrella").str("day"))
        assertEquals(SkillNames.WEATHER, tool("how cold is it outside").name)
        // Unknown things still go to the model.
        assertNull(CommandParser.parse("tell me a story about a dragon", now))
    }

    @Test fun remindersInEnglish() {
        ReminderParser.parse("Remind me in 10 minutes to call mom", now)!!.let {
            assertEquals("call mom", it.text); assertEquals(10L, it.inMinutes)
        }
        ReminderParser.parse("remind me to turn off the stove in half an hour", now)!!.let {
            assertEquals("turn off the stove", it.text); assertEquals(30L, it.inMinutes)
        }
        ReminderParser.parse("Remind me at 6:30 pm to buy bread", now)!!.let {
            assertEquals("buy bread", it.text); assertEquals(now.withHour(18).withMinute(30), it.at)
        }
        ReminderParser.parse("remind me tomorrow at 9 about the meeting", now)!!.let {
            assertEquals("the meeting", it.text); assertEquals(now.plusDays(1).withHour(9), it.at)
        }
        ReminderParser.parse("remind me tomorrow morning to water the plants", now)!!.let {
            assertEquals("water the plants", it.text); assertEquals(now.plusDays(1).withHour(9), it.at)
        }
        ReminderParser.parse("remind me at 7 in the evening to take pills", now)!!.let {
            assertEquals("take pills", it.text); assertEquals(now.withHour(19), it.at)
        }
        // "at 7" said at 10:00 without am/pm → 19:00 today.
        assertEquals(now.withHour(19), ReminderParser.parse("remind me at 7 to call Dad", now)!!.at)
        assertEquals(now.plusDays(2).withHour(9), ReminderParser.parse("remind me the day after tomorrow to pay rent", now)!!.at)
        ReminderParser.parse("remind me in 2 days to send the report", now)!!.let { assertEquals(2880L, it.inMinutes) }
        assertEquals(now.withHour(20), ReminderParser.parse("remind me tonight to lock the door", now)!!.at)
        assertNull(ReminderParser.parse("remind me something", now))
        // The command parser routes it to the reminder tool.
        assertEquals(SkillNames.SET_REMINDER, tool("remind me in 5 minutes to stretch").name)
        // Russian still works the same.
        assertEquals("позвонить маме", ReminderParser.parse("напомни через 10 минут позвонить маме", now)!!.text)
    }

    @Test fun englishPersonaAndPrompt() {
        val en = withLang(Lang.EN) { Personas.ISKRA.localized() }
        assertEquals("Iskra", en.name)
        assertEquals("Искра", Personas.ISKRA.localized().name)
        assertTrue(en.boundaries.size == Personas.ISKRA.boundaries.size)
    }

    @Test fun englishTextsEverywhere() = withLang(Lang.EN) {
        val prompt = ru.iskra.core.agent.PromptComposer(java.time.ZoneId.of("UTC")).system(
            Personas.ISKRA.localized(), ru.iskra.core.persona.MoodState(), ru.iskra.core.persona.StyleProfile(), emptyList(), 0L, true)
        assertTrue(prompt.startsWith("You are Iskra, a digital cat-girl companion"), prompt.take(80))
        assertTrue("Thursday, 1 January 1970" in prompt || "Thursday" in prompt, prompt)
        assertTrue(!prompt.any { it in 'а'..'я' }, "Russian left in the English prompt")

        val f = ru.iskra.core.skills.Forecast(ru.iskra.core.skills.Place("London", 0.0, 0.0), 7.6, 61, 4.0,
            listOf(ru.iskra.core.skills.DayWeather(5.0, 9.0, 61, 70)))
        assertEquals("London: now +8°, light rain, wind 4 m/s. Today from +5° to +9°, chance of precipitation 70 %. Take an umbrella.",
            ru.iskra.core.skills.WeatherText.say(f, 0))
        assertEquals("Good morning! Outside it's +8°, light rain. Two reminders for today — I'll keep track.",
            ru.iskra.core.skills.Moments.morning("Mrr… morning. Shall I check today's weather?", ru.iskra.core.skills.Moments.weatherNow(f), 2))
        ru.iskra.core.skills.MomentLines.CHARGING.forEach { assertTrue(it.length <= 90 && it.none { c -> c in 'а'..'я' }, it) }
        ru.iskra.core.skills.PhraseBank.MORNING.forEach { assertTrue(it.none { c -> c in 'а'..'я' }, it) }
        assertEquals("Rarely (up to 3 times a day)", ru.iskra.core.skills.ProactiveFrequency.RARE.title)
        assertEquals("Battery", ru.iskra.core.power.EconomyMode.BATTERY.title)
        assertTrue(ru.iskra.core.llm.LlmException("x", ru.iskra.core.llm.LlmException.Kind.AUTH).userMessage().startsWith("The API key didn't work"))
        ru.iskra.core.device.Vendor.entries.forEach { v ->
            ru.iskra.core.device.VendorGuide.steps(v).forEach { assertTrue(it.text.none { c -> c in 'а'..'я' }, it.text) }
        }
        assertTrue(ru.iskra.core.skills.PocketSpark(kotlin.random.Random(1)).next().none { it in 'а'..'я' })
    }

    @Test fun englishWakeWordsFeelingsAndStyle() {
        val m = ru.iskra.core.voice.WakeWordMatcher(listOf("Iskra", "Spark"))
        assertEquals("set a timer for five minutes", m.match("Spark, please set a timer for five minutes")!!.remainder)
        assertEquals("what time is it", m.match("hey iskra what time is it")!!.remainder)
        assertNull(m.match("I have a spare key"))          // "spare" is not "spark"
        assertNull(m.match("we watched the sparks fly over the river"))
        assertTrue(ru.iskra.core.persona.Sentiment.estimate("thank you, you're awesome") > 0)
        assertTrue(ru.iskra.core.persona.Sentiment.estimate("shut up, you are useless") < 0)
        val p = ru.iskra.core.persona.StyleProfile()
        assertTrue(ru.iskra.core.persona.StyleFeedback.detect("please answer shorter", p)!!.verbosity < 0)
        assertTrue(ru.iskra.core.persona.StyleFeedback.detect("stop meowing", p)!!.catSpeech < 0)
        assertNull(ru.iskra.core.persona.StyleFeedback.detect("what a lovely day", p))
    }

    @Test fun sharedTextBecomesOrdinaryCommands() {
        val t = "Buy oat milk\nand bread"
        assertEquals(listOf(ru.iskra.core.skills.ShareAction.REMEMBER, ru.iskra.core.skills.ShareAction.NOTE, ru.iskra.core.skills.ShareAction.REMIND),
            ru.iskra.core.skills.SharedText.actions(t, brain = false))
        assertEquals(6, ru.iskra.core.skills.SharedText.actions(t, brain = true).size)
        assertEquals(listOf(ru.iskra.core.skills.ShareAction.EXPLAIN, ru.iskra.core.skills.ShareAction.SUMMARIZE, ru.iskra.core.skills.ShareAction.TRANSLATE),
            ru.iskra.core.skills.SharedText.actions("x".repeat(1200), brain = true))
        assertNull(ru.iskra.core.skills.SharedText.clean("   "))
        assertEquals(ru.iskra.core.skills.SharedText.MAX_CHARS, ru.iskra.core.skills.SharedText.clean("y".repeat(9000))!!.length)
        // Offline round trip in both languages: the messages are commands the parser understands.
        for (lang in listOf(Lang.RU, Lang.EN)) withLang(lang) {
            val note = CommandParser.parse(ru.iskra.core.skills.SharedText.message(ru.iskra.core.skills.ShareAction.NOTE, t), now)
            assertEquals("Buy oat milk and bread", assertIs<CommandParser.Parsed.Tool>(note, "$lang").args["text"]!!.jsonPrimitive.content)
            val mem = CommandParser.parse(ru.iskra.core.skills.SharedText.message(ru.iskra.core.skills.ShareAction.REMEMBER, t), now)
            assertEquals("Buy oat milk and bread", assertIs<CommandParser.Parsed.Remember>(mem, "$lang").text)
            val rem = CommandParser.parse(ru.iskra.core.skills.SharedText.message(ru.iskra.core.skills.ShareAction.REMIND, t), now)
            assertIs<CommandParser.Parsed.Tool>(rem, "$lang").let { assertEquals(SkillNames.SET_REMINDER, it.name); assertEquals(60, it.int("in_minutes")) }
        }
    }

    @Test fun updateCheckReadsTheLatestRelease() {
        val json = """{"tag_name":"v0.10.1","draft":false,"prerelease":false,"html_url":"https://github.com/Expireduser-source/Iskra/releases/tag/v0.10.1",
            "body":"## What's new\n- English everywhere\n- Free brain\n- Share to Iskra\n- More","assets":[
            {"name":"Iskra.apk.sha256","browser_download_url":"https://x/Iskra.apk.sha256"},{"name":"Iskra.apk","browser_download_url":"https://x/Iskra.apk"}]}"""
        val r = ru.iskra.core.update.UpdateCheck.parse(json)!!
        assertEquals("0.10.1", r.version); assertEquals("https://x/Iskra.apk", r.apkUrl)
        assertEquals("• English everywhere\n• Free brain\n• Share to Iskra", ru.iskra.core.update.UpdateCheck.shortNotes(r.notes))
        assertTrue(ru.iskra.core.update.UpdateCheck.isNewer("0.10.1", "0.10.0"))
        assertTrue(ru.iskra.core.update.UpdateCheck.isNewer("0.10.0", "0.9.3"))
        assertTrue(!ru.iskra.core.update.UpdateCheck.isNewer("0.10.0", "0.10.0-debug"))
        assertTrue(!ru.iskra.core.update.UpdateCheck.isNewer("0.9.9", "0.10.0"))
        assertNull(ru.iskra.core.update.UpdateCheck.parse("""{"tag_name":"build-40","prerelease":true}"""))
    }
}
