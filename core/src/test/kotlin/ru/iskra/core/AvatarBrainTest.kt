package ru.iskra.core

import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarBrain
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarBrainTest {
    private fun brain() = AvatarBrain(1080f, 2400f, 200f, Random(1), startX = 500f, startY = 300f)

    @Test fun fallsToFloorAfterRelease() {
        val b = brain()
        b.dragStart(); b.dragTo(400f, 200f); b.dragEnd(0f, 0f)
        repeat(300) { b.update(1 / 60f) }
        assertEquals(2400f - 200f, b.y)
        assertEquals(Activity.IDLE, b.activity)
    }

    @Test fun staysInsideScreenWhenFlung() {
        val b = brain()
        b.dragStart(); assertTrue(b.dragEnd(4000f, -3000f))
        repeat(600) {
            val p = b.update(1 / 60f)
            assertTrue(p.x in 0f..880f && p.y in 0f..2200f, "$p")
        }
    }

    @Test fun blinksAndSleeps() {
        val b = brain()
        b.wanderEnabled = false
        b.sleepAfterSec = 5f
        var sawClosed = false
        repeat(60 * 4) { if (b.update(1 / 60f).eyeOpen < 0.5f) sawClosed = true }
        assertTrue(sawClosed, "should blink within 4 s")
        repeat(60 * 3) { b.update(1 / 60f) }
        assertEquals(Activity.SLEEPING, b.activity)
        b.tapped()
        assertEquals(Activity.IDLE, b.activity)
    }

    @Test fun talkingEnds() {
        val b = brain()
        b.talk("Привет")
        assertEquals(Activity.TALKING, b.activity)
        repeat(120) { b.update(1 / 60f) }
        assertEquals(Activity.IDLE, b.activity)
    }
}
