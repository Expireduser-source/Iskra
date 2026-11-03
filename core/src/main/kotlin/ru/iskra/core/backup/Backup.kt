package ru.iskra.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ru.iskra.core.agent.CompanionState
import ru.iskra.core.diag.Redactor
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.MoodState
import ru.iskra.core.persona.StyleProfile

/**
 * Owner's backup: long-term memory, settings and mood/style. Never API keys, never the chat log.
 *
 * Settings travel as a flat map of primitives (String / Boolean / Int / Long / Double) so the core
 * does not depend on the Android settings class; the app decides which names it accepts.
 */
data class Backup(
    val createdAtMs: Long,
    val appVersion: String,
    val settings: Map<String, Any>,
    val memory: List<MemoryItem>,
    val state: CompanionState?,
    val format: Int = BackupCodec.FORMAT,
)

class BackupException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

object BackupCodec {
    const val FORMAT = 1
    const val KIND = "iskra-backup"
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_MEMORY = 20_000
    const val MAX_TEXT = 4_000

    /** Setting names that can hold a credential. Such entries are never written or read. */
    private val forbiddenName = Regex("(?i)(key|token|secret|passw|auth|credential|bearer)")

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * Removes anything credential-like: forbidden setting names, setting values that contain a key
     * (e.g. an URL with `?key=`), the owner's actual keys, and key-shaped strings inside memory texts.
     */
    fun sanitize(b: Backup, knownSecrets: Collection<String> = emptyList()): Backup {
        val secrets = knownSecrets.map { it.trim() }.filter { it.length >= 6 }
        fun hasSecret(s: String) = secrets.any { s.contains(it) } || Redactor.redact(s) != s
        val settings = b.settings.filter { (name, v) ->
            !forbiddenName.containsMatchIn(name) &&
                (v is Boolean || v is Int || v is Long || v is Double || (v is String && !hasSecret(v)))
        }
        val memory = b.memory.map { m ->
            if (hasSecret(m.text)) m.copy(text = Redactor.redact(m.text, secrets)) else m
        }
        return b.copy(settings = settings, memory = memory)
    }

    fun encode(b: Backup, knownSecrets: Collection<String> = emptyList()): String {
        val s = sanitize(b, knownSecrets)
        val root = buildJsonObject {
            put("kind", KIND)
            put("format", FORMAT)
            put("createdAtMs", s.createdAtMs)
            put("appVersion", s.appVersion)
            put("note", "Резервная копия Искры. Ключей API и переписки здесь нет.")
            put("settings", buildJsonObject {
                s.settings.toSortedMap().forEach { (k, v) ->
                    when (v) {
                        is Boolean -> put(k, v)
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Double -> put(k, v)
                        is String -> put(k, v)
                    }
                }
            })
            s.state?.let { st ->
                put("state", buildJsonObject {
                    put("valence", st.mood.valence); put("arousal", st.mood.arousal); put("bond", st.mood.bond)
                    put("moodAt", st.mood.updatedAtMs)
                    put("verbosity", st.style.verbosity); put("humor", st.style.humor); put("catSpeech", st.style.catSpeech)
                    put("lastMsg", st.lastUserMessageAtMs); put("total", st.totalUserMessages)
                })
            }
            put("memory", buildJsonArray {
                s.memory.forEach { m ->
                    add(buildJsonObject {
                        put("kind", m.kind.name); put("source", m.source.name)
                        put("importance", m.importance)
                        put("created", m.createdAtMs); put("lastUsed", m.lastUsedAtMs)
                        put("text", m.text)
                    })
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Parses and validates a backup file. Messages in [BackupException] are for the owner. */
    fun decode(text: String, knownSecrets: Collection<String> = emptyList()): Backup {
        if (text.length > MAX_BYTES) throw BackupException("Файл слишком большой для резервной копии Искры.")
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException("Это не файл резервной копии Искры (не JSON).", e)
        }
        if (root["kind"]?.jsonPrimitive?.content != KIND) throw BackupException("Это не файл резервной копии Искры.")
        val format = root["format"]?.jsonPrimitive?.intOrNull ?: throw BackupException("В файле нет версии формата.")
        if (format > FORMAT) throw BackupException("Копия сделана более новой версией Искры (формат $format). Обнови приложение.")
        if (format < 1) throw BackupException("Неизвестная версия формата: $format.")
        try {
            val settings = LinkedHashMap<String, Any>()
            (root["settings"] as? JsonObject)?.forEach { (k, el) ->
                val p = el as? JsonPrimitive ?: return@forEach
                val v: Any? = when {
                    p.isString -> p.content
                    p.booleanOrNull != null -> p.booleanOrNull
                    p.intOrNull != null -> p.intOrNull
                    p.longOrNull != null -> p.longOrNull
                    else -> p.doubleOrNull
                }
                if (v != null) settings[k] = v
            }
            val memory = (root["memory"] as? JsonArray ?: JsonArray(emptyList())).take(MAX_MEMORY).mapIndexedNotNull { i, el ->
                val o = el.jsonObject
                val t = o["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (t.isEmpty()) return@mapIndexedNotNull null
                MemoryItem(
                    id = (i + 1).toLong(),
                    kind = runCatching { MemoryKind.valueOf(o["kind"]!!.jsonPrimitive.content) }.getOrDefault(MemoryKind.FACT),
                    text = t.take(MAX_TEXT),
                    source = runCatching { MemorySource.valueOf(o["source"]!!.jsonPrimitive.content) }.getOrDefault(MemorySource.USER_EXPLICIT),
                    importance = (o["importance"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 5),
                    createdAtMs = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
                    lastUsedAtMs = o["lastUsed"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
            val state = (root["state"] as? JsonObject)?.let { o ->
                fun d(k: String, def: Double) = o[k]?.jsonPrimitive?.doubleOrNull ?: def
                CompanionState(
                    mood = MoodState(d("valence", 0.2), d("arousal", 0.4), d("bond", 0.1), o["moodAt"]?.jsonPrimitive?.longOrNull ?: 0L).clamp(),
                    style = StyleProfile(d("verbosity", 0.0), d("humor", 0.0), d("catSpeech", 0.0)).clamp(),
                    lastUserMessageAtMs = o["lastMsg"]?.jsonPrimitive?.longOrNull ?: 0L,
                    totalUserMessages = o["total"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
            val b = Backup(
                createdAtMs = root["createdAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                appVersion = root["appVersion"]?.jsonPrimitive?.content.orEmpty().take(40),
                settings = settings, memory = memory, state = state, format = format,
            )
            // A file edited by hand must not smuggle a key into the settings either.
            return sanitize(b, knownSecrets)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Файл резервной копии повреждён.", e)
        }
    }

    /** Stable comparison of memory ignoring local row ids. */
    fun sameMemory(a: List<MemoryItem>, b: List<MemoryItem>): Boolean =
        a.map { it.copy(id = 0) } == b.map { it.copy(id = 0) }
}
