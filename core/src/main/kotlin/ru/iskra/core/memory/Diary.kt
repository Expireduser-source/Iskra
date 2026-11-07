package ru.iskra.core.memory

/**
 * A day of Iskra's life in her own words — her diary (a young digital life, 0.15.0): written at night from that day's
 * conversations, recalled the next morning, readable and deletable by the owner like memory. [day] is an ISO date.
 */
data class DiaryEntry(val id: Long, val day: String, val text: String, val createdAtMs: Long)

interface DiaryStore {
    /** Oldest first. */
    fun all(): List<DiaryEntry>
    fun add(day: String, text: String, nowMs: Long): DiaryEntry
    fun delete(id: Long)
    fun clear()
}

class InMemoryDiaryStore : DiaryStore {
    private val entries = ArrayList<DiaryEntry>()
    private var next = 1L
    @Synchronized override fun all() = entries.toList()
    @Synchronized override fun add(day: String, text: String, nowMs: Long) = DiaryEntry(next++, day, text, nowMs).also { entries += it }
    @Synchronized override fun delete(id: Long) { entries.removeAll { it.id == id } }
    @Synchronized override fun clear() = entries.clear()
}
