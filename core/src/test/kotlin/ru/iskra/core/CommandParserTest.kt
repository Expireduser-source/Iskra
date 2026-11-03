package ru.iskra.core

import kotlinx.serialization.json.jsonPrimitive
import ru.iskra.core.tools.CommandParser
import ru.iskra.core.tools.CommandParser.Parsed
import ru.iskra.core.tools.ToolNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CommandParserTest {
    private fun tool(s: String) = assertIs<Parsed.Tool>(CommandParser.parse(s), s)

    @Test fun alarm() {
        val p = tool("Поставь будильник на 7:30")
        assertEquals(ToolNames.SET_ALARM, p.name)
        assertEquals("7", p.args["hour"]!!.jsonPrimitive.content)
        assertEquals("30", p.args["minute"]!!.jsonPrimitive.content)
        assertEquals("19", tool("разбуди меня в 7 вечера").args["hour"]!!.jsonPrimitive.content)
        assertNull(CommandParser.parse("поставь будильник на 25:00"))
    }

    @Test fun timer() {
        assertEquals("300", tool("таймер на 5 минут").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("30", tool("поставь таймер на 30 секунд").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("1800", tool("таймер на полчаса").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("7200", tool("таймер 2 часа").args["seconds"]!!.jsonPrimitive.content)
        assertEquals("180", tool("Искра, таймер на три минуты!").args["seconds"]!!.jsonPrimitive.content)
    }

    @Test fun simpleCommands() {
        assertEquals(ToolNames.FLASHLIGHT, tool("включи фонарик").name)
        assertEquals("false", tool("выключи фонарик").args["on"]!!.jsonPrimitive.content)
        assertEquals("home", tool("домой").args["action"]!!.jsonPrimitive.content)
        assertEquals("wifi", tool("открой настройки wi-fi").args["section"]!!.jsonPrimitive.content)
        assertEquals("телеграм", tool("открой телеграм").args["name"]!!.jsonPrimitive.content)
        assertEquals("+79991234567", tool("позвони +7 999 123-45-67").args["number"]!!.jsonPrimitive.content)
        assertEquals(ToolNames.OPEN_CAMERA, tool("открой камеру").name)
    }

    @Test fun rememberKeepsCase() {
        val p = assertIs<Parsed.Remember>(CommandParser.parse("Запомни: мою сестру зовут Маша"))
        assertEquals("мою сестру зовут Маша", p.text)
        assertIs<Parsed.ListMemory>(CommandParser.parse("что ты помнишь?"))
    }

    @Test fun chitchatIsNotACommand() {
        assertNull(CommandParser.parse("как дела?"))
        assertNull(CommandParser.parse("расскажи анекдот про котов"))
    }
}
