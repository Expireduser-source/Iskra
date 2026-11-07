package ru.iskra.core.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.iskra.core.i18n.Plural
import ru.iskra.core.i18n.tr

/**
 * A timer asked for without a time, for a dish whose usual time is known: «Сварю яйца всмятку — засеки, сколько нужно».
 * Set at once with any brain and without one — a phone model without tools answered it «Я не умею варить яйца всмятку!
 * Но могу предложить рецепт омлета» (Gemma 3 1B, ARM evaluation 0.16.0), and E2B asked back whether to set it. A dish
 * not listed here, or a request with a time or with something else asked as well, goes on as before.
 */
object DishTimer {
    private class Dish(val re: Regex, val minutes: Int, val label: Pair<String, String>, val line: Pair<String, String>)

    private val dishes = listOf(
        Dish(Regex("""(?iu)(?:яйц|яичк)\p{L}*[^.!?\n]{0,25}?всмятку|всмятку[^.!?\n]{0,25}?(?:яйц|яичк)|soft[- ]boil\p{L}*\s+(?:an\s+)?eggs?"""), 4,
            "Яйца всмятку" to "Soft-boiled eggs", "яйца всмятку будут в самый раз — считай от закипания" to "soft-boiled eggs will be just right — count from the boil"),
        Dish(Regex("""(?iu)(?<!\p{L})в\s+мешоч\p{L}*|medium[- ]boil\p{L}*\s+eggs?"""), 6,
            "Яйца в мешочек" to "Medium-boiled eggs", "как раз яйца в мешочек — считай от закипания" to "just right for medium-boiled eggs — count from the boil"),
        Dish(Regex("""(?iu)(?:яйц|яичк)\p{L}*[^.!?\n]{0,25}?вкрутую|вкрутую[^.!?\n]{0,25}?(?:яйц|яичк)|hard[- ]boil\p{L}*\s+(?:an\s+)?eggs?"""), 9,
            "Яйца вкрутую" to "Hard-boiled eggs", "яйца сварятся вкрутую — считай от закипания" to "the eggs will be hard-boiled — count from the boil"),
        Dish(Regex("""(?iu)(?<!\p{L})пельмен\p{L}*|(?<!\p{L})dumplings(?!\p{L})"""), 7,
            "Пельмени" to "Dumplings", "пельмени сварятся — считай от закипания" to "the dumplings will be done — count from the boil"),
        Dish(Regex("""(?iu)(?<!\p{L})вареник\p{L}*"""), 5,
            "Вареники" to "Vareniki", "вареники сварятся — считай от закипания" to "the vareniki will be done — count from the boil"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:макарон\p{L}*|спагетти|паст[уаы]|pasta|spaghetti)(?!\p{L})"""), 10,
            "Макароны" to "Pasta", "обычно столько варят макароны, но глянь и на пачку" to "that's the usual time for pasta, but check the packet too"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:гречк\p{L}*|гречнев\p{L}*|buckwheat)"""), 20,
            "Гречка" to "Buckwheat", "гречка сварится на тихом огне" to "the buckwheat will be done on a low heat"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:рис|риса|рису|rice)(?!\p{L})"""), 20,
            "Рис" to "Rice", "рис как раз сварится" to "the rice will be just done"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:сосиск\p{L}*|сардельк\p{L}*|sausages?|hot\s*dogs?)(?!\p{L})"""), 5,
            "Сосиски" to "Sausages", "сосиски будут готовы" to "the sausages will be ready"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:картошк\p{L}*|картофел\p{L}*|potato(?:es)?)(?!\p{L})"""), 20,
            "Картошка" to "Potatoes", "картошка сварится — проверь вилкой" to "the potatoes will be done — check with a fork"),
        Dish(Regex("""(?iu)(?<!\p{L})(?:чай|чая|чаю|tea)(?!\p{L})"""), 4,
            "Чай" to "Tea", "чай как раз заварится" to "the tea will be just brewed"),
    )

    /** A timer asked for: «засеки», «поставь таймер», "set a timer", "time it". */
    private val asked = Regex("""(?iu)(?<!\p{L})засеки(?:те)?(?!\p{L})|(?<!\p{L})(?:поставь|заведи|запусти|включи)(?:те)?(?!\p{L})[^.!?\n]{0,30}?(?<!\p{L})таймер|""" +
        """\b(?:set|start|put\s+on)\b[^.!?\n]{0,30}?\btimer\b|\btime\s+(?:it|them)\s+for\s+me\b""")

    /** A time of its own («засеки 5 минут на яйца»), or something else asked as well: not for this rule. */
    private val other = Regex("""(?iu)\d|(?<!\p{L})(?:секунд|минут|час|полчаса|second|minute|hour)\p{L}*|(?<!\p{L})(?:напомни|запиши|позвони|разбуди|будильник|напоминани|remind|alarm|call)\p{L}*""")

    /** The timer for [text], with her words for it, or null. */
    fun parse(text: String): Pair<CommandParser.Parsed.Tool, String>? {
        if (text.length > 120 || !asked.containsMatchIn(text) || other.containsMatchIn(text)) return null
        val dish = dishes.firstOrNull { it.re.containsMatchIn(text) } ?: return null
        val tool = CommandParser.Parsed.Tool(ToolNames.SET_TIMER, buildJsonObject {
            put("seconds", dish.minutes * 60)
            put("label", tr(dish.label.first, dish.label.second))
        })
        val time = Plural.count(dish.minutes.toLong(), "минуту", "минуты", "минут", "minute", "minutes")
        return tool to tr("Засекла $time — ${dish.line.first}.", "Timer set for $time — ${dish.line.second}.")
    }
}
