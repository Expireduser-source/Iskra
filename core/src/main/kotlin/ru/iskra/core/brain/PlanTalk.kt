package ru.iskra.core.brain

import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.Stemmer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Plans the owner mentions in passing — «завтра у меня экзамен по физике», "I have a job interview on Friday" — kept as a
 * dated memory, so Iskra wishes luck on the day and asks afterwards how it went, with or without a model. Only clear cases
 * count: a coming day and a known kind of event in one sentence, not a question and not the past.
 */
object PlanTalk {
    /** What to wish: luck (exams, interviews), an easy time (doctors), a good time (concerts), a good trip, or that it goes well. */
    enum class Kind { TRIAL, HEALTH, FUN, TRIP, OTHER }

    /** [what] begins with the event in the nominative: «экзамен по физике», «визит к стоматологу», "physics exam". */
    data class Plan(val date: LocalDate, val what: String, val kind: Kind, val gender: Char, val ru: Boolean, val time: LocalTime? = null) {
        /** How memory keeps it: «Планы владельца на 7 ноября 2026: экзамен по физике». */
        val memoryText: String get() = if (ru) "Планы владельца на ${dateText(date, true)}: $what" else "The owner's plans for ${dateText(date, false)}: $what"
        /** After she asked how it went (or the owner told): «7 ноября 2026 у владельца было: экзамен по физике». */
        val doneText: String get() = if (ru) "${dateText(date, true)} у владельца было: $what" else "On ${dateText(date, false)} the owner had: $what"
    }

    /** What one message says: new plans, and the events it calls off («экзамен отменили»). */
    data class Heard(val plans: List<Plan> = emptyList(), val cancelled: List<String> = emptyList()) {
        fun isEmpty() = plans.isEmpty() && cancelled.isEmpty()
    }

    /**
     * A plan read back from memory; [done]: she already asked how it went, or the owner told; [outcome]: what the owner
     * answered to «как прошло?», in their words («Сдала на пятёрку!»).
     */
    data class Known(val item: MemoryItem, val plan: Plan, val done: Boolean, val outcome: String? = null)

    /** «… у владельца было: экзамен по анатомии (владелец: «Сдала на пятёрку!»)» — the answer kept with the plan. */
    private val withOutcome = Regex("""^(.+?) \((?:владелец|the owner): «(.+)»\)$""")

    /** The done plan with the owner's answer: «7 ноября 2026 у владельца было: экзамен (владелец: «Сдала на пятёрку!»)». */
    fun withOutcomeText(k: Known, said: String): String {
        val words = said.trim().replace(Regex("""\s+"""), " ").replace("«", "\"").replace("»", "\"").let { if (it.length > 90) it.take(88).trimEnd() + "…" else it }
        return k.plan.doneText + if (k.plan.ru) " (владелец: «$words»)" else " (the owner: «$words»)"
    }

    private class Event(forms: String, val lemma: String, val kind: Kind, val gender: Char = 'm') {
        val re = Regex("""(?iu)(?<![\p{L}\p{N}])(?:$forms)(?![\p{L}\p{N}])""")
    }

    private val ruEvents = listOf(
        Event("экзамены|экзаменов|экзаменам|экзаменах", "экзамены", Kind.TRIAL, 'p'),
        Event("экзамен|экзамена|экзамене|экзамену|экзаменом", "экзамен", Kind.TRIAL),
        Event("зач[её]т|зач[её]та|зач[её]те|зач[её]ту", "зачёт", Kind.TRIAL),
        Event("пересдача|пересдачу|пересдаче|пересдачи", "пересдача", Kind.TRIAL, 'f'),
        Event("контрольная|контрольную|контрольной|контрош(?:ка|ку|ке)", "контрольная", Kind.TRIAL, 'f'),
        Event("самостоятельная|самостоятельную|самостоятельной", "самостоятельная", Kind.TRIAL, 'f'),
        Event("олимпиада|олимпиаду|олимпиаде|олимпиады", "олимпиада", Kind.TRIAL, 'f'),
        Event("тест|теста|тесте|тесту", "тест", Kind.TRIAL),
        Event("первый\\s+рабочий\\s+день|первого\\s+рабочего\\s+дня", "первый рабочий день", Kind.TRIAL),
        Event("егэ", "ЕГЭ", Kind.TRIAL), Event("огэ", "ОГЭ", Kind.TRIAL),
        Event("сессия|сессию|сессии", "сессия", Kind.TRIAL, 'f'),
        Event("собеседование|собеседования|собеседовании|собеседованию|собес|собеса|собесе", "собеседование", Kind.TRIAL, 'n'),
        Event("интервью", "интервью", Kind.TRIAL, 'n'),
        Event("защита|защиту|защите|защиты", "защита", Kind.TRIAL, 'f'),
        Event("презентация|презентацию|презентации", "презентация", Kind.TRIAL, 'f'),
        Event("выступление|выступления|выступлении", "выступление", Kind.TRIAL, 'n'),
        Event("доклад|доклада|докладе", "доклад", Kind.TRIAL),
        Event("соревнования|соревнованиях|соревнований|соревнование", "соревнования", Kind.TRIAL, 'p'),
        Event("турнир|турнира|турнире", "турнир", Kind.TRIAL),
        Event("операция|операцию|операции", "операция", Kind.HEALTH, 'f'),
        Event("анализы|анализов|анализах", "анализы", Kind.HEALTH, 'p'),
        Event("обследование|обследования|обследовании", "обследование", Kind.HEALTH, 'n'),
        Event("прививка|прививку|прививке", "прививка", Kind.HEALTH, 'f'),
        Event("узи", "УЗИ", Kind.HEALTH, 'n'), Event("мрт", "МРТ", Kind.HEALTH, 'n'),
        Event("при[её]м|при[её]ма|при[её]ме", "приём", Kind.HEALTH),
        Event("визит|визита", "визит", Kind.HEALTH),
        Event("концерт|концерта|концерте", "концерт", Kind.FUN),
        Event("свидание|свидания|свидании", "свидание", Kind.FUN, 'n'),
        Event("вечеринка|вечеринку|вечеринке|тусовка|тусовку|тусовке|туса|тусу|тусе", "вечеринка", Kind.FUN, 'f'),
        Event("свадьба|свадьбу|свадьбе", "свадьба", Kind.FUN, 'f'),
        Event("корпоратив|корпоратива|корпоративе", "корпоратив", Kind.FUN),
        Event("праздник|праздника|празднике", "праздник", Kind.FUN),
        Event("годовщина|годовщину|годовщине", "годовщина", Kind.FUN, 'f'),
        Event("выпускной|выпускного|выпускном", "выпускной", Kind.FUN),
        Event("матч|матча|матче", "матч", Kind.FUN),
        Event("фестиваль|фестиваля|фестивале", "фестиваль", Kind.FUN),
        Event("выставка|выставку|выставке", "выставка", Kind.FUN, 'f'),
        Event("спектакль|спектакля|спектакле", "спектакль", Kind.FUN),
        Event("поход|похода|походе", "поход", Kind.FUN),
        Event("день\\s+рождения|дня\\s+рождения|дне\\s+рождения|днюха|днюху|днюхе|днюхи", "день рождения", Kind.FUN),
        Event("поездка|поездку|поездке", "поездка", Kind.TRIP, 'f'),
        Event("путешествие|путешествия|путешествии", "путешествие", Kind.TRIP, 'n'),
        Event("отпуск|отпуска|отпуске", "отпуск", Kind.TRIP),
        Event("командировка|командировку|командировке", "командировка", Kind.TRIP, 'f'),
        Event("перел[её]т|перел[её]та|рейс|рейса", "перелёт", Kind.TRIP),
        Event("переезд|переезда|переезде", "переезд", Kind.OTHER),
        Event("встреча|встречу|встрече", "встреча", Kind.OTHER, 'f'),
        Event("совещание|совещания|совещании", "совещание", Kind.OTHER, 'n'),
        Event("планёрка|планерка|планёрку|планерку|планёрке|планерке", "планёрка", Kind.OTHER, 'f'),
        Event("созвон|созвона|созвоне", "созвон", Kind.OTHER),
        Event("тренировка|тренировку|тренировке", "тренировка", Kind.OTHER, 'f'),
    )

