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
