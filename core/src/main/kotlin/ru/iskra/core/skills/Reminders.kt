package ru.iskra.core.skills

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.JsonObject
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.tools.CommandParser
import ru.iskra.core.tools.EnglishReminders
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.int
import ru.iskra.core.tools.str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Reminder(val id: Long, val text: String, val atMs: Long, val createdAtMs: Long, val done: Boolean = false)

/** Reminders on the phone; the app re-arms the pending ones after a reboot or an update. */
interface ReminderStore {
    fun add(text: String, atMs: Long, createdAtMs: Long): Reminder
    fun get(id: Long): Reminder?
    /** Not yet delivered, soonest first. */
    fun pending(): List<Reminder>
    fun markDone(id: Long)
    fun delete(id: Long)
}

/** Arms the system alarm for a reminder (AlarmManager in the app). */
interface ReminderScheduler {
    fun schedule(r: Reminder)
    fun cancel(id: Long)
    /** false: Android gives only inexact alarms (may come a few minutes late). */
    val exact: Boolean get() = true
}

class InMemoryReminderStore : ReminderStore {
    private val items = ArrayList<Reminder>()
    private var nextId = 1L
    @Synchronized override fun add(text: String, atMs: Long, createdAtMs: Long) = Reminder(nextId++, text, atMs, createdAtMs).also { items += it }
    @Synchronized override fun get(id: Long) = items.firstOrNull { it.id == id }
    @Synchronized override fun pending() = items.filter { !it.done }.sortedBy { it.atMs }
    @Synchronized override fun markDone(id: Long) { items.replaceAll { if (it.id == id) it.copy(done = true) else it } }
    @Synchronized override fun delete(id: Long) { items.removeAll { it.id == id } }
}

/**
 * «Напомни через 10 минут позвонить маме», «напомни в 18:30 купить хлеб», «напомни завтра в 9 про
 * встречу», «напомни выключить плиту через полчаса», «напомни завтра утром …».
 */
object ReminderParser {
    /** [inMinutes] is set for relative times («через 10 минут»), so seconds are not lost. */
    data class Result(val text: String, val at: LocalDateTime, val inMinutes: Long? = null)

    private const val UNIT = """(секунд\p{L}*|сек|минут\p{L}*|мин|час\p{L}*|ч|дн\p{L}*|день|недел\p{L}*)"""
    private val relative = Regex("""(?:^|\s)через\s+(?:(час|полчаса|полтора часа|день|неделю|минуту|сутки)|(\d{1,4}|[а-я]+)\s*$UNIT?)(?=\s|$|[,.!])""")
    private val dayWord = Regex("""(?:^|\s)(сегодня|завтра|послезавтра)(?=\s|$|[,.!])""")
    private val clock = Regex("""(?:^|\s)(?:в|к)\s+(\d{1,2})(?:[:.\s](\d{2}))?(?:\s*(?:часов|часа|час))?(?:\s*(утра|дня|вечера|ночи))?(?=\s|$|[,.!])""")
    private val partOfDay = Regex("""(?:^|\s)(утром|днем|вечером|ночью)(?=\s|$|[,.!])""")
    // «напомни», not «напомнишь» (that one is a question, read by [canonical]).
    private val prefix = Regex("""^(?:искра[, ]+)?(?:пожалуйста[, ]+)?напомни(?:те)?(?:-ка)?(?![\p{L}])(?:\s+мне)?(?:\s+пожалуйста)?[,:]?\s*""")
    private val leadingJunk = Regex("""(?iu)^(?:[,:\s—]|(?:о том,? что|чтобы|что|мне|пожалуйста|надо|нужно|необходимо)(?![\p{L}-]))+\s*""")

    /** «Напомнишь мне…?», «Можешь напомнить…», «Не забудь напомнить…» — asking for a reminder in other words. */
    private val askLead = Regex("""^(?:искра[, ]+)?(?:пожалуйста[, ]+)?(?:(?:ты\s+)?(?:мне\s+)?напомнишь|(?:ты\s+)?(?:мне\s+)?(?:мог(?:ла|ли)?\s+бы|можешь|можете|сможешь)\s+(?:мне\s+)?напомнить|не\s+забудь(?:те)?\s+(?:мне\s+)?напомнить)(?![\p{L}])""")
    /** «…, не дай мне забыть», «…, чтобы я не забыла», «…, а то забуду». */
    private val forget = Regex("""[,;]?\s*(?:—\s*)?(?:(?:только|пожалуйста)\s+)?не\s+дай(?:те)?\s+(?:мне\s+)?(?:это\s+)?забыть(?![\p{L}])|[,;]?\s*(?:—\s*)?чтобы\s+я\s+не\s+забыл[аи]?(?![\p{L}])|[,;]?\s*(?:—\s*)?а\s+то\s+(?:я\s+)?(?:точно\s+)?забуду(?![\p{L}])""")