    /** «к стоматологу», «у врача» → «визит к стоматологу». */
    private val doctor = Regex("""(?iu)(?<!\p{L})(?:к|ко|у)\s+(врач[уа]|доктор[уа]|стоматолог[уа]|зубно(?:му|го)|терапевт[уа]|окулист[уа]|хирург[уа]|невролог[уа]|кардиолог[уа]|гинеколог[уа]|лор[уа]|педиатр[уа]|психолог[уа]|психотерапевт[уа]|дерматолог[уа]|ортодонт[уа]|эндокринолог[уа]|офтальмолог[уа]|ветеринар[уа]|уролог[уа]|травматолог[уа])(?!\p{L})""")

    /** «лечу в Сочи», «еду на дачу» → «поездка в Сочи». */
    private val tripVerb = Regex("""(?iu)(?<!\p{L})(?:лечу|улетаю|еду|уезжаю|поеду|полечу|едем|летим|поедем|полетим|уезжаем|улетаем|отправляюсь|отправляемся)\s+(в|во|на)\s+([\p{L}-]+(?:\s+[\p{L}-]+)?)""")
    private val tripPlaces = setOf("море", "дачу", "юг", "природу", "рыбалку", "озеро", "курорт", "горы", "моря")

    /** «иду в кино» → «поход в кино». */
    private val outing = Regex("""(?iu)(?<!\p{L})(?:в|во)\s+(кино|кинотеатр|театр|цирк|музей|аквапарк|зоопарк|планетарий)(?!\p{L})""")

    private val enEvents = listOf(
        Event("exams|finals|midterms", "", Kind.TRIAL, 'p'),
        Event("exam|final|midterm|test|quiz|driving\\s+test", "", Kind.TRIAL),
        Event("(?:job\\s+)?interview", "", Kind.TRIAL),
        Event("presentation|defen[cs]e|audition|competition|tournament|recital|speech|pitch", "", Kind.TRIAL),
        Event("(?:doctor'?s?|dentist'?s?|medical|hospital)\\s+appointment|appointment|surgery|operation|check-?up|blood\\s+tests?|vaccination|scan", "", Kind.HEALTH),
        Event("date|concert|party|wedding|gig|show|festival|game|match|prom|graduation|birthday\\s+party", "", Kind.FUN),
        Event("(?:business\\s+)?trip|flight|vacation|holidays?|journey", "", Kind.TRIP),
        Event("meeting|move", "", Kind.OTHER),
    )
    private val enDoctor = Regex("""(?iu)(?<!\p{L})(?:the\s+)?(dentist|doctor)(?!\p{L})""")

    // ---- Days -------------------------------------------------------------------------------------------------

    private val weekdaysRu = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
    private val weekdaysEn = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val monthsGen = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthsEn = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private const val MONTH_RU = "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря"
    private const val MONTH_EN = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"

