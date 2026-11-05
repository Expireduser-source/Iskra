package ru.iskra.core

import ru.iskra.core.memory.InMemoryMemoryStore
import ru.iskra.core.memory.MemoryKind
import ru.iskra.core.memory.MemoryManager
import ru.iskra.core.memory.MemorySource
import ru.iskra.core.memory.TextNorm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryTest {
    private val now = 1_700_000_000_000L

    @Test fun stemmingMatchesInflections() {
        assertEquals(TextNorm.stem("кошка"), TextNorm.stem("кошками"))
        assertTrue(TextNorm.tokens("Люблю кофе с корицей").isNotEmpty())
        assertEquals(TextNorm.stem("собаки"), TextNorm.stem("собака"))
    }

    @Test fun retrievalPrefersRelevant() {
        val m = MemoryManager(InMemoryMemoryStore())
        m.add("Владельца зовут Аня", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 5, now)
        m.add("Аня любит кофе с корицей", MemoryKind.PREFERENCE, MemorySource.USER_EXPLICIT, 3, now)
        m.add("У Ани есть собака по кличке Бублик", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, now)
        val r = m.relevant("как там моя собака?", 1, now)
        assertTrue(r.single().text.contains("Бублик"), r.toString())
        val coffee = m.relevant("сварить кофе", 1, now)
        assertTrue(coffee.single().text.contains("кофе"))
    }

    @Test fun rejectsSecretsAndDuplicates() {
        val m = MemoryManager(InMemoryMemoryStore())
        assertIs<MemoryManager.AddResult.Rejected>(m.add("мой пароль от почты qwerty", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, now))
        assertIs<MemoryManager.AddResult.Rejected>(m.add("карта 4111 1111 1111 1111", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, now))
        assertIs<MemoryManager.AddResult.Added>(m.add("Живу в Казани", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, now))
        assertIs<MemoryManager.AddResult.Duplicate>(m.add("живу в Казани.", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 3, now))
    }

    @Test fun evictsLeastImportantModelMemoryWhenFull() {
        val m = MemoryManager(InMemoryMemoryStore(), maxItems = 2)
        m.add("Факт первый важный", MemoryKind.FACT, MemorySource.USER_EXPLICIT, 5, now)
        m.add("Модель думает про чай", MemoryKind.FACT, MemorySource.MODEL_PROPOSED, 1, now)
        assertIs<MemoryManager.AddResult.Added>(m.add("Новый факт про горы", MemoryKind.FACT, MemorySource.MODEL_PROPOSED, 3, now))
        assertEquals(setOf("Факт первый важный", "Новый факт про горы"), m.all().map { it.text }.toSet())
    }
}

class MemoryRecallTest {
    private val m = ru.iskra.core.memory.MemoryManager(ru.iskra.core.memory.InMemoryMemoryStore())
    private fun add(text: String, importance: Int = 3, kind: ru.iskra.core.memory.MemoryKind = ru.iskra.core.memory.MemoryKind.FACT, at: Long = 0) =
        m.add(text, kind, ru.iskra.core.memory.MemorySource.USER_EXPLICIT, importance, at)

    @kotlin.test.Test fun russianStemsMeet() {
        val s = ru.iskra.core.memory.Stemmer::stem
        // Snowball keeps "живет" whole; the "live" concept joins them for search.
        kotlin.test.assertEquals(ru.iskra.core.memory.Concepts.of(s("живу")), ru.iskra.core.memory.Concepts.of(s("живёт")))
        kotlin.test.assertEquals(s("работаю"), s("работает"))
        kotlin.test.assertEquals(s("красивая"), s("красивый"))
        kotlin.test.assertEquals(s("running"), s("runs"))
        kotlin.test.assertEquals(s("puppies"), s("puppy"))
    }

    @kotlin.test.Test fun synonymsAndOtherLanguageFindTheFact() {
        add("У владельца пёс Рекс")
        add("Владелец работает дизайнером")
        add("Владелец любит кофе без сахара")
        fun top(q: String) = m.context(q, 0).related.first().text
        kotlin.test.assertEquals("У владельца пёс Рекс", top("как зовут мою собаку?"))
        kotlin.test.assertEquals("У владельца пёс Рекс", top("what's my dog's name"))
        kotlin.test.assertEquals("Владелец работает дизайнером", top("что у меня с работой"))
        kotlin.test.assertEquals("Владелец любит кофе без сахара", top("свари мне эспрессо"))
    }

    @kotlin.test.Test fun profileAndRecentConversationsAlwaysCome() {
        add("Владельца зовут Аня", importance = 5)
        repeat(12) { add("Факт номер $it про марки и почту", importance = 2) }
        add("Говорили про поездку в Казань", kind = ru.iskra.core.memory.MemoryKind.EPISODE, at = 10)
        add("Обсуждали новый ноутбук", kind = ru.iskra.core.memory.MemoryKind.EPISODE, at = 20)
        val c = m.context("какая погода", 100)
        kotlin.test.assertEquals(listOf("Владельца зовут Аня"), c.profile.map { it.text })
        kotlin.test.assertEquals(listOf("Говорили про поездку в Казань", "Обсуждали новый ноутбук"), c.recent.map { it.text })
        kotlin.test.assertTrue(c.related.size <= 6)
    }
}