    /**
     * A request for a reminder in any of the usual wordings, written as «напомни …» for [parse]: «Напомнишь мне через час
     * позвонить маме?», «Можешь напомнить…», «Через полчаса надо вынуть пирог из духовки, не дай мне забыть» (only Gemma 4
     * with its tools understood that one; now it works with any model, and without one). English too ("can you remind me",
     * "don't let me forget"). null when it isn't such a request.
     */
    fun canonical(input: String): String? {
        val raw = input.trim().trimEnd('?', '.', '!', ' ')
        val t = raw.lowercase().replace('ё', 'е')
        if (prefix.containsMatchIn(t)) return raw
        askLead.find(t)?.let { m -> return "напомни " + raw.substring(m.range.last + 1).trimStart(',', ' ') }
        forget.find(t)?.let { m -> return "напомни " + raw.removeRange(m.range).trim().trim(',', ' ', '—') }
        return EnglishReminders.canonical(raw)
    }

    fun parse(input: String, now: LocalDateTime): Result? {
        val raw = input.trim().trimEnd('.', '!')
        val t = raw.lowercase().replace('ё', 'е')
        val p = prefix.find(t) ?: return EnglishReminders.parse(input, now)
        var body = t.substring(p.range.last + 1)
        var original = raw.substring(p.range.last + 1)
        fun cut(range: IntRange) {
            body = body.removeRange(range); original = original.removeRange(range)
        }

        var at: LocalDateTime? = null
        var inMinutes: Long? = null
        relative.find(body)?.let { m ->
            val minutes = relativeMinutes(m) ?: return null
            at = now.plusSeconds(minutes * 60L); inMinutes = minutes
            cut(m.range)
        }
        if (at == null) {
            val day = dayWord.find(body)
            val time = clock.find(body)
            val part = partOfDay.find(body)
            if (day == null && time == null && part == null) return null
            val date = when (day?.groupValues?.get(1)) {
                "завтра" -> now.toLocalDate().plusDays(1)
                "послезавтра" -> now.toLocalDate().plusDays(2)
                else -> now.toLocalDate()
            }
            var hour: Int; var minute = 0
            var meridiem = false
            if (time != null) {
                hour = time.groupValues[1].toInt(); minute = time.groupValues[2].ifEmpty { "0" }.toInt()
                when (time.groupValues[3]) {
                    "дня", "вечера" -> { if (hour in 1..11) hour += 12; meridiem = true }
                    "ночи" -> { if (hour == 12) hour = 0; meridiem = true }
                    "утра" -> meridiem = true
                }
            } else {
                hour = when (part?.groupValues?.get(1)) { "днем" -> 13; "вечером" -> 19; "ночью" -> 23; else -> 9 }
                meridiem = true
            }
            if (time == null && part != null) when (part.groupValues[1]) { "вечером" -> if (hour < 12) hour += 12 }
            if (hour !in 0..23 || minute !in 0..59) return null
            var candidate = date.atTime(hour, minute)
            if (day == null && !candidate.isAfter(now)) {
                // "в 7" said at 10:00 means 19:00 today; at 20:00 it means 7:00 tomorrow.
                candidate = if (!meridiem && hour in 1..11 && date.atTime(hour + 12, minute).isAfter(now)) date.atTime(hour + 12, minute)
                else candidate.plusDays(1)
            }
            at = candidate
            // Remove the matched parts from the text, rightmost first so ranges stay valid.
            listOfNotNull(day?.range, time?.range, part?.range).sortedByDescending { it.first }.forEach(::cut)
        }
        val result = at ?: return null
        if (!result.isAfter(now) || result.isAfter(now.plusDays(366))) return null
        val text = original.replace(leadingJunk, "").trim().trimEnd(',', ' ').ifBlank { tr("напоминание", "reminder") }
        return Result(text, result, inMinutes)
    }

    private fun relativeMinutes(m: MatchResult): Long? {
        m.groupValues[1].takeIf { it.isNotEmpty() }?.let {
            return when (it) { "час" -> 60; "полчаса" -> 30; "полтора часа" -> 90; "день", "сутки" -> 1440; "неделю" -> 10_080; else -> 1 }
        }
        val numText = m.groupValues[2]
        val n = numText.toLongOrNull() ?: CommandParser.numberWords[numText]?.toLong() ?: return null
        val unit = m.groupValues[3]
        return when {
            numText == "полчаса" -> 30
            unit.startsWith("сек") -> maxOf(1, n / 60)
            unit.startsWith("час") || unit == "ч" -> n * 60
            unit.startsWith("дн") || unit == "день" -> n * 1440
            unit.startsWith("недел") -> n * 10_080
            else -> n
        }.takeIf { it in 1..525_600 }
    }
}

