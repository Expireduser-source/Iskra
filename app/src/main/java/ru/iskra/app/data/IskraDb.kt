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
class IskraDb(context: Context) : SQLiteOpenHelper(context, "iskra.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema. Future versions add explicit migrations here — never drop user data.
    }
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
