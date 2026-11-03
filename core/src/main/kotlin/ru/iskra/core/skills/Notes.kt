package ru.iskra.core.skills

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
    val addNote = ToolSpec(SkillNames.ADD_NOTE, "Записать заметку владельца (список покупок, мысль, номер заказа). Не для паролей.",
        ToolSchemas.obj(listOf("text"), mapOf("text" to ToolSchemas.str("Текст заметки, как сказал владелец"))))
    val listNotes = ToolSpec(SkillNames.LIST_NOTES, "Прочитать заметки владельца (последние 10).",
        ToolSchemas.obj(emptyList(), emptyMap()))
    val deleteNotes = ToolSpec(SkillNames.DELETE_NOTES, "Удалить заметки. Спросит подтверждение у владельца.",
        ToolSchemas.obj(listOf("which"), mapOf("which" to ToolSchemas.str("last — последнюю, all — все", listOf("last", "all")))))
    val setReminder = ToolSpec(SkillNames.SET_REMINDER,
        "Напомнить владельцу в заданное время уведомлением и голосом (переживает перезагрузку). " +
            "Укажи либо in_minutes, либо at (местное время, формат 2026-11-03T18:30).",
        ToolSchemas.obj(listOf("text"), mapOf(
            "text" to ToolSchemas.str("О чём напомнить, коротко: «позвонить маме»"),
            "in_minutes" to ToolSchemas.int("Через сколько минут", 1, 525_600),
            "at" to ToolSchemas.str("Местные дата и время, ISO: 2026-11-03T18:30"),
        )))
    val listReminders = ToolSpec(SkillNames.LIST_REMINDERS, "Список будущих напоминаний.", ToolSchemas.obj(emptyList(), emptyMap()))
    val cancelReminders = ToolSpec(SkillNames.CANCEL_REMINDERS, "Отменить напоминания. Спросит подтверждение у владельца.",
        ToolSchemas.obj(listOf("which"), mapOf("which" to ToolSchemas.str("next — ближайшее, all — все", listOf("next", "all")))))
    val weather = ToolSpec(SkillNames.WEATHER, "Погода (Open-Meteo): сейчас и на сегодня/завтра для города владельца или указанного.",
        ToolSchemas.obj(emptyList(), mapOf(
            "day" to ToolSchemas.str("today или tomorrow", listOf("today", "tomorrow")),
            "city" to ToolSchemas.str("Город, если владелец назвал другой"),
        )))
}

class AddNoteTool(private val store: NoteStore, private val clock: () -> Long) : Tool {
    override val spec = SkillSchemas.addNote
    override val risk = Risk.SAFE
    override fun describe(args: JsonObject) = "заметка: «${args.str("text").orEmpty().take(60)}»"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val text = args.str("text")?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (text.length < 2) return ToolOutcome(false, "пустая заметка")
        if (text.length > 1000) return ToolOutcome(false, "слишком длинная заметка — до 1000 символов")
        if (MemoryManager.looksSensitive(text)) return ToolOutcome(false, "похоже на пароль или номер карты — такое не записываю")
        store.add(text, clock())
        return ToolOutcome(true, "записала: «$text»")
    }
}

class ListNotesTool(private val store: NoteStore) : Tool {
    override val spec = SkillSchemas.listNotes
    override val risk = Risk.SAFE
    override val informational = true
    override fun describe(args: JsonObject) = "прочитать заметки"
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val notes = store.all()
        if (notes.isEmpty()) return ToolOutcome(true, "Заметок пока нет. Скажи: «запиши заметку: …».")
        val shown = notes.take(10)
        val more = if (notes.size > shown.size) "\n…и ещё ${notes.size - shown.size}." else ""
        return ToolOutcome(true, "Заметки (${notes.size}):\n" + shown.joinToString("\n") { "• ${it.text}" } + more)
    }
}

class DeleteNotesTool(private val store: NoteStore) : Tool {
    override val spec = SkillSchemas.deleteNotes
    override val risk = Risk.CONFIRM
    override fun describe(args: JsonObject): String {
        val notes = store.all()
        return if (args.str("which") == "all") "удалить все заметки (${notes.size})"
        else "удалить последнюю заметку: «${notes.firstOrNull()?.text?.take(60).orEmpty()}»"
    }
    override suspend fun execute(args: JsonObject): ToolOutcome {
        val notes = store.all()
        if (notes.isEmpty()) return ToolOutcome(false, "заметок нет")
        return if (args.str("which") == "all") { store.deleteAll(); ToolOutcome(true, "удалила заметки: ${notes.size}") }
        else { store.delete(notes.first().id); ToolOutcome(true, "удалила «${notes.first().text.take(60)}»") }
    }
}
