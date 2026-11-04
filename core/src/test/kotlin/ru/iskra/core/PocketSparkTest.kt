package ru.iskra.core

import ru.iskra.core.skills.PocketSpark
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PocketSparkTest {
    @Test fun onlyExplicitRequestsMatch() {
        listOf("Удиви меня", " Искра, удиви меня! ", "искорка дня", "Искорка удиви меня…")
            .forEach { assertTrue(PocketSpark.matches(it), it) }
        listOf("не удиви меня", "запомни: удиви меня", "что значит удиви меня", "удиви меня и позвони 112", "")
            .forEach { assertFalse(PocketSpark.matches(it), it) }
    }

    @Test fun invitationsStayShortAndDoNotRepeatRecentChoices() {
        val sparks = PocketSpark(Random(17))
        val recent = ArrayDeque<String>()
        repeat(100) {
            val line = sparks.next()
            assertFalse(line in recent)
            assertTrue(line.length <= 90, line)
            recent.addLast(line)
            if (recent.size > 3) recent.removeFirst()
        }
    }
}
