package ru.iskra.core.agent

import ru.iskra.core.persona.Emotion

data class Turn(val role: Role, val text: String, val atMs: Long, val emotion: Emotion? = null) {
    enum class Role { USER, ASSISTANT }
}

/** Short-term conversation log (the last N turns are sent to the model). */
interface ConversationStore {
    fun recent(limit: Int): List<Turn>
    /** Reads the oldest turns without removing them, so failed summaries preserve history. */
    fun oldest(limit: Int): List<Turn>
    fun append(turn: Turn)
    fun count(): Int
    /** Removes the oldest [n] turns and returns them (used for summarisation into episodes). */
    fun popOldest(n: Int): List<Turn>
    fun clear()
}

class InMemoryConversationStore : ConversationStore {
    private val turns = ArrayList<Turn>()
    @Synchronized override fun recent(limit: Int) = turns.takeLast(limit)
    @Synchronized override fun oldest(limit: Int) = turns.take(limit)
    @Synchronized override fun append(turn: Turn) { turns += turn }
    @Synchronized override fun count() = turns.size
    @Synchronized override fun popOldest(n: Int): List<Turn> {
        val k = n.coerceAtMost(turns.size)
        val out = turns.subList(0, k).toList()
        repeat(k) { turns.removeAt(0) }
        return out
    }
    @Synchronized override fun clear() = turns.clear()
}

/** Persistent per-companion state (mood, style, timestamps). */
interface CompanionStateStore {
    fun load(): CompanionState
    fun save(state: CompanionState)
}

data class CompanionState(
    val mood: ru.iskra.core.persona.MoodState = ru.iskra.core.persona.MoodState(),
    val style: ru.iskra.core.persona.StyleProfile = ru.iskra.core.persona.StyleProfile(),
    val lastUserMessageAtMs: Long = 0L,
    val totalUserMessages: Long = 0L,
)

class InMemoryStateStore(private var state: CompanionState = CompanionState()) : CompanionStateStore {
    override fun load() = state
    override fun save(state: CompanionState) { this.state = state }
}