    private val dayPatterns: List<Pair<Regex, (MatchResult, LocalDate) -> LocalDate?>> = listOf(
        Regex("""(?iu)(?<!\p{L})послезавтра(?!\p{L})|(?<!\p{L})(?:the\s+)?day\s+after\s+tomorrow(?!\p{L})""") to { _, t -> t.plusDays(2) },
        Regex("""(?iu)(?<!\p{L})(?:завтра|tomorrow)(?!\p{L})""") to { _, t -> t.plusDays(1) },
        Regex("""(?iu)(?<!\p{L})(?:сегодня|today|tonight|this\s+(?:morning|afternoon|evening))(?!\p{L})""") to { _, t -> t },
        Regex("""(?iu)(?<!\p{L})(?:в|во|на|к)\s+(?:эт(?:от|у|о)\s+|следующ(?:ий|ую|ее)\s+|ближайш(?:ий|ую|ее)\s+)?(понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)(?!\p{L})""") to { m, t ->
            next(t, weekdaysRu.indexOf(m.groupValues[1].lowercase()))
        },
        Regex("""(?iu)(?<!\p{L})(?:(?:on|this|next|coming)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?!\p{L})""") to { m, t ->
            next(t, weekdaysEn.indexOf(m.groupValues[1].lowercase()))
        },
        Regex("""(?iu)(?<!\p{L})(?:на|в)\s+(?:эти\s+|ближайшие\s+)?выходн(?:ых|ые)(?!\p{L})|(?<!\p{L})(?:this|next|on\s+the|at\s+the)\s+weekend(?!\p{L})""") to { _, t ->
            if (t.dayOfWeek == DayOfWeek.SUNDAY || t.dayOfWeek == DayOfWeek.SATURDAY) t else next(t, 5)
        },
        Regex("""(?iu)(?<!\p{L})через\s+(\d{1,2}|пару|неделю)(?:\s+(дн(?:я|ей)|день|недел[июь]))?(?!\p{L})""") to { m, t ->
            val n = when (m.groupValues[1].lowercase()) { "пару" -> 2; "неделю" -> 1; else -> m.groupValues[1].toInt() }
            val weeks = m.groupValues[1].lowercase() == "неделю" || m.groupValues[2].lowercase().startsWith("недел")
            t.plusDays(if (weeks) n * 7L else n.toLong())
        },
        Regex("""(?iu)(?<!\p{L})in\s+(\d{1,2}|a|one|two|three|four|five|six|a\s+couple\s+of)\s+(days?|weeks?)(?!\p{L})""") to { m, t ->
            val n = when (val w = m.groupValues[1].lowercase()) { "a", "one" -> 1; "two", "a couple of" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6; else -> w.toIntOrNull() ?: 1 }
            t.plusDays(if (m.groupValues[2].lowercase().startsWith("week")) n * 7L else n.toLong())
        },
        Regex("""(?iu)(?<!\p{N})(\d{1,2})(?:-?го)?\s+($MONTH_RU)(?:\s+(\d{4}))?(?!\p{L})""") to { m, t ->
            upcoming(t, monthsGen.indexOf(m.groupValues[2].lowercase()) + 1, m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull())
        },
        Regex("""(?iu)(?<!\p{L})($MONTH_EN)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?!\p{N})""") to { m, t ->
            upcoming(t, enMonth(m.groupValues[1]), m.groupValues[2].toInt(), null)
        },
        Regex("""(?iu)(?<!\p{N})(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_EN)(?!\p{L})""") to { m, t ->
            upcoming(t, enMonth(m.groupValues[2]), m.groupValues[1].toInt(), null)
        },
    )

    private fun enMonth(s: String) = monthsEn.indexOfFirst { it.lowercase().startsWith(s.lowercase().take(3)) } + 1

    /** The next [index] weekday (0 = Monday) after [today]: «в пятницу» said on a Friday means the next one. */
    private fun next(today: LocalDate, index: Int): LocalDate? {
        if (index < 0) return null
        val diff = (index - (today.dayOfWeek.value - 1) + 7) % 7
        return today.plusDays(if (diff == 0) 7L else diff.toLong())
    }

    /** A day and month from today on, at most ~4 months ahead (a date further back is the past, not a plan). */
    private fun upcoming(today: LocalDate, month: Int, day: Int, year: Int?): LocalDate? {
        if (month !in 1..12) return null
        val d = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return null
        val date = if (year == null && d.isBefore(today)) runCatching { d.plusYears(1) }.getOrNull() ?: return null else d
        return date.takeIf { !it.isBefore(today) && ChronoUnit.DAYS.between(today, it) <= 125 }
    }

    /** «иду к врачу», «еду на собеседование» with no day named: today. */
    // «сейчас сессия», «сейчас отпуск» is how things are, not a plan for today: only going somewhere counts.
    private val goingNow = Regex("""(?iu)^(?:я\s+)?(?:уже\s+|сейчас\s+)?(?:иду|еду|лечу|бегу|направляюсь)(?!\p{L})""")

    /** The coming day a sentence names («завтра», «в пятницу», «12 ноября», "on Friday") and where it is said, or null. */
    fun day(text: String, today: LocalDate): Pair<LocalDate, IntRange>? {
        val digits = NumberWords.digitize(text)
        return dayPatterns.mapNotNull { (re, f) -> re.find(digits)?.let { m -> f(m, today)?.let { it to m.range } } }
            .minByOrNull { it.second.first }
            ?: goingNow.find(digits)?.let { today to it.range }
    }

    private val timeRu = Regex("""(?iu)(?<![\p{L}\p{N}])(?:в|к)\s+(\d{1,2})(?:[:.](\d{2}))?(?:\s*(утра|дня|вечера|ночи))?(?![\p{L}\p{N}])|(?<!\p{L})(?:в|к)\s+полдень(?!\p{L})""")
    private val timeEn = Regex("""(?iu)(?<![\p{L}\p{N}])at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?![\p{L}\p{N}])|(?<!\p{L})at\s+noon(?!\p{L})""")

    /** «в 10», «в 10:30», «в 7 вечера», «к полудню», "at 10 am": when the event starts, if said. «в 5» alone is 17:00. */
    fun time(text: String): LocalTime? {
        val t = NumberWords.digitize(text)
        val m = timeRu.find(t) ?: timeEn.find(t) ?: return null
        if (m.value.contains("полдень") || m.value.contains("noon")) return LocalTime.NOON
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        when (m.groupValues[3].lowercase()) {
            "вечера", "pm", "p.m." -> if (h < 12) h += 12
            "дня" -> if (h < 12 && h <= 6) h += 12
            "утра", "ночи", "am", "a.m." -> if (h == 12) h = 0
            else -> if (h in 1..7) h += 12
        }
        return LocalTime.of(h, min)
    }

    /** «7 ноября 2026» / "November 7, 2026". */
    fun dateText(d: LocalDate, ru: Boolean): String = if (ru) "${d.dayOfMonth} ${monthsGen[d.monthValue - 1]} ${d.year}" else "${monthsEn[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"

