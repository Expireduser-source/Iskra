package ru.iskra.core.diag

/**
 * Masks secrets in any text that may end up in the diagnostics log or the copied report.
 *
 * Two layers: exact values of the owner's saved keys (always replaced, whatever they look like),
 * and patterns of well-known key formats / auth headers, for keys that leak through error messages.
 */
object Redactor {
    /** Kept visible: first 4 characters (to tell keys apart) and the length. */
    fun mask(secret: String): String {
        val s = secret.trim()
        if (s.isEmpty()) return ""
        if (s.length <= 8) return "***"
        return s.take(4) + "…***(${s.length})"
    }

    private val patterns: List<Regex> = listOf(
        // Auth headers / query parameters: keep the name, mask the value.
        Regex("""(?i)(authorization\s*[:=]\s*(?:bearer|api-key|token)?\s*)([^\s"',;]{6,})"""),
        Regex("""(?i)(\b(?:x-api-key|xi-api-key|api[-_]?key|access[-_]?token|secret|password|token|key)\s*["']?\s*[:=]\s*["']?)([^\s"',;&]{6,})"""),
        Regex("""(?i)(\bbearer\s+)([A-Za-z0-9._~+/=-]{8,})"""),
        Regex("""(?i)(\bapi-key\s+)([A-Za-z0-9._~+/=-]{8,})"""),
        Regex("""(?i)([?&](?:key|api_key|apikey|token|access_token)=)([^&\s]{6,})"""),
    )

    /** Bare tokens in known formats (OpenAI/OpenRouter/Anthropic sk-…, Google AIza…, Yandex AQVN…/y0_…, GitHub, AWS, Slack, JWT). */
    private val bare: List<Regex> = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{12,}"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        Regex("""\bAQVN[0-9A-Za-z_-]{16,}"""),
        Regex("""\by[0-3]_[0-9A-Za-z_-]{20,}"""),
        Regex("""\bt1\.[0-9A-Za-z_.-]{20,}"""),
        Regex("""\b(?:ghp|gho|ghu|ghs|github_pat)_[A-Za-z0-9_]{16,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""),
        // ElevenLabs-style keys and other long opaque hex secrets.
        Regex("""\b(?:sk_)?[0-9a-f]{32,}\b"""),
    )

    /** [known] are the actual saved keys; they are removed even if they match no pattern. */
    fun redact(text: String, known: Collection<String> = emptyList()): String {
        var out = text
        for (k in known) {
            val key = k.trim()
            if (key.length >= 6) out = out.replace(key, mask(key))
        }
        for (p in patterns) out = p.replace(out) { m -> m.groupValues[1] + maskIfNotMasked(m.groupValues[2]) }
        for (p in bare) out = p.replace(out) { m -> maskIfNotMasked(m.value) }
        return out
    }

    private fun maskIfNotMasked(v: String) = if (v.contains("…***") || v == "***") v else mask(v)
}

/** One line of the diagnostics journal. Messages are short technical events, never dialogue text. */
data class DiagEntry(val timeMs: Long, val level: Level, val tag: String, val message: String) {
    enum class Level(val mark: Char) { INFO('I'), WARN('W'), ERROR('E') }

    /** One line, tab-separated; tabs/newlines inside fields are flattened. */
    fun encode(): String = "$timeMs\t${level.mark}\t${flat(tag)}\t${flat(message)}"

    companion object {
        fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        fun decode(line: String): DiagEntry? {
            val p = line.split('\t', limit = 4)
            if (p.size != 4) return null
            val t = p[0].toLongOrNull() ?: return null
            val lvl = Level.entries.firstOrNull { it.mark.toString() == p[1] } ?: return null
            return DiagEntry(t, lvl, p[2], p[3])
        }
    }
}

/**
 * Bounded journal: keeps the last [capacity] entries. Every message is redacted and length-capped
 * on the way in, so a secret never reaches memory, disk or the report. Thread-safe.
 */
