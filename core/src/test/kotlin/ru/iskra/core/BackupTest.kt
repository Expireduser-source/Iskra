package ru.iskra.core

import ru.iskra.core.agent.CompanionState
import ru.iskra.core.backup.Backup
import ru.iskra.core.backup.BackupCodec
import ru.iskra.core.backup.BackupException
import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryItem
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.persona.MoodState
import ru.iskra.core.persona.StyleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val llmKey = "sk-or-v1-0123456789abcdef0123456789abcdef0123"
    private val ttsKey = "MyYandexServiceKey-Plain42"

    private val memory = listOf(
        MemoryItem(7, MemoryKind.FACT, "Хозяина зовут Лёша", MemorySource.USER_EXPLICIT, 5, 1_000, 2_000),
        MemoryItem(9, MemoryKind.PREFERENCE, "Любит зелёный чай \"без сахара\"\nи кошек", MemorySource.MODEL_PROPOSED, 3, 3_000, 4_000),
        MemoryItem(12, MemoryKind.EPISODE, "Обсуждали поездку в Казань", MemorySource.SUMMARY, 2, 5_000, 6_000),
    )
    private val state = CompanionState(
        mood = MoodState(0.55, 0.3, 0.42, 123_456),
        style = StyleProfile(-0.25, 0.5, 0.75),
        lastUserMessageAtMs = 99_000, totalUserMessages = 314,
    )
    private val settings: Map<String, Any> = mapOf(
        "preset" to "OPENROUTER", "model" to "deepseek/deepseek-chat", "baseUrl" to "https://openrouter.ai/api/v1",
        "tts" to true, "avatarSize" to 140, "ttsEngine" to "YANDEX", "ttsVoice" to "jane", "wakeNames" to "Искра, Искорка",
        "liveliness" to 2, "followUp" to false,
    )
    private val backup = Backup(createdAtMs = 1_762_000_000_000, appVersion = "0.3.2", settings = settings, memory = memory, state = state)

    @Test fun exportNeverContainsKeys() {
        val dirty = backup.copy(
            settings = settings + mapOf(
                "apiKey" to llmKey,                                    // forbidden name
                "tts_key" to ttsKey,                                   // forbidden name
                "ttsAuthToken" to "abc",                               // forbidden name, short value
                "ttsBaseUrl" to "https://tts.example.com/v1?key=$ttsKey", // key inside a value
                "comment" to "мой ключ $llmKey",                          // key-shaped value
            ),
            memory = memory + MemoryItem(20, MemoryKind.FACT, "ключ от сервиса: $llmKey", MemorySource.USER_EXPLICIT, 3, 1, 1),
        )
        val json = BackupCodec.encode(dirty, knownSecrets = listOf(llmKey, ttsKey))
        assertFalse(json.contains(llmKey), json)
        assertFalse(json.contains(ttsKey), json)
        for (name in listOf("apiKey", "tts_key", "ttsAuthToken", "ttsBaseUrl", "comment")) assertFalse(json.contains("\"$name\""), "$name exported")
        assertTrue(json.contains("\"format\": ${BackupCodec.FORMAT}"))
        assertTrue(json.contains("\"preset\": \"OPENROUTER\""))
    }

    @Test fun ownerKeyOfAnyShapeIsDroppedFromSettings() {
        val b = backup.copy(settings = mapOf("wakeNames" to "Искра, $ttsKey"))
        val json = BackupCodec.encode(b, knownSecrets = listOf(ttsKey))
        assertFalse(json.contains(ttsKey))
        assertFalse(json.contains("wakeNames"))
    }

    @Test fun exportThenImportGivesSameData() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup.settings, restored.settings)
        assertTrue(BackupCodec.sameMemory(backup.memory, restored.memory), "${restored.memory}")
        assertEquals(backup.state, restored.state)
        assertEquals(backup.createdAtMs, restored.createdAtMs)
        assertEquals(backup.appVersion, restored.appVersion)
        assertEquals(BackupCodec.FORMAT, restored.format)
        // Types survive: Int stays Int, Boolean stays Boolean.
        assertEquals(140, restored.settings["avatarSize"])
        assertEquals(false, restored.settings["followUp"])
    }

    @Test fun roundTripThroughMemoryStoreKeepsTimestamps() {
        val mm = MemoryManager(InMemoryMemoryStore())
        val skipped = mm.replaceAll(BackupCodec.decode(BackupCodec.encode(backup)).memory)
        assertEquals(0, skipped)
        val again = BackupCodec.decode(BackupCodec.encode(backup.copy(memory = mm.all())))
        // Text whitespace is normalised by the memory rules; everything else is identical.
        assertEquals(memory.map { it.createdAtMs to it.lastUsedAtMs }, again.memory.map { it.createdAtMs to it.lastUsedAtMs })
        assertEquals(memory.map { it.kind to it.source }, again.memory.map { it.kind to it.source })
    }

    @Test fun importSkipsPasswordsLikeNormalMemory() {
        val mm = MemoryManager(InMemoryMemoryStore())
        val skipped = mm.replaceAll(memory + MemoryItem(1, MemoryKind.FACT, "пароль от почты qwerty123", MemorySource.USER_EXPLICIT, 3, 1, 1))
        assertEquals(1, skipped)
        assertEquals(3, mm.all().size)
    }

    @Test fun handEditedFileCannotSmuggleKeys() {
        val text = """{"kind":"iskra-backup","format":1,"createdAtMs":1,"appVersion":"x",
            "settings":{"apiKey":"$llmKey","model":"m","baseUrl":"https://x.io/v1?api_key=$ttsKey"},"memory":[]}"""
        val b = BackupCodec.decode(text)
        assertEquals(mapOf<String, Any>("model" to "m"), b.settings)
    }

    @Test fun rejectsForeignBrokenAndNewerFiles() {
        assertFailsWith<BackupException> { BackupCodec.decode("hello") }
        assertFailsWith<BackupException> { BackupCodec.decode("""{"kind":"something-else","format":1}""") }
        assertFailsWith<BackupException> { BackupCodec.decode("""{"kind":"iskra-backup"}""") }
        val newer = assertFailsWith<BackupException> { BackupCodec.decode("""{"kind":"iskra-backup","format":99}""") }
        assertTrue(newer.userMessage.contains("новее") || newer.userMessage.contains("новой"))
        assertFailsWith<BackupException> { BackupCodec.decode("""{"kind":"iskra-backup","format":1,"memory":[1,2]}""") }
    }
}

class BackupNotesTest {
    @org.junit.Test fun notesRoundTripAndKeysAreRedacted() {
        val key = "sk-or-v1-0123456789abcdef0123456789abcdef"
        val b = ru.iskra.core.backup.Backup(1L, "0.7.0", emptyMap(), emptyList(), null,
            notes = listOf(ru.iskra.core.skills.Note(1, "купить молоко", 5L), ru.iskra.core.skills.Note(2, "ключ $key", 6L)))
        val text = ru.iskra.core.backup.BackupCodec.encode(b, listOf(key))
        kotlin.test.assertFalse(text.contains(key))
        val back = ru.iskra.core.backup.BackupCodec.decode(text, listOf(key))
        kotlin.test.assertEquals(listOf("купить молоко", 5L), listOf(back.notes[0].text, back.notes[0].createdAtMs))
        kotlin.test.assertEquals(2, back.notes.size)
        // Files without notes (format 1 from 0.6) still load.
        kotlin.test.assertEquals(emptyList(), ru.iskra.core.backup.BackupCodec.decode(ru.iskra.core.backup.BackupCodec.encode(b.copy(notes = emptyList()))).notes)
    }
}
