package ru.iskra.core

import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarBrain
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        b.wanderEnabled = false
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
        assertTrue(b.isTalking)
        repeat(120) { b.update(1 / 60f) }
        assertFalse(b.isTalking)
    }

    @Test fun lipSyncTalkLastsUntilStopped() {
        val b = brain()
        b.talk("Привет", lipSync = true)
        b.setMouthLevel(0.7f)
        assertEquals(0.7f, b.update(1 / 60f).mouthOpen)
        b.setMouthLevel(0f)
        repeat(600) { b.update(1 / 60f) }
        assertTrue(b.isTalking, "real audio may pause; only stopTalking ends it")
        assertEquals(0f, b.update(1 / 60f).mouthOpen)
        b.stopTalking()
        assertFalse(b.isTalking)
    }

    @Test fun grabsTheEdgeWhenThrownAtIt() {
        val b = brain()
        b.wanderEnabled = false
        b.dragStart(); b.dragTo(500f, 400f); b.dragEnd(-3000f, -200f)
        var p = b.update(1 / 60f)
        repeat(30) { p = b.update(1 / 60f) }
        assertEquals(Activity.CLIMBING, p.activity)
        assertEquals(0f, p.x)
        assertEquals(-1, p.wallSide)
    }

    @Test fun throwUpGrabsTheTop() {
        val b = brain()
        b.wanderEnabled = false
        b.dragStart(); b.dragTo(500f, 300f); b.dragEnd(0f, -4000f)
        var p = b.update(1 / 60f)
        repeat(30) { if (p.activity != Activity.HANGING) p = b.update(1 / 60f) }
        assertEquals(Activity.HANGING, p.activity)
        assertEquals(0f, p.y)
    }

    /** Long random simulation: she does the whole repertoire, never gets lost, never leaves for long. */
    @Test fun passiveLifeIsVariedAndSane() {
        for (seed in 1..3) {
            val b = AvatarBrain(1080f, 2400f, 300f, Random(seed), startX = 500f, startY = 2100f)
            b.liveliness = 2
            val seen = mutableSetOf<Activity>()
            var hiddenFor = 0f
            val dt = 1 / 120f   // 120 Hz display
            for (i in 0 until 120 * 60 * 30) { // 30 simulated minutes
                if (i % (120 * 60) == 0) b.tapped() // owner pokes her now and then, so she does not sleep
                val p = b.update(dt)
                seen += p.activity
                assertTrue(p.x >= -300f * 1.1f && p.x <= 1080f + 300f * 0.1f, "x out of range: $p")
                assertTrue(p.y >= -300f && p.y <= 2100f + 0.5f, "y out of range: $p")
                if (p.activity != Activity.HIDDEN && p.activity != Activity.PEEKING && p.activity != Activity.RUNNING && p.activity != Activity.JUMPING) {
                    assertTrue(p.x in -0.5f..780.5f, "visible states stay on screen: $p")
                }
                hiddenFor = if (p.activity == Activity.HIDDEN) hiddenFor + dt else 0f
                assertTrue(hiddenFor < 15f, "she must come back")
            }
            for (a in listOf(Activity.WALKING, Activity.JUMPING, Activity.CLIMBING, Activity.SITTING, Activity.HIDDEN, Activity.PEEKING, Activity.HANGING)) {
                assertTrue(a in seen, "seed $seed never did $a; saw $seen")
            }
        }
    }

    @Test fun comesBackWhenSpokenTo() {
        val b = AvatarBrain(1080f, 2400f, 300f, Random(7), startX = 500f, startY = 2100f)
        b.liveliness = 2
        var guard = 0
        while (b.activity != Activity.HIDDEN && b.activity != Activity.PEEKING && guard++ < 120 * 60 * 30) {
            if (guard % (120 * 60) == 0) b.tapped()
            b.update(1 / 120f)
        }
        assertTrue(b.activity == Activity.HIDDEN || b.activity == Activity.PEEKING, "should hide at some point")
        b.talk("Я тут!")
        repeat(120 * 4) { b.update(1 / 120f) }
        val p = b.update(1 / 120f)
        assertTrue(p.x in 0f..780f && p.activity != Activity.HIDDEN && p.activity != Activity.PEEKING, "$p")
    }
}
