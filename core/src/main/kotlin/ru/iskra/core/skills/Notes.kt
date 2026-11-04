package ru.iskra.core.skills

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.JsonObject
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.ToolSchemas
import ru.iskra.core.tools.str
import ru.iskra.core.llm.ToolSpec

object SkillNames {
    const val ADD_NOTE = "add_note"
    const val LIST_NOTES = "list_notes"
    const val DELETE_NOTES = "delete_notes"
    const val SET_REMINDER = "set_reminder"
    const val LIST_REMINDERS = "list_reminders"
    const val CANCEL_REMINDERS = "cancel_reminders"
    const val WEATHER = "get_weather"
}

data class Note(val id: Long, val text: String, val createdAtMs: Long)

/** Owner's notes, stored only on the phone. They go to the model only when asked for ("что в заметках"). */
interface NoteStore {
    fun add(text: String, atMs: Long): Note
    /** Newest first. */
    fun all(): List<Note>
    fun delete(id: Long)
    fun deleteAll()
    fun replaceAll(notes: List<Note>)
}

class InMemoryNoteStore : NoteStore {
    private val notes = ArrayList<Note>()
    private var nextId = 1L
    @Synchronized override fun add(text: String, atMs: Long) = Note(nextId++, text, atMs).also { notes += it }
    @Synchronized override fun all() = notes.sortedByDescending { it.createdAtMs * 1000 + it.id }
    @Synchronized override fun delete(id: Long) { notes.removeAll { it.id == id } }
    @Synchronized override fun deleteAll() = notes.clear()
    @Synchronized override fun replaceAll(notes: List<Note>) {
        this.notes.clear(); this.notes += notes; nextId = (notes.maxOfOrNull { it.id } ?: 0) + 1
    }
}

internal object SkillSchemas {
    val addNote get() = ToolSpec(SkillNames.ADD_NOTE, tr("Записать заметку владельца (список покупок, мысль, номер заказа). Не для паролей.", "Write down an owner's note (shopping list, a thought, an order number). Not for passwords."),
        ToolSchemas.obj(listOf("text"), mapOf("text" to ToolSchemas.str(tr("Текст заметки, как сказал владелец", "Note text as the owner said it")))))
    val listNotes get() = ToolSpec(SkillNames.LIST_NOTES, tr("Прочитать заметки владельца (последние 10).", "Read the owner's notes (last 10)."),
        ToolSchemas.obj(emptyList(), emptyMap()))
    val deleteNotes get() = ToolSpec(SkillNames.DELETE_NOTES, tr("Удалить заметки. Спросит подтверждение у владельца.", "Delete notes. Asks the owner to confirm."),
        ToolSchemas.obj(listOf("which"), mapOf("which" to ToolSchemas.str(tr("last — последнюю, all — все", "last — the latest one, all — all of them"), listOf("last", "all")))))
    val setReminder get() = ToolSpec(SkillNames.SET_REMINDER,
        tr("Напомнить владельцу в заданное время уведомлением и голосом (переживает перезагрузку). " +
            "Укажи либо in_minutes, либо at (местное время, формат 2026-11-03T18:30).", "Remind the owner at the given time with a notification and voice (survives a reboot). Give either in_minutes or at (local time, format 2026-11-03T18:30)."),
        ToolSchemas.obj(listOf("text"), mapOf(
            "text" to ToolSchemas.str(tr("О чём напомнить, коротко: «позвонить маме»", "What to remind about, briefly: “call mom”")),
            "in_minutes" to ToolSchemas.int(tr("Через сколько минут", "In how many minutes"), 1, 525_600),
            "at" to ToolSchemas.str(tr("Местные дата и время, ISO: 2026-11-03T18:30", "Local date and time, ISO: 2026-11-03T18:30")),
        )))
    val listReminders get() = ToolSpec(SkillNames.LIST_REMINDERS, tr("Список будущих напоминаний.", "List of upcoming reminders."), ToolSchemas.obj(emptyList(), emptyMap()))
    val cancelReminders get() = ToolSpec(SkillNames.CANCEL_REMINDERS, tr("Отменить напоминания. Спросит подтверждение у владельца.", "Cancel reminders. Asks the owner to confirm."),
        ToolSchemas.obj(listOf("which"), mapOf("which" to ToolSchemas.str(tr("next — ближайшее, all — все", "next — the nearest one, all — all of them"), listOf("next", "all")))))
    val weather get() = ToolSpec(SkillNames.WEATHER, tr("Погода (Open-Meteo): сейчас и на сегодня/завтра для города владельца или указанного.", "Weather (Open-Meteo): now and for today/tomorrow for the owner's city or the named one."),
        ToolSchemas.obj(emptyList(), mapOf(
            "day" to ToolSchemas.str(tr("today или tomorrow", "today or tomorrow"), listOf("today", "tomorrow")),
            "city" to ToolSchemas.str(tr("Город, если владелец назвал другой", "City, if the owner named another one")),
        )))
}

class AddNoteTool(private val store: NoteStore, private val clock: () -> Long) : Tool {
    override val spec get() = SkillSchemas.addNote
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = tr("заметка: «${args.str("text").orEmpty().take(60)}»", "note: “${args.str("text").orEmpty().take(60)}”")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.length < 2) return ToolOutcome(false, tr("пустая заметка", "empty note"))
        if (text.length > 1000) return ToolOutcome(false, tr("слишком длинная заметка — до 1000 символов", "the note is too long — up to 1000 characters"))
        if (MemoryManager.looksSensitive(text)) return ToolOutcome(false, tr("похоже на пароль или номер карты — такое не записываю", "looks like a password or a card number — I don't write that down"))
        store.add(text, clock())
        return ToolOutcome(true, tr("записала: «$text»", "wrote down: “$text”"))
    }
}

class ListNotesTool(private val store: NoteStore) : Tool {
    override val spec get() = SkillSchemas.listNotes
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = tr("прочитать заметки", "read the notes")
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val notes = store.all()
        if (notes.isEmpty()) return ToolOutcome(true, tr("Заметок пока нет. Скажи: «запиши заметку: …».", "No notes yet. Say: “note: …”."))
        val shown = notes.take(10)
        val more = if (notes.size > shown.size) tr("\n…и ещё ${notes.size - shown.size}.", "\n…and ${notes.size - shown.size} more.") else ""
        return ToolOutcome(true, tr("Заметки (${notes.size}):\n", "Notes (${notes.size}):\n") + shown.joinToString("\n") { "• ${it.text}" } + more)
    }
}

class DeleteNotesTool(private val store: NoteStore) : Tool {
    override val spec get() = SkillSchemas.deleteNotes
    override val risk = Risk.CONFIRM
    override fun describe(args: JsonObject): String {
        val notes = store.all()
        return if (args.str("which") == "all") tr("удалить все заметки (${notes.size})", "delete all notes (${notes.size})")
        else tr("удалить последнюю заметку: «${notes.firstOrNull()?.text?.take(60).orEmpty()}»", "delete the latest note: “${notes.firstOrNull()?.text?.take(60).orEmpty()}”")
    }
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val notes = store.all()
        if (notes.isEmpty()) return ToolOutcome(false, tr("заметок нет", "there are no notes"))
        return if (args.str("which") == "all") { store.deleteAll(); ToolOutcome(true, tr("удалила заметки: ${notes.size}", "deleted notes: ${notes.size}")) }
        else { store.delete(notes.first().id); ToolOutcome(true, tr("удалила «${notes.first().text.take(60)}»", "deleted “${notes.first().text.take(60)}”")) }
    }
}