    /** «завтра», «в пятницу», «12 ноября» — how to say a coming day. */
    fun whenText(d: LocalDate, today: LocalDate, ru: Boolean): String {
        val days = ChronoUnit.DAYS.between(today, d)
        val wd = d.dayOfWeek.value - 1
        return when {
            days == 0L -> if (ru) "сегодня" else "today"
            days == 1L -> if (ru) "завтра" else "tomorrow"
            days == 2L -> if (ru) "послезавтра" else "the day after tomorrow"
            days in 3..6 -> if (ru) (if (wd == 1) "во " else "в ") + weekdaysRu[wd] else "on " + weekdaysEn[wd].replaceFirstChar { it.uppercase() }
            else -> if (ru) "${d.dayOfMonth} ${monthsGen[d.monthValue - 1]}" else "on ${monthsEn[d.monthValue - 1]} ${d.dayOfMonth}"
        }
    }

    // ---- Hearing ----------------------------------------------------------------------------------------------

    private val questionRu = Regex("""(?iu)^(?:а\s+)?(?:когда|во\s+сколько|какого\s+числа|в\s+какой|какой|какая|какие|что\s+у\s+меня|у\s+меня\s+ли|разве|правда\s+ли)(?!\p{L})""")
    private val questionEn = Regex("""(?iu)^(?:when|what|which|how)(?!\p{L})""")
    private val auxEn = Regex("""(?iu)^(?:do|does|did|is|are|am|was|were|will|have|has|can|could|should)(?!\p{L})""")
    private val imperative = Regex("""(?iu)^(?:искра[,\s]+)?(?:напомни|поставь|разбуди|запиши|добавь|создай|заведи|remind|set|wake|add|create|schedule)(?!\p{L})""")
    private val pastRu = Regex("""(?iu)(?<!\p{L})(?:вчера|позавчера|был|была|было|были|прош[её]л|прошла|прошло|прошли|сдал|сдала|сдали|ходил|ходила|ходили|ездил|ездила|ездили|летал|летала|закончил\p{L}*|завалил\p{L}*|провалил\p{L}*)(?!\p{L})""")
    private val pastEn = Regex("""(?iu)(?<!\p{L})(?:yesterday|was|were|had|went|passed|failed|did|finished)(?!\p{L})""")
    private val cancelRu = Regex("""(?iu)(?<!\p{L})(?:отменил\p{L}*|отмен[её]н\p{L}*|перенесл\p{L}*|перенес[её]н\p{L}*|перенос\p{L}*)(?!\p{L})""")
    /** «завтра не будет экзамена» — right before the event, so «не будет времени, завтра экзамен» stays a plan. */
    private val notRu = Regex("""(?iu)(?<!\p{L})не\s+будет\s+(?:никак\p{L}+\s+)?$""")
    private val cancelEn = Regex("""(?iu)(?<!\p{L})(?:cancel(?:l)?ed|called\s+off|postponed|moved|rescheduled|pushed\s+back|is\s+off|got\s+cancel(?:l)?ed)(?!\p{L})""")
    private val movedRu = Regex("""(?iu)(?<!\p{L})(?:перенесл\p{L}*|перенес[её]н\p{L}*|перенос\p{L}*)(?!\p{L})""")
    private val movedEn = Regex("""(?iu)(?<!\p{L})(?:postponed|moved|rescheduled|pushed\s+back)(?!\p{L})""")
    /** Someone else's event: «брат завтра сдаёт экзамен» → «экзамен у брата»; «он», «она» → not the owner's plan. */
    private val otherRu = Regex("""(?iu)^(?:а\s+)?(?:мой\s+|моя\s+|у\s+)?(он|она|они|него|неё|нее|них|тебя|брат|брата|сестра|сестры|мама|мамы|папа|папы|отец|отца|мать|матери|сын|сына|дочь|дочка|дочки|дочери|друг|друга|подруга|подруги|муж|мужа|жена|жены|парень|парня|девушка|девушки|бабушка|бабушки|дедушка|дедушки)(?!\p{L})""")
    private val owners = mapOf("брат" to "брата", "сестра" to "сестры", "мама" to "мамы", "папа" to "папы", "отец" to "отца", "мать" to "мамы",
        "матери" to "мамы", "сын" to "сына", "дочь" to "дочки", "дочка" to "дочки", "дочери" to "дочки", "друг" to "друга", "подруга" to "подруги",
        "муж" to "мужа", "жена" to "жены", "парень" to "парня", "девушка" to "девушки", "бабушка" to "бабушки", "дедушка" to "дедушки")
    private val pronouns = setOf("он", "она", "они", "него", "неё", "нее", "них", "тебя")
    private val uOther = Regex("""(?iu)(?<!\p{L})у\s+(брата|сестры|мамы|папы|отца|матери|сына|дочки|дочери|друга|подруги|мужа|жены|парня|девушки|бабушки|дедушки|него|неё|нее|них|тебя)(?!\p{L})""")
    private val otherEn = Regex("""(?iu)^(?:he|she|they|his|her|their|you|your|my\s+(?:mom|mum|mother|dad|father|sister|brother|son|daughter|wife|husband|friend|boyfriend|girlfriend))(?!\p{L})""")
    private val ownBirthday = Regex("""(?iu)(?:(?<!\p{L})мой|у\s+меня)\s+(?:\p{L}+\s+){0,2}(?:день\s+рождения|днюха)|(?<!\p{L})my\s+(?:\p{L}+\s+)?birthday""")