class RingLog(
    val capacity: Int = 500,
    private val maxMessage: Int = 300,
    private val knownSecrets: () -> Collection<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val buf = ArrayDeque<DiagEntry>(capacity)

    fun add(level: DiagEntry.Level, tag: String, message: String): DiagEntry {
        val clean = Redactor.redact(DiagEntry.flat(message), knownSecrets()).let { if (it.length > maxMessage) it.take(maxMessage - 1) + "…" else it }
        val e = DiagEntry(clock(), level, DiagEntry.flat(tag).take(24), clean)
        synchronized(buf) {
            buf.addLast(e)
            while (buf.size > capacity) buf.removeFirst()
        }
        return e
    }

    fun entries(): List<DiagEntry> = synchronized(buf) { buf.toList() }
    fun clear() = synchronized(buf) { buf.clear() }

    fun encode(): String = entries().joinToString("\n") { it.encode() }

    /** Loads a saved journal (older entries beyond capacity are dropped; broken lines are skipped). */
    fun load(text: String) {
        val parsed = text.lineSequence().mapNotNull { DiagEntry.decode(it) }.toList().takeLast(capacity)
        synchronized(buf) { buf.clear(); buf.addAll(parsed) }
    }
}

/** What the diagnostics screen knows about the phone and the app. Only non-sensitive facts. */
data class DiagInfo(
    val appVersion: String,
    val build: String,
    val device: String,
    val android: String,
    val vendorOs: String,
    val permissions: List<Pair<String, Boolean>>,
    val services: List<Pair<String, String>>,
    val settings: List<Pair<String, String>>,
    /** Build type, package and signing certificate, e.g. "release · ru.iskra.app · свой ключ, SHA-256 AE:2D…". */
    val signing: String = "",
)

/** Crash counter for the 1.0 criterion «7 дней без падений». */
object CrashHistory {
    private const val DAY = 86_400_000L

    /** Whole days since the last crash, or since [trackingSinceMs] if there was none. */
    fun daysWithout(times: List<Long>, nowMs: Long, trackingSinceMs: Long): Long =
        ((nowMs - (times.maxOrNull() ?: trackingSinceMs)) / DAY).coerceAtLeast(0)

    fun countSince(times: List<Long>, sinceMs: Long) = times.count { it >= sinceMs }

    fun summary(times: List<Long>, nowMs: Long, trackingSinceMs: Long): String {
        val week = countSince(times, nowMs - 7 * DAY)
        val days = daysWithout(times, nowMs, trackingSinceMs)
        val goal = if (days >= 7) " ✅ цель 1.0 достигнута" else " (цель 1.0 — 7)"
        return "за 7 дней: $week · без падений $days дн.$goal" + if (times.isNotEmpty()) " · всего ${times.size}" else ""
    }
}

object DiagReport {
    /**
     * Plain-text report to paste into a chat or issue. The final [Redactor] pass is a safety net:
     * even if a key slipped into a field, it leaves masked.
     */
    fun build(
        info: DiagInfo,
        log: List<DiagEntry>,
        knownSecrets: Collection<String> = emptyList(),
        maxLog: Int = 60,
        time: (Long) -> String = { it.toString() },
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== Искра: отчёт диагностики ===")
        sb.appendLine("Версия: ${info.appVersion} (сборка ${info.build})")
        if (info.signing.isNotEmpty()) sb.appendLine("Сборка и подпись: ${info.signing}")
        sb.appendLine("Телефон: ${info.device}")
        sb.appendLine("Android: ${info.android}")
        sb.appendLine("Оболочка: ${info.vendorOs}")
        sb.appendLine()
        sb.appendLine("-- Разрешения")
        info.permissions.forEach { (n, ok) -> sb.appendLine("${if (ok) "[x]" else "[ ]"} $n") }
        sb.appendLine()
        sb.appendLine("-- Сервисы")
        info.services.forEach { (n, v) -> sb.appendLine("$n: $v") }
        sb.appendLine()
        sb.appendLine("-- Настройки (ключи замаскированы)")
        info.settings.forEach { (n, v) -> sb.appendLine("$n: $v") }
        sb.appendLine()
        val errors = log.count { it.level == DiagEntry.Level.ERROR }
        val warns = log.count { it.level == DiagEntry.Level.WARN }
        sb.appendLine("-- Журнал: ${log.size} записей, ошибок $errors, предупреждений $warns; последние ${minOf(maxLog, log.size)}")
        log.takeLast(maxLog).forEach { sb.appendLine("${time(it.timeMs)} ${it.level.mark} ${it.tag}: ${it.message}") }
        sb.appendLine()
        sb.appendLine("В отчёте нет ключей API и текста переписки.")
        return Redactor.redact(sb.toString(), knownSecrets)
    }
}
