package ru.iskra.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.iskra.core.agent.ConversationStore
import ru.iskra.core.agent.Turn
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.memory.MemoryStore
import ru.iskra.core.persona.Emotion

/**
 * Local SQLite database in the app's private storage (protected by Android file-based encryption
 * and the app sandbox; excluded from backups). Schema migrations go through [onUpgrade].
 */
class IskraDb(context: Context) : SQLiteOpenHelper(context, "iskra.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE memory(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL,
                importance INTEGER NOT NULL, created INTEGER NOT NULL, last_used INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE turns(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL, emotion TEXT)"""
        )
        createV2(db)
        createV3(db)
    }

    /** v3 (0.15.0): Iskra's own diary. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS diary(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    /** v2 (0.7.0): notes and reminders. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS reminders(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL, at INTEGER NOT NULL, created INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Explicit migrations only — never drop user data.
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
    }

    /** A newer version's database opened by this one (the owner went back): its tables stay, nothing is dropped. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

class SqliteMemoryStore(private val helper: IskraDb) : MemoryStore {
    override fun all(): List<MemoryItem> {
        val out = ArrayList<MemoryItem>()
        helper.readableDatabase.rawQuery(
            "SELECT id, kind, text, source, importance, created, last_used FROM memory ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                out += MemoryItem(
                    id = c.getLong(0),
                    kind = runCatching { MemoryKind.valueOf(c.getString(1)) }.getOrDefault(MemoryKind.FACT),
                    text = c.getString(2),
                    source = runCatching { MemorySource.valueOf(c.getString(3)) }.getOrDefault(MemorySource.USER_EXPLICIT),
                    importance = c.getInt(4),
                    createdAtMs = c.getLong(5),
                    lastUsedAtMs = c.getLong(6),
                )
            }
        }
        return out
    }

    override fun insert(kind: MemoryKind, text: String, source: MemorySource, importance: Int, nowMs: Long): MemoryItem {
        val v = ContentValues().apply {
            put("kind", kind.name); put("text", text); put("source", source.name)
            put("importance", importance); put("created", nowMs); put("last_used", nowMs)
        }
        val id = helper.writableDatabase.insertOrThrow("memory", null, v)
        return MemoryItem(id, kind, text, source, importance, nowMs, nowMs)
    }

    override fun update(item: MemoryItem) {
        val v = ContentValues().apply {
            put("kind", item.kind.name); put("text", item.text); put("source", item.source.name)
            put("importance", item.importance); put("last_used", item.lastUsedAtMs)
        }
        helper.writableDatabase.update("memory", v, "id = ?", arrayOf(item.id.toString()))
    }

    override fun delete(id: Long) {
        helper.writableDatabase.delete("memory", "id = ?", arrayOf(id.toString()))
    }

    override fun deleteAll() {
        helper.writableDatabase.delete("memory", null, null)
    }

    override fun replaceAll(items: List<MemoryItem>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("memory", null, null)
            for (m in items) {
                db.insertOrThrow("memory", null, ContentValues().apply {
                    put("kind", m.kind.name); put("text", m.text); put("source", m.source.name)
                    put("importance", m.importance); put("created", m.createdAtMs); put("last_used", m.lastUsedAtMs)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun touch(ids: Collection<Long>, nowMs: Long) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE memory SET last_used = ? WHERE id = ?", arrayOf<Any>(nowMs, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

class SqliteConversationStore(private val helper: IskraDb) : ConversationStore {
    override fun recent(limit: Int): List<Turn> {
        val out = ArrayList<Turn>()
        helper.readableDatabase.rawQuery(
            "SELECT role, text, at, emotion FROM (SELECT * FROM turns ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Turn(
                role = if (c.getString(0) == "USER") Turn.Role.USER else Turn.Role.ASSISTANT,
                text = c.getString(1),
                atMs = c.getLong(2),
                emotion = c.getString(3)?.let { e -> runCatching { Emotion.valueOf(e) }.getOrNull() },
            )
        }
        return out
    }

    override fun oldest(limit: Int): List<Turn> {
        val out = ArrayList<Turn>()
        helper.readableDatabase.rawQuery(
            "SELECT role, text, at, emotion FROM turns ORDER BY id ASC LIMIT ?", arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Turn(
                role = if (c.getString(0) == "USER") Turn.Role.USER else Turn.Role.ASSISTANT,
                text = c.getString(1),
                atMs = c.getLong(2),
                emotion = c.getString(3)?.let { e -> runCatching { Emotion.valueOf(e) }.getOrNull() },
            )
        }
        return out
    }

    override fun append(turn: Turn) {
        val v = ContentValues().apply {
            put("role", turn.role.name); put("text", turn.text); put("at", turn.atMs); put("emotion", turn.emotion?.name)
        }
        helper.writableDatabase.insertOrThrow("turns", null, v)
    }

    override fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM turns", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun popOldest(n: Int): List<Turn> {
        val db = helper.writableDatabase
        val out = ArrayList<Turn>()
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id, role, text, at, emotion FROM turns ORDER BY id ASC LIMIT ?", arrayOf(n.toString())).use { c ->
            while (c.moveToNext()) {
                ids += c.getLong(0)
                out += Turn(
                    if (c.getString(1) == "USER") Turn.Role.USER else Turn.Role.ASSISTANT,
                    c.getString(2), c.getLong(3),
                    c.getString(4)?.let { e -> runCatching { Emotion.valueOf(e) }.getOrNull() },
                )
            }
        }
        if (ids.isNotEmpty()) db.delete("turns", "id <= ?", arrayOf(ids.last().toString()))
        return out
    }

    override fun clear() {
        helper.writableDatabase.delete("turns", null, null)
    }
}

class SqliteNoteStore(private val helper: IskraDb) : ru.iskra.core.skills.NoteStore {
    override fun add(text: String, atMs: Long): ru.iskra.core.skills.Note {
        val id = helper.writableDatabase.insertOrThrow("notes", null, ContentValues().apply { put("text", text); put("created", atMs) })
        return ru.iskra.core.skills.Note(id, text, atMs)
    }

    override fun all(): List<ru.iskra.core.skills.Note> {
        val out = ArrayList<ru.iskra.core.skills.Note>()
        helper.readableDatabase.rawQuery("SELECT id, text, created FROM notes ORDER BY created DESC, id DESC", null).use { c ->
            while (c.moveToNext()) out += ru.iskra.core.skills.Note(c.getLong(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    override fun delete(id: Long) { helper.writableDatabase.delete("notes", "id = ?", arrayOf(id.toString())) }
    override fun deleteAll() { helper.writableDatabase.delete("notes", null, null) }

    override fun replaceAll(notes: List<ru.iskra.core.skills.Note>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("notes", null, null)
            for (n in notes) db.insertOrThrow("notes", null, ContentValues().apply { put("text", n.text); put("created", n.createdAtMs) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

/** Iskra's diary: a day of her life in her own words (0.15.0). */
class SqliteDiaryStore(private val helper: IskraDb) : ru.iskra.core.memory.DiaryStore {
    override fun all(): List<ru.iskra.core.memory.DiaryEntry> {
        val out = ArrayList<ru.iskra.core.memory.DiaryEntry>()
        helper.readableDatabase.rawQuery("SELECT id, day, text, created FROM diary ORDER BY day, id", null).use { c ->
            while (c.moveToNext()) out += ru.iskra.core.memory.DiaryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    override fun add(day: String, text: String, nowMs: Long): ru.iskra.core.memory.DiaryEntry {
        val id = helper.writableDatabase.insertOrThrow("diary", null, ContentValues().apply { put("day", day); put("text", text); put("created", nowMs) })
        return ru.iskra.core.memory.DiaryEntry(id, day, text, nowMs)
    }

    override fun delete(id: Long) { helper.writableDatabase.delete("diary", "id = ?", arrayOf(id.toString())) }
    override fun clear() { helper.writableDatabase.delete("diary", null, null) }

    /** A backup's entries in place of these. */
    fun replaceAll(entries: List<ru.iskra.core.memory.DiaryEntry>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("diary", null, null)
            for (e in entries) db.insertOrThrow("diary", null, ContentValues().apply { put("day", e.day); put("text", e.text); put("created", e.createdAtMs) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

class SqliteReminderStore(private val helper: IskraDb) : ru.iskra.core.skills.ReminderStore {
    override fun add(text: String, atMs: Long, createdAtMs: Long): ru.iskra.core.skills.Reminder {
        val id = helper.writableDatabase.insertOrThrow("reminders", null, ContentValues().apply {
            put("text", text); put("at", atMs); put("created", createdAtMs); put("done", 0)
        })
        return ru.iskra.core.skills.Reminder(id, text, atMs, createdAtMs)
    }

    private fun query(where: String?, args: Array<String>?): List<ru.iskra.core.skills.Reminder> {
        val out = ArrayList<ru.iskra.core.skills.Reminder>()
        helper.readableDatabase.rawQuery(
            "SELECT id, text, at, created, done FROM reminders" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY at", args
        ).use { c ->
            while (c.moveToNext()) out += ru.iskra.core.skills.Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)
        }
        return out
    }

    override fun get(id: Long) = query("id = ?", arrayOf(id.toString())).firstOrNull()
    override fun pending() = query("done = 0", null)
    override fun markDone(id: Long) { helper.writableDatabase.update("reminders", ContentValues().apply { put("done", 1) }, "id = ?", arrayOf(id.toString())) }
    override fun delete(id: Long) { helper.writableDatabase.delete("reminders", "id = ?", arrayOf(id.toString())) }
    fun deleteAll() { helper.writableDatabase.delete("reminders", null, null) }
}
