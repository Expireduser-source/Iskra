package ru.iskra.core

import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.avatar.RigAnimator
import ru.iskra.core.avatar.RigState
import ru.iskra.core.avatar.Sprite
import ru.iskra.core.avatar.Visual
import ru.iskra.core.persona.Emotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RigAnimatorTest {
    private fun pose(activity: Activity = Activity.IDLE, emotion: Emotion = Emotion.NEUTRAL, x: Float = 100f,
                     thinking: Boolean = false, waving: Boolean = false, listening: Boolean = false) =
        AvatarPose(x, 0f, false, activity, emotion, 1f, 0f, 0f, 0f, 0f, 0f, 0f,
            thinking = thinking, listening = listening, waving = waving)

    @Test
    fun sitSleepPhoneAreSpritesEverythingElseIsTheRig() {
        assertEquals(Visual.Picture(Sprite.SIT), RigAnimator.choose(pose(Activity.SITTING)).first)
        assertEquals(Visual.Picture(Sprite.SLEEP), RigAnimator.choose(pose(Activity.SLEEPING)).first)
        assertEquals(Visual.Picture(Sprite.PHONE), RigAnimator.choose(pose(thinking = true)).first)
        assertEquals(Visual.Rig to null, RigAnimator.choose(pose()))
        assertEquals(Visual.Rig to null, RigAnimator.choose(pose(Activity.WALKING)))
        // 0.11: the rig has its own pose for everything in the air, so no "no own pose" note any more.
        listOf(Activity.RUNNING, Activity.JUMPING, Activity.FALLING, Activity.DRAGGED, Activity.CLIMBING, Activity.HANGING, Activity.PEEKING)
            .forEach { assertEquals(Visual.Rig to null, RigAnimator.choose(pose(it)), "$it") }
        Activity.entries.forEach { RigAnimator.choose(pose(it)) }
    }

    @Test
    fun rigToSitCrossfades() {
        val a = RigAnimator()
        a.update(0.1f, pose(), 300f)
        val f = a.update(0.02f, pose(Activity.SITTING), 300f)
        assertEquals(listOf(Visual.Rig, Visual.Picture(Sprite.SIT)), f.layers.map { it.visual })
        repeat(20) { a.update(0.02f, pose(Activity.SITTING), 300f) }
        assertEquals(listOf(Visual.Picture(Sprite.SIT)), a.update(0.02f, pose(Activity.SITTING), 300f).layers.map { it.visual })
    }

    @Test
    fun poseDrivesGesturesAndEmotion() {
        val a = RigAnimator()
        a.update(0.1f, pose(), 300f)
        a.update(0.03f, pose(waving = true), 300f)
        assertEquals(RigState.Gesture.WAVE, a.rig.gesture)
        a.update(0.03f, pose(listening = true), 300f)
        assertEquals(RigState.Gesture.NOD, a.rig.gesture)
        a.update(0.03f, pose(emotion = Emotion.POUTY), 300f)
        assertEquals(RigState.Gesture.SHAKE, a.rig.gesture)
        assertEquals("angry", a.rig.expression)
        a.update(0.03f, pose(emotion = Emotion.CURIOUS), 300f)
        assertEquals("sly", a.rig.expression)
    }

    @Test
    fun startingToRunSwingsThePonytail() {
        val a = RigAnimator(); a.rig.energy = 0f
        repeat(60) { a.update(1f / 30f, pose(), 300f) }
        assertTrue(kotlin.math.abs(a.rig.pony.angle) < 0.01f)
        var x = 100f
        repeat(8) { i -> x += 300f * 1.1f / 30f * (i + 1) / 8f; a.update(1f / 30f, pose(Activity.RUNNING, x = x), 300f) }
        assertTrue(kotlin.math.abs(a.rig.pony.angle) > 0.02f, "pony ${a.rig.pony.angle}")
        assertNull(RigAnimator.choose(pose(Activity.WALKING)).second)
    }
}