/** Upcoming reminders for the prompt: "сегодня в 18:30 — позвонить маме" (the next [max] within [withinMs]). */
fun upcomingReminders(store: ReminderStore, nowMs: Long, zone: ZoneId, withinMs: Long = 36 * 3_600_000L, max: Int = 3): List<String> =
    store.pending().filter { it.atMs in nowMs..(nowMs + withinMs) }.take(max).map { "${whenText(it.atMs, nowMs, zone)} — ${it.text}" }

/** How to say when: «в 18:30», «завтра в 09:00», «через 10 мин (в 14:35)». */
internal fun whenText(atMs: Long, nowMs: Long, zone: ZoneId): String {
    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMs), zone)
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val hm = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    val minutes = (atMs - nowMs + 30_000) / 60_000
    return when {
        minutes < 60 -> tr("через $minutes мин (в $hm)", "in $minutes min (at $hm)")
        at.toLocalDate() == now.toLocalDate() -> tr("сегодня в $hm", "today at $hm")
        at.toLocalDate() == now.toLocalDate().plusDays(1) -> tr("завтра в $hm", "tomorrow at $hm")
        else -> at.format(tr(DateTimeFormatter.ofPattern("d.MM в HH:mm"), DateTimeFormatter.ofPattern("MMM d 'at' HH:mm", java.util.Locale.ENGLISH)))
    }
}

class SetReminderTool(
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val clock: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Tool {
    override val spec get() = SkillSchemas.setReminder
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("напоминание: «${args.str("text").orEmpty().take(60)}»", "reminder: “${args.str("text").orEmpty().take(60)}”")

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text")?.trim()?.take(300).orEmpty().ifBlank { tr("напоминание", "reminder") }
        if (MemoryManager.looksSensitive(text)) return ToolOutcome(false, tr("похоже на пароль или номер карты — такое не сохраняю", "looks like a password or a card number — I don't save that"))
        val now = clock()
        val atMs = args.int("in_minutes")?.let { now + it * 60_000L }
            ?: args.str("at")?.let { s ->
                runCatching { LocalDateTime.parse(s.take(16)) }.recoverCatching { LocalDate.parse(s.take(10)).atTime(9, 0) }
                    .getOrNull()?.atZone(zone())?.toInstant()?.toEpochMilli()
            }
            ?: return ToolOutcome(false, tr("не поняла, когда напомнить", "I didn't get when to remind you"))
        if (atMs <= now) return ToolOutcome(false, tr("это время уже прошло", "that time has already passed"))
        if (atMs > now + 366L * 86_400_000) return ToolOutcome(false, tr("слишком далеко — не дальше года", "too far ahead — no more than a year"))
        val r = store.add(text, atMs, now)
        scheduler.schedule(r)
        val late = if (scheduler.exact) "" else tr(" (Android может прислать его на несколько минут позже — для точности разреши «Будильники и напоминания» в настройках Искры)", " (Android may deliver it a few minutes late — for the exact time allow “Alarms & reminders” in Iskra's settings)")
        return ToolOutcome(true, tr("напомню «$text» ${whenText(atMs, now, zone())}$late", "I'll remind you “$text” ${whenText(atMs, now, zone())}$late"))
    }
}

class ListRemindersTool(private val store: ReminderStore, private val clock: () -> Long, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : Tool {
    override val spec get() = SkillSchemas.listReminders
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = tr("список напоминаний", "list of reminders")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val list = store.pending()
        if (list.isEmpty()) return ToolOutcome(true, tr("Напоминаний нет. Скажи: «напомни через 10 минут …».", "No reminders. Say: “remind me in 10 minutes …”."))
        val now = clock()
        return ToolOutcome(true, tr("Напоминания (${list.size}):\n", "Reminders (${list.size}):\n") + list.take(10).joinToString("\n") { "• ${whenText(it.atMs, now, zone())} — ${it.text}" })
    }
}

class CancelRemindersTool(private val store: ReminderStore, private val scheduler: ReminderScheduler) : Tool {
    override val spec get() = SkillSchemas.cancelReminders
    override val risk = Risk.CONFIRM
    override fun describe(args: JsonObject): String {
        val list = store.pending()
        return if (args.str("which") == "all") tr("отменить все напоминания (${list.size})", "cancel all reminders (${list.size})")
        else tr("отменить ближайшее напоминание: «${list.firstOrNull()?.text?.take(60).orEmpty()}»", "cancel the nearest reminder: “${list.firstOrNull()?.text?.take(60).orEmpty()}”")
    }
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val list = store.pending()
        if (list.isEmpty()) return ToolOutcome(false, tr("напоминаний нет", "there are no reminders"))
        val chosen = if (args.str("which") == "all") list else list.take(1)
        chosen.forEach { scheduler.cancel(it.id); store.delete(it.id) }
        return ToolOutcome(true, if (chosen.size == 1) tr("отменила «${chosen[0].text.take(60)}»", "cancelled “${chosen[0].text.take(60)}”") else tr("отменила напоминания: ${chosen.size}", "cancelled reminders: ${chosen.size}"))
    }
}
