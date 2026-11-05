package ru.iskra.core.memory

import ru.iskra.core.i18n.tr
import kotlin.math.exp
import kotlin.math.ln

enum class MemoryKind { FACT, PREFERENCE, EPISODE }
enum class MemorySource { USER_EXPLICIT, MODEL_PROPOSED, SUMMARY }

data class MemoryItem(
    val id: Long,
    val kind: MemoryKind,
    val text: String,
    val source: MemorySource,
    val importance: Int,          // 1..5
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
)

/** Persistent storage of long-term memory. Android: SQLite. Tests: [InMemoryMemoryStore]. */
interface MemoryStore {
    fun all(): List<MemoryItem>
    fun insert(kind: MemoryKind, text: String, source: MemorySource, importance: Int, nowMs: Long): MemoryItem
    fun update(item: MemoryItem)
    fun delete(id: Long)
    fun deleteAll()
    fun touch(ids: Collection<Long>, nowMs: Long)
    /** Replaces everything with [items] (restore from backup), keeping their timestamps. Ids are reassigned. */
    fun replaceAll(items: List<MemoryItem>)
}

class InMemoryMemoryStore : MemoryStore {
    private val items = LinkedHashMap<Long, MemoryItem>()
    private var nextId = 1L

    @Synchronized override fun all() = items.values.toList()
    @Synchronized override fun insert(kind: MemoryKind, text: String, source: MemorySource, importance: Int, nowMs: Long): MemoryItem {
        val item = MemoryItem(nextId++, kind, text, source, importance, nowMs, nowMs)
        items[item.id] = item
        return item
    }
    @Synchronized override fun update(item: MemoryItem) { items[item.id] = item }
    @Synchronized override fun delete(id: Long) { items.remove(id) }
    @Synchronized override fun deleteAll() { items.clear() }
    @Synchronized override fun touch(ids: Collection<Long>, nowMs: Long) {
        ids.forEach { id -> items[id]?.let { items[id] = it.copy(lastUsedAtMs = nowMs) } }
    }
    @Synchronized override fun replaceAll(items: List<MemoryItem>) {
        this.items.clear()
        items.forEach { val n = it.copy(id = nextId++); this.items[n.id] = n }
    }
}

/** Russian/English normalisation for memory: lowercase, split, drop stop words, stem, add synonym concepts. */
object TextNorm {
    private val stop = setOf(
        "это", "как", "что", "так", "для", "меня", "мне", "тебя", "тебе", "был", "была", "было", "есть",
        "the", "and", "you", "она", "они", "оно", "его", "еще", "ещё", "уже", "очень", "только", "когда",
        "где", "мой", "моя", "мое", "моё", "мои", "твой", "твоя", "свой", "все", "всё", "или", "если", "чтобы",
        "that", "this", "with", "have", "has", "was", "were", "are", "for", "not", "but", "what", "when", "where",
        "how", "your", "about", "from", "will", "would", "can", "could", "just", "very", "also", "they", "she",
        "her", "him", "his", "its", "our", "all", "any", "mine", "there", "their",
        "мою", "моей", "моего", "моих", "свою", "своей", "владелец", "владельца", "владельцу", "owner", "owner's",
    )

    /** Stems plus concept tokens ("#dog") for the words that have them. */
    fun tokens(text: String): List<String> {
        val stems = words(text).map(::stem)
        return stems + stems.flatMap { Concepts.of(it) }.distinct()
    }

    private fun words(text: String) = text.lowercase().replace('ё', 'е')
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in stop }

    fun stem(word: String): String = Stemmer.stem(word)
}

/**
 * Lexical retrieval with IDF weighting + importance + recency.
 * Deliberately simple and deterministic; the [MemoryRetriever] interface lets us swap in
 * embedding search later without touching the agent.
 */
interface MemoryRetriever {
    fun retrieve(query: String, items: List<MemoryItem>, limit: Int, nowMs: Long): List<MemoryItem>
}