    /** Plans and called-off events in [text] (each sentence, or part of one after «, а», on its own). */
    fun hear(text: String, today: LocalDate): Heard {
        val plans = ArrayList<Plan>()
        val cancelled = ArrayList<String>()
        val parts = text.trim().split(Regex("""(?<=[.!?…])\s+|\n+|;\s*|,\s*(?=(?:а|но|and|but)\s)""")).map { it.trim().removePrefix("а ").removePrefix("но ").trim() }
        for (s in parts.filter { it.isNotEmpty() }) {
            val ru = s.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' }
            if (imperative.containsMatchIn(s) || ownBirthday.containsMatchIn(s)) continue   // the owner's own birthday is a fact, not a plan
            if (if (ru) questionRu.containsMatchIn(s) else questionEn.containsMatchIn(s) || (auxEn.containsMatchIn(s) && s.endsWith("?"))) continue
            val event = (if (ru) eventRu(s) else eventEn(s)) ?: continue
            val (what, kind, gender, at) = event
            val day = day(s, today)
            val cancel = (if (ru) cancelRu else cancelEn).findAll(s).firstOrNull { kotlin.math.abs(it.range.first - at.first) <= 40 }
            if (cancel != null || (ru && notRu.containsMatchIn(s.substring(0, at.first)))) {
                cancelled += head(what)
                val moved = cancel != null && (if (ru) movedRu else movedEn).containsMatchIn(s)
                if (moved && day != null && day.second.first > cancel!!.range.first) plans += Plan(day.first, what, kind, gender, ru, time(s))
                continue
            }
            if (day == null || (if (ru) pastRu else pastEn).containsMatchIn(s)) continue
            if (day.second.first > at.last + 60) continue // a day said far after the event is about something else
            if (!ru) {
                if (otherEn.containsMatchIn(s)) continue
                plans += Plan(day.first, what, kind, gender, false, time(s))
                continue
            }
            val whose = (otherRu.find(s) ?: uOther.find(s.substring(0, at.first)))?.groupValues?.get(1)?.lowercase()
            if (whose != null && whose in pronouns) continue
            val full = if (whose == null || what.contains(" у ")) what else "$what у ${owners[whose] ?: whose}"
            if (full.startsWith("день рождения у ")) continue // someone's birthday is kept as a yearly date (MemoryTalk), not a one-off plan
            plans += Plan(day.first, full, kind, gender, true, time(s))
        }
        return Heard(plans.distinctBy { it.what.lowercase() to it.date }, cancelled.distinct())
    }

    private data class Found(val what: String, val kind: Kind, val gender: Char, val at: IntRange)

    private fun eventRu(s: String): Found? {
        val candidates = ArrayList<Found>()
        for (e in ruEvents) e.re.find(s)?.let { m ->
            var what = e.lemma
            var kind = e.kind
            val rest = complementRu(s.substring(m.range.last + 1))
            // «поход к врачу» is a visit, not a walk
            if (e.lemma == "поход" || e.lemma == "визит" || e.lemma == "приём") doctor.find(s.substring(m.range.last + 1).take(30))?.let { d ->
                if (d.range.first <= 2) { kind = Kind.HEALTH; what = if (e.lemma == "приём") "приём" else "визит" }
            }
            if (e.lemma == "поход" && rest.isEmpty() && what == "поход") return@let // a bare «поход» is too vague
            // «день рождения к другу» reads oddly: only a visit goes «к» someone.
            val tail = if (rest.startsWith("к ") && what != "визит") "" else rest
            candidates += Found(listOf(what, tail).filter { it.isNotEmpty() }.joinToString(" "), kind, e.gender, m.range)
        }
        doctor.find(s)?.let { m -> candidates += Found("визит к ${dative(m.groupValues[1].lowercase())}", Kind.HEALTH, 'm', m.range) }
        tripVerb.find(s)?.let { m ->
            val words = m.groupValues[2].split(Regex("""\s+"""))
            val place = if (words[0].lowercase() in tripPlaces) words[0].lowercase()
                else words.takeWhile { it.first().isUpperCase() }.joinToString(" ")
            if (place.isNotEmpty()) candidates += Found("поездка ${m.groupValues[1].lowercase()} $place", Kind.TRIP, 'f', m.range)
        }
        outing.find(s)?.let { m -> candidates += Found("поход в ${m.groupValues[1].lowercase()}", Kind.FUN, 'm', m.range) }
        return candidates.minByOrNull { it.at.first }
    }

