package ru.iskra.core

import ru.iskra.core.tools.AppMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppMatcherTest {
    private val labels = listOf("Telegram", "YouTube", "YouTube Music", "Камера", "Настройки", "VK", "WhatsApp", "Chrome")

    @Test fun matchesTransliterationAndAliases() {
        assertEquals("Telegram", labels[AppMatcher.best("телеграм", labels)!!])
        assertEquals("Telegram", labels[AppMatcher.best("телега", labels)!!])
        assertEquals("YouTube", labels[AppMatcher.best("ютуб", labels)!!])
        assertEquals("VK", labels[AppMatcher.best("вконтакте", labels)!!])
        assertEquals("WhatsApp", labels[AppMatcher.best("ватсап", labels)!!])
        assertEquals("Камера", labels[AppMatcher.best("камера", labels)!!])
    }

    @Test fun noMatch() {
        assertNull(AppMatcher.best("музыку погромче", listOf("Telegram", "Chrome")))
    }
}