class LexicalRetriever : MemoryRetriever {
    override fun retrieve(query: String, items: List<MemoryItem>, limit: Int, nowMs: Long): List<MemoryItem> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val q = TextNorm.tokens(query).toSet()
        val docs = items.map { it to TextNorm.tokens(it.text).toSet() }
        val df = HashMap<String, Int>()
        docs.forEach { (_, toks) -> toks.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = items.size.toDouble()
        val scored = docs.map { (item, toks) ->
            var lexical = 0.0
            for (t in q) if (t in toks) lexical += ln(1.0 + n / (df[t] ?: 1))
            val ageDays = (nowMs - item.lastUsedAtMs).coerceAtLeast(0) / 86_400_000.0
            val recency = exp(-ageDays / 30.0)
            val prior = 0.15 * item.importance + 0.2 * recency +
                (if (item.kind == MemoryKind.PREFERENCE) 0.1 else 0.0)
            item to (if (lexical > 0) 2.0 * lexical + prior else prior * 0.5)
        }
        // Always include very important memories (owner's name etc.), then the best matches.
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}

/** Memory for one prompt, in three parts (see [MemoryManager.context]). */
data class MemoryContext(
    val profile: List<MemoryItem> = emptyList(),
    val related: List<MemoryItem> = emptyList(),
    val recent: List<MemoryItem> = emptyList(),
) {
    val all: List<MemoryItem> get() = profile + related + recent
    fun isEmpty() = profile.isEmpty() && related.isEmpty() && recent.isEmpty()
}

class MemoryManager(
    private val store: MemoryStore,
    private val retriever: MemoryRetriever = LexicalRetriever(),
    private val maxItems: Int = 2000,
    private val maxTextLength: Int = 400,
) {
    sealed interface AddResult {
        data class Added(val item: MemoryItem) : AddResult
        data class Duplicate(val existing: MemoryItem) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    fun add(text: String, kind: MemoryKind, source: MemorySource, importance: Int, nowMs: Long): AddResult {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 3) return AddResult.Rejected(tr("слишком коротко", "too short"))
        if (clean.length > maxTextLength) return AddResult.Rejected(tr("слишком длинно (до $maxTextLength символов)", "too long (up to $maxTextLength characters)"))
        if (looksSensitive(clean)) return AddResult.Rejected(tr("похоже на пароль или номер карты — такое я не храню", "looks like a password or a card number — I don't keep that"))
        val all = store.all()
        val toks = TextNorm.tokens(clean).toSet()
        all.firstOrNull { similar(toks, TextNorm.tokens(it.text).toSet()) }?.let { return AddResult.Duplicate(it) }
        if (all.size >= maxItems) {
            // Evict the least important, least recently used non-explicit memory.
            val victim = all.filter { it.source != MemorySource.USER_EXPLICIT }
                .minWithOrNull(compareBy<MemoryItem>({ it.importance }, { it.lastUsedAtMs }))
                ?: return AddResult.Rejected(tr("память заполнена", "memory is full"))
            store.delete(victim.id)
        }
        return AddResult.Added(store.insert(kind, clean, source, importance.coerceIn(1, 5), nowMs))
    }

    /**
     * What goes into the prompt: who the owner is (the most important facts, always), what relates to this
     * message, and the latest conversation summaries (continuity across days).
     */
    fun context(query: String, nowMs: Long, profileLimit: Int = 5, relatedLimit: Int = 6, recentLimit: Int = 2): MemoryContext {
        val all = store.all()
        val profile = all.filter { it.kind != MemoryKind.EPISODE && it.importance >= 4 }
            .sortedWith(compareByDescending<MemoryItem> { it.importance }.thenByDescending { it.createdAtMs }).take(profileLimit)
        val recent = all.filter { it.kind == MemoryKind.EPISODE }.sortedByDescending { it.createdAtMs }.take(recentLimit)
        val used = (profile + recent).map { it.id }.toSet()
        val related = retriever.retrieve(query, all.filter { it.id !in used }, relatedLimit, nowMs)
        store.touch((profile + related + recent).map { it.id }, nowMs)
        return MemoryContext(profile, related, recent.sortedBy { it.createdAtMs })
    }

    fun relevant(query: String, limit: Int, nowMs: Long): List<MemoryItem> {
        val found = retriever.retrieve(query, store.all(), limit, nowMs)
        store.touch(found.map { it.id }, nowMs)
        return found
    }

    /**
     * Memories that contain the words of [query], best first, with the share of the query's words found (0..1).
     * Used by "забудь …" and the model's forget/search tools.
     */
    fun match(query: String, limit: Int = 5): List<Pair<MemoryItem, Double>> {
        val q = TextNorm.tokens(query).toSet()
        if (q.isEmpty()) return emptyList()
        return store.all().map { item ->
            val toks = TextNorm.tokens(item.text).toSet()
            item to q.count { it in toks }.toDouble() / q.size
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Double>> { it.second }.thenByDescending { it.first.createdAtMs })
            .take(limit)
    }

    fun all() = store.all()
    fun delete(id: Long) = store.delete(id)
    fun deleteAll() = store.deleteAll()

    /**
     * Restore from a backup. The same rules as for new memories apply: no passwords / card numbers,
     * length and count limits. Returns how many items were skipped.
     */
    fun replaceAll(items: List<MemoryItem>): Int {
        val ok = items.asSequence()
            .map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " "), importance = it.importance.coerceIn(1, 5)) }
            .filter { it.text.length in 3..maxTextLength && !looksSensitive(it.text) }
            .take(maxItems)
            .toList()
        store.replaceAll(ok)
        return items.size - ok.size
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size.toDouble()
        return inter / (a.size + b.size - inter) >= 0.8
    }

    companion object {
        private val cardLike = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val secretWords = Regex("""(парол|password|passcode|пин-?код|pin\b|cvv|cvc|seed|сид-фраз|api[ _-]?key|private[ _-]?key|токен|token\b)""", RegexOption.IGNORE_CASE)
        fun looksSensitive(text: String) = cardLike.containsMatchIn(text) || secretWords.containsMatchIn(text)
    }
}