    private fun eventEn(s: String): Found? {
        val candidates = ArrayList<Found>()
        for (e in enEvents) for (m in e.re.findAll(s)) {
            // A noun, not a verb: "my exam", "a job interview", "exam tomorrow" — not "I'll test it".
            val before = s.substring(0, m.range.first)
            val det = Regex("""(?iu)(?:^|\s)(?:a|an|my|the|our|big|final)\s+((?:[\p{L}-]+\s+){0,2})$""").find(before)
            if (det == null && m.range.first > 0) continue
            val mods = det?.groupValues?.get(1)?.trim()?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() && it.lowercase() !in setOf("very", "really", "so", "this", "that") }.orEmpty()
            val rest = complementEn(s.substring(m.range.last + 1))
            val what = (mods + m.value.lowercase() + listOfNotNull(rest.ifEmpty { null })).joinToString(" ")
            candidates += Found(what, e.kind, e.gender, m.range)
            break
        }
        enDoctor.find(s)?.let { m -> if (Regex("""(?iu)(?:going|go|have|got)\s+(?:to\s+)?(?:see\s+)?(?:the\s+)?$""").containsMatchIn(s.substring(0, m.range.first)))
            candidates += Found("${m.groupValues[1].lowercase()} visit", Kind.HEALTH, 'm', m.range) }
        Regex("""(?iu)(?<!\p{L})(?:flying|going|travel(?:l)?ing|heading|driving)\s+to\s+(\p{Lu}[\p{L}-]+(?:\s+\p{Lu}[\p{L}-]+)?)""").find(s)?.let { m ->
            // (?i) lets \p{Lu} match any letter: keep the capitalised words only ("Paris", not "Paris on").
            val place = m.groupValues[1].split(Regex("""\s+""")).takeWhile { it.first().isUpperCase() }.joinToString(" ")
            if (place.isNotEmpty()) candidates += Found("trip to $place", Kind.TRIP, 'n', m.range)
        }
        return candidates.minByOrNull { it.at.first }
    }

    private fun dative(w: String) = when {
        w.endsWith("ого") -> w.dropLast(3) + "ому"
        w.endsWith("а") -> w.dropLast(1) + "у"
        else -> w
    }

    private val ruPreps = setOf("по", "в", "во", "на", "с", "со", "у", "к", "ко", "для", "от", "из", "про", "за")
    private val ruStop = setOf("и", "а", "но", "или", "поэтому", "потому", "что", "чтобы", "так", "как", "я", "мне", "меня", "мы", "нас", "очень",
        "уже", "ведь", "же", "бы", "ли", "не", "ни", "завтра", "сегодня", "послезавтра", "утром", "днём", "днем", "вечером", "ночью", "скоро",
        "наконец", "опять", "снова", "будет", "будут", "есть", "тоже", "ещё", "еще", "всё", "все", "вот", "там", "тут", "это", "этот", "эта",
        "его", "её", "ее", "их", "мой", "моя", "моё", "мое", "мои", "свой", "своя", "который", "которая", "которое", "иду", "идём", "идем")
    private val timeWord = Regex("""(?iu)^(?:понедельник\p{L}*|вторник\p{L}*|сред[уаеы]|четверг\p{L}*|пятниц\p{L}*|суббот\p{L}*|воскресень\p{L}*|выходн\p{L}*|утр\p{L}*|вечер\p{L}*|д[её]н\p{L}*|дня|ноч\p{L}*|обед\p{L}*|полдень|полночь|следующ\p{L}*|ближайш\p{L}*|начал\p{L}*|конц\p{L}*|январ\p{L}*|феврал\p{L}*|март\p{L}*|апрел\p{L}*|ма[йяе]|июн\p{L}*|июл\p{L}*|август\p{L}*|сентябр\p{L}*|октябр\p{L}*|ноябр\p{L}*|декабр\p{L}*|числ\p{L}*|час\p{L}*|недел\p{L}*|\d.*)$""")
    /** Words that end the event's name: «встреча с клиентом перенесена», «экзамен по физике будет». */
    private val verbish = Regex("""(?iu)^(?:перенес\p{L}*|отмен\p{L}*|будет|будут|был\p{L}*|прош\p{L}*|пройд\p{L}*|состо\p{L}*|начн\p{L}*|начина\p{L}*|закончи\p{L}*|назнач\p{L}*|\p{L}+(?:ется|ются|ится|атся|ятся|ет|ут|ют|ит|ат|ят|ла|ли|ло|на|но|ны))$""")

    private val genitiveTail = setOf("диплома", "курсовой", "курсовика", "курсача", "проекта", "диссертации", "магистерской", "отчёта", "отчета",
        "мамы", "папы", "брата", "сестры", "друга", "подруги", "сына", "дочки", "дочери", "жены", "мужа", "бабушки", "дедушки")

    /** What follows the event and belongs to it: «по физике», «в Яндекс», «Земфиры», «у брата» — not «в пятницу», «в 10». */
    private fun complementRu(rest: String): String {
        val words = Regex("""[\p{L}\p{N}][\p{L}\p{N}-]*|[^\p{L}\p{N}\s]""").findAll(rest).map { it.value }.toList()
        if (words.isEmpty()) return ""
        fun ok(w: String) = w.first().isLetter() && w.lowercase() !in ruStop && w.lowercase() !in ruPreps && !timeWord.matches(w) && !verbish.matches(w)
        val first = words[0]
        if (first.lowercase() in ruPreps) {
            val taken = words.drop(1).takeWhile { ok(it) }.take(2)
            return if (taken.isEmpty()) "" else (listOf(first.lowercase()) + taken).joinToString(" ")
        }
        if (first.first().isUpperCase() && ok(first)) return words.takeWhile { it.first().isUpperCase() && ok(it) }.take(2).joinToString(" ")
        if (first.lowercase() in genitiveTail) return first.lowercase()
        return ""
    }

    private val enTime = Regex("""(?iu)^(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|morning|afternoon|evening|night|noon|weekend|week|next|this|the|day|\d.*|$MONTH_EN)$""")

    private val enStop = setOf("and", "but", "so", "or", "i", "is", "are", "was", "were", "will", "be", "it", "at", "on", "in", "to", "with", "for",
        "from", "of", "that", "this", "which", "who", "my", "a", "an", "and", "then")

    private fun complementEn(rest: String): String {
        val words = Regex("""[\p{L}\p{N}][\p{L}\p{N}'-]*|[^\p{L}\p{N}\s]""").findAll(rest).map { it.value }.toList()
        if (words.size < 2 || words[0].lowercase() !in setOf("at", "with", "in", "for", "to", "on")) return ""
        val taken = words.drop(1).takeWhile { it.first().isLetter() && !enTime.matches(it) && it.lowercase() !in enStop }.take(2)
        // "with the boss" keeps "the"; "on Friday" is a day, not a part of the event
        val kept = if (words.getOrNull(1)?.lowercase() == "the" && words.size > 2 && !enTime.matches(words[2])) listOf("the", words[2]) else taken
        return if (kept.isEmpty()) "" else words[0].lowercase() + " " + kept.joinToString(" ")
    }

    /** The word an event goes by, to find it again: «экзамен по физике» → «экзамен». */
    fun head(what: String): String = what.split(' ').first().lowercase()

    // ---- Memory -----------------------------------------------------------------------------------------------

    private val planRu = Regex("""^Планы владельца на (\d{1,2}) ($MONTH_RU) (\d{4}): (.+)$""")
    private val doneRu = Regex("""^(\d{1,2}) ($MONTH_RU) (\d{4}) у владельца было: (.+)$""")
    private val planEn = Regex("""^The owner's plans for (\p{L}+) (\d{1,2}), (\d{4}): (.+)$""")
    private val doneEn = Regex("""^On (\p{L}+) (\d{1,2}), (\d{4}) the owner had: (.+)$""")

    /** The plans memory holds, oldest date first. */
    fun known(memories: List<MemoryItem>): List<Known> = memories.mapNotNull { item ->
        planRu.find(item.text)?.let { m -> read(item, m.groupValues[3], monthsGen.indexOf(m.groupValues[2]) + 1, m.groupValues[1], m.groupValues[4], true, false) }
            ?: doneRu.find(item.text)?.let { m -> read(item, m.groupValues[3], monthsGen.indexOf(m.groupValues[2]) + 1, m.groupValues[1], m.groupValues[4], true, true) }
            ?: planEn.find(item.text)?.let { m -> read(item, m.groupValues[3], enMonth(m.groupValues[1]), m.groupValues[2], m.groupValues[4], false, false) }
            ?: doneEn.find(item.text)?.let { m -> read(item, m.groupValues[3], enMonth(m.groupValues[1]), m.groupValues[2], m.groupValues[4], false, true) }
    }.sortedBy { it.plan.date }

    private fun read(item: MemoryItem, year: String, month: Int, day: String, said: String, ru: Boolean, done: Boolean): Known? {
        val date = runCatching { LocalDate.of(year.toInt(), month, day.toInt()) }.getOrNull() ?: return null
        val outcome = withOutcome.find(said)?.groupValues?.get(2)
        val what = withOutcome.find(said)?.groupValues?.get(1) ?: said
        val e = (if (ru) ruEvents else enEvents).filter { it.re.containsMatchIn(what) }.minByOrNull { it.re.find(what)!!.range.first }
        val kind = when {
            what.startsWith("поездка") || what.startsWith("trip") -> Kind.TRIP
            what.startsWith("визит") || what.endsWith(" visit") -> Kind.HEALTH
            what.startsWith("поход в") -> Kind.FUN
            else -> e?.kind ?: Kind.OTHER
        }
        val gender = if (what.startsWith("поездка")) 'f' else e?.gender ?: 'm'
        return Known(item, Plan(date, what, kind, gender, ru), done, outcome)
    }

    // ---- Talking about them -----------------------------------------------------------------------------------

    /** Whether [text] talks about the plan: any of its meaningful words («экзамен», «физике»), by stem. */
    fun mentions(plan: Plan, text: String): Boolean {
        val generic = setOf("визит", "поход", "день", "рожден", "trip", "visit", "the", "with", "for")
        val keys = words(plan.what).map { Stemmer.stem(it) }.filter { it.length >= 3 && it !in generic }.toSet()
        if (keys.isEmpty()) return false
        return words(text).any { Stemmer.stem(it) in keys }
    }

    private fun words(s: String) = s.lowercase().replace('ё', 'е').split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 && it !in ruPreps }

    /**
     * A plan as a model should read it: relative to today and without the year — «Планы владельца на завтра (7 ноября):
     * экзамен», «Вчера (7 ноября) у владельца было: …». In the ARM evaluation both Gemma 4 models repeated «6 ноября 2026
     * года» from the stored wording. Other memories are returned as they are.
     */
    fun forPrompt(item: MemoryItem, today: LocalDate): MemoryItem {
        val k = known(listOf(item)).firstOrNull() ?: return item
        val d = k.plan.date
        val ru = k.plan.ru
        val days = ChronoUnit.DAYS.between(today, d)
        val md = if (ru) "${d.dayOfMonth} ${monthsGen[d.monthValue - 1]}" else "${monthsEn[d.monthValue - 1]} ${d.dayOfMonth}"
        val whenRu = when (days) {
            0L -> "сегодня ($md)"; 1L -> "завтра ($md)"; 2L -> "послезавтра ($md)"; -1L -> "вчера ($md)"; -2L -> "позавчера ($md)"
            in 3L..6L -> whenText(d, today, true) + " ($md)"
            else -> md + if (d.year != today.year) " ${d.year}" else ""
        }
        val whenEn = when (days) {
            0L -> "today ($md)"; 1L -> "tomorrow ($md)"; -1L -> "yesterday ($md)"
            in 2L..6L -> whenText(d, today, false) + " ($md)"
            else -> md + if (d.year != today.year) ", ${d.year}" else ""
        }
        val said = k.outcome?.let { if (ru) " (владелец: «$it»)" else " (the owner: «$it»)" }.orEmpty()
        val text = when {
            ru && !k.done && !d.isBefore(today) -> "Планы владельца на $whenRu: ${k.plan.what}"
            ru -> "${whenRu.replaceFirstChar { it.uppercase() }} у владельца было: ${k.plan.what}$said"
            !k.done && !d.isBefore(today) -> "The owner's plans for $whenEn: ${k.plan.what}"
            else -> "${whenEn.replaceFirstChar { it.uppercase() }} the owner had: ${k.plan.what}$said"
        }
        return item.copy(text = text)
    }

    /** The owner says how it went («сдал на пять», "it went great"). */
    fun outcome(text: String): Boolean = pastRu.containsMatchIn(text) || pastEn.containsMatchIn(text)

    private val verdict = Regex("""(?iu)(?<!\p{L})(?:отлично|хорошо|нормально|неплохо|плохо|ужасно|супер|класс\p{L}*|круто|здорово|так\s+себе|прекрасно|чудесно|кошмар|провал|пят[её]рк\p{L}*|четв[её]рк\p{L}*|тр[её]йк\p{L}*|двойк\p{L}*|great|good|fine|bad|awful|terrible|ok|okay|amazing|perfect|so-so)(?!\p{L})""")

    /** An answer to «как прошло?»: how it went, not a new request («поставь будильник»). */
    fun verdict(text: String): Boolean = outcome(text) || verdict.containsMatchIn(text)

    /**
     * The plan to ask about now: one that was 1–3 days ago, or an exam or a doctor today after 19:00, not asked yet.
     * The latest first.
     */
    fun due(known: List<Known>, today: LocalDate, hour: Int): Known? = known.filter { !it.done }.filter { k ->
        val d = k.plan.date
        (d.isBefore(today) && !d.isBefore(today.minusDays(3))) || (d == today && hour >= 19 && k.plan.kind in setOf(Kind.TRIAL, Kind.HEALTH))
    }.maxByOrNull { it.plan.date }

    private fun went(p: Plan): String = when (p.gender) { 'f' -> "прошла"; 'n' -> "прошло"; 'p' -> "прошли"; else -> "прошёл" }

    /** «Как прошёл экзамен по физике? Мрр, рассказывай!» */
    fun ask(p: Plan): String = if (p.ru) {
        if (p.kind == Kind.TRIP) "Как ${p.what}? Мрр, рассказывай!" else "Как ${went(p)} ${p.what}? Мрр, рассказывай!"
    } else {
        if (p.kind == Kind.TRIP) "How's the ${p.what}? Mrr, tell me everything!" else "How did the ${p.what} go? Mrr, tell me!"
    }

    /** «Кстати, как прошёл экзамен по физике? …» — after a reply about something else. */
    fun byTheWay(p: Plan): String = (if (p.ru) "Кстати, " else "By the way, ") + ask(p).replaceFirstChar { it.lowercase() }

    /** On the day: «Сегодня экзамен по физике — держу за тебя лапки!» */
    fun wish(p: Plan): String = if (p.ru) "Сегодня ${p.what} — " + when (p.kind) {
        Kind.TRIAL -> "держу за тебя лапки!"
        Kind.HEALTH -> "держу лапки, пусть всё пройдёт легко."
        Kind.FUN -> "пусть будет здорово!"
        Kind.TRIP -> "лёгкой дороги!"
        Kind.OTHER -> "пусть всё пройдёт хорошо!"
    } else "Today's the ${p.what} — " + when (p.kind) {
        Kind.TRIAL -> "my paws are crossed for you!"
        Kind.HEALTH -> "paws crossed, I hope it goes easy."
        Kind.FUN -> "have a great time!"
        Kind.TRIP -> "safe travels!"
        Kind.OTHER -> "I hope it goes well!"
    }

    /** The evening before: «Завтра экзамен по физике — выспись как следует, мрр.» */
    fun eve(p: Plan): String = if (p.ru) "Завтра ${p.what} — " + when (p.kind) {
        Kind.TRIAL -> "выспись как следует, мрр."
        Kind.HEALTH -> "всё будет хорошо, мрр."
        Kind.FUN -> "мрр, потом расскажешь?"
        Kind.TRIP -> "не забудь зарядку для телефона, мрр."
        Kind.OTHER -> "мрр, всё получится."
    } else "Tomorrow's the ${p.what} — " + when (p.kind) {
        Kind.TRIAL -> "get some good sleep, mrr."
        Kind.HEALTH -> "it'll be fine, mrr."
        Kind.FUN -> "mrr, tell me about it after?"
        Kind.TRIP -> "don't forget your phone charger, mrr."
        Kind.OTHER -> "mrr, you've got this."
    }

    /** Said without a model when the owner mentions plans: «Запомнила: завтра — экзамен по физике. Держу за тебя лапки!» */
    fun noted(p: Plan, today: LocalDate): String {
        val w = whenText(p.date, today, p.ru)
        return if (p.ru) "Запомнила: $w — ${p.what}. " + when (p.kind) {
            Kind.TRIAL -> "Держу за тебя лапки!"
            Kind.HEALTH -> "Всё будет хорошо, мрр."
            Kind.FUN, Kind.TRIP -> "Мрр, звучит здорово!"
            Kind.OTHER -> "Потом расскажешь, как прошло?"
        } else "Got it: ${p.what} $w. " + when (p.kind) {
            Kind.TRIAL -> "My paws are crossed for you!"
            Kind.HEALTH -> "It'll be fine, mrr."
            Kind.FUN, Kind.TRIP -> "Mrr, sounds great!"
            Kind.OTHER -> "Tell me how it goes?"
        }
    }

    /** When to remind before a plan with a time: an hour before, or 15 minutes when that's already past; null if too late. */
    fun remindAt(p: Plan, now: java.time.LocalDateTime): java.time.LocalDateTime? {
        val start = java.time.LocalDateTime.of(p.date, p.time ?: return null)
        return listOf(start.minusHours(1), start.minusMinutes(15)).firstOrNull { it.isAfter(now.plusMinutes(5)) }
    }

    /** «Напомнить за час — в 09:00?» */
    fun offer(p: Plan, at: java.time.LocalDateTime): String {
        val hm = "%02d:%02d".format(at.hour, at.minute)
        val hourBefore = at.plusHours(1).toLocalTime() == p.time
        return if (p.ru) (if (hourBefore) "Напомнить за час — в $hm?" else "Напомнить в $hm?")
        else (if (hourBefore) "Shall I remind you an hour before, at $hm?" else "Shall I remind you at $hm?")
    }

    fun crossedOut(what: List<String>, ru: Boolean): String =
        if (ru) "Поняла, вычёркиваю: ${what.joinToString(", ")}." else "Got it, crossing out: ${what.joinToString(", ")}."

    /**
     * Notes for the model: today's and tomorrow's plans ([fresh]: only in the first exchange of the day, so it doesn't
     * repeat them in every reply) and the one to ask about.
     */
    fun notes(known: List<Known>, today: LocalDate, hour: Int, fresh: Boolean, ru: Boolean): List<String> {
        val out = ArrayList<String>()
        if (fresh) {
            known.filter { !it.done && it.plan.date == today }.take(2).forEach { k -> out += if (ru) "сегодня у владельца: ${k.plan.what} — ${wishVerb(k.plan.kind, true)}"
                else "today the owner has: ${k.plan.what} — ${wishVerb(k.plan.kind, false)}" }
            known.filter { it.plan.date == today.plusDays(1) }.take(2).forEach { k -> out += if (ru) "завтра у владельца: ${k.plan.what}" else "tomorrow the owner has: ${k.plan.what}" }
        }
        due(known, today, hour)?.let { k ->
            val w = when (ChronoUnit.DAYS.between(k.plan.date, today)) { 0L -> if (ru) "сегодня" else "today"; 1L -> if (ru) "вчера" else "yesterday"; else -> if (ru) "недавно" else "recently" }
            out += if (ru) "$w у владельца было: ${k.plan.what} — спроси, как прошло, если владелец ещё не рассказал" else "$w the owner had: ${k.plan.what} — ask how it went, unless they already told"
        }
        return out
    }

    private fun wishVerb(k: Kind, ru: Boolean) = when (k) {
        Kind.TRIAL -> if (ru) "пожелай удачи" else "wish them luck"
        Kind.HEALTH -> if (ru) "поддержи" else "be supportive"
        Kind.FUN -> if (ru) "пожелай хорошо провести время" else "wish them a good time"
        Kind.TRIP -> if (ru) "пожелай хорошей дороги" else "wish them a good trip"
        Kind.OTHER -> if (ru) "пожелай, чтобы всё прошло хорошо" else "wish them well"
    }
}
