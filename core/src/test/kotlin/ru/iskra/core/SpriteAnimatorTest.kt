package ru.iskra.core

import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarBrain
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.avatar.Sprite
import ru.iskra.core.avatar.SpriteAnimator
import ru.iskra.core.avatar.SpriteLayout
import ru.iskra.core.persona.Emotion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpriteLayoutTest {
    private val box = 330f // 120 dp on a 2.75x screen

    @Test
    fun feetOfEverySpriteStandOnOneLine() {
        Sprite.entries.forEach { s ->
            val k = SpriteLayout.scale(s, box)
            val feet = SpriteLayout.rect(s, box).top + s.feetY * k
            assertEquals(box * SpriteLayout.BASELINE, feet, 0.01f, s.name)
        }
    }

    @Test
    fun sitAndSleepHeadIsAsBigAsStanding() {
        val stand = SpriteLayout.headSize(Sprite.STAND, box)
        assertEquals(stand, SpriteLayout.headSize(Sprite.SIT, box), 0.01f)
        assertEquals(stand, SpriteLayout.headSize(Sprite.SLEEP, box), 0.01f)
        // Upright sprites are drawn by the artist at one size: one scale, no size jumps between emotions.
        val upright = Sprite.entries - Sprite.SIT - Sprite.SLEEP
        assertTrue(upright.all { SpriteLayout.scale(it, box) == SpriteLayout.scale(Sprite.STAND, box) })
        assertTrue(SpriteLayout.scale(Sprite.SLEEP, box) < SpriteLayout.scale(Sprite.STAND, box))
    }

    @Test
    fun everySpriteFitsTheBoxMirroredOrNot() {
        Sprite.entries.forEach { s ->
            val r = SpriteLayout.rect(s, box)
            val k = SpriteLayout.scale(s, box)
            val l = r.left + s.left * k
            val rt = r.left + s.right * k
            val top = r.top + s.top * k
            // Mirroring flips around the box centre, so both sides must fit.
            assertTrue(l >= 0f && rt <= box && box - rt >= 0f && box - l <= box, "${s.name}: $l..$rt")
            assertTrue(top >= 0f, "${s.name}: top $top")
        }
    }
}

class SpriteAnimatorTest {
    private fun pose(activity: Activity = Activity.IDLE, emotion: Emotion = Emotion.NEUTRAL, facingRight: Boolean = false,
                     thinking: Boolean = false, waving: Boolean = false) =
        AvatarPose(0f, 0f, facingRight, activity, emotion, 1f, 0f, 0f, 0f, 0f, 0f, 0f, thinking = thinking, waving = waving)

    @Test
    fun walkingAlternatesTwoFramesAtAboutSixPerSecond() {
        val a = SpriteAnimator()
        val dt = 1f / 120f
        var changes = 0
        var last: Sprite? = null
        repeat(120) {
            val f = a.update(dt, pose(Activity.WALKING))
            assertEquals(1, f.layers.size, "walk frames are cut, not faded")
            assertTrue(f.sprite == Sprite.WALK01 || f.sprite == Sprite.WALK02)
            if (last != null && f.sprite != last) changes++
            last = f.sprite
        }
        assertTrue(changes in 5..6, "frame changes in 1 s: $changes")
        assertEquals(1f, a.update(dt, pose(Activity.WALKING)).scaleY, "no breathing while walking")
    }

    @Test
    fun emotionChangeCrossfadesShortly() {
        val a = SpriteAnimator()
        a.update(0.1f, pose())
        val f = a.update(0.016f, pose(emotion = Emotion.HAPPY))
        assertEquals(listOf(Sprite.STAND, Sprite.JOY), f.layers.map { it.sprite })
        assertTrue(a.fading)
        var t = 0.016f
        while (a.fading) { a.update(0.016f, pose(emotion = Emotion.HAPPY)); t += 0.016f }
        assertTrue(t <= SpriteAnimator.FADE_SEC + 0.02f, "fade took $t s")
        assertEquals(listOf(Sprite.JOY), a.update(0.016f, pose(emotion = Emotion.HAPPY)).layers.map { it.sprite })
    }

    @Test
    fun crossfadeNeverMakesHerSeeThrough() {
        val a = SpriteAnimator()
        a.update(0.1f, pose())
        a.update(0.001f, pose(emotion = Emotion.SAD))
        while (a.fading) {
            val f = a.update(0.01f, pose(emotion = Emotion.SAD))
            assertTrue(f.layers.maxOf { it.alpha } >= 0.999f)
        }
    }

    @Test
    fun breathesOneToTwoPercentWhenStill() {
        listOf(Activity.IDLE, Activity.SITTING, Activity.SLEEPING).forEach { act ->
            val a = SpriteAnimator()
            var min = 1f; var max = 0f
            repeat(600) { val s = a.update(1f / 60f, pose(act)).scaleY; min = minOf(min, s); max = maxOf(max, s) }
            assertTrue(max <= 1f && min >= 0.98f && min <= 0.99f, "$act: $min..$max")
        }
    }

    @Test
    fun mirroredWhenFacingRight() {
        val a = SpriteAnimator()
        assertEquals(false, a.update(0.1f, pose(facingRight = false)).layers.last().mirror)
        val f = a.update(0.1f, pose(facingRight = true))
        assertEquals(1, f.layers.size, "turning around is not a crossfade")
        assertEquals(true, f.layers.last().mirror)
    }

    @Test
    fun ownSpritesHaveNoFallbackNote() {
        assertEquals(Sprite.STAND to null, SpriteAnimator.choose(pose()))
        assertEquals(Sprite.SIT to null, SpriteAnimator.choose(pose(Activity.SITTING)))
        assertEquals(Sprite.SLEEP to null, SpriteAnimator.choose(pose(Activity.SLEEPING)))
        assertEquals(Sprite.WAVE to null, SpriteAnimator.choose(pose(waving = true)))
        assertEquals(Sprite.PHONE to null, SpriteAnimator.choose(pose(thinking = true)))
        val emotions = mapOf(
            Emotion.HAPPY to Sprite.JOY, Emotion.SAD to Sprite.SAD, Emotion.POUTY to Sprite.ANGRY,
            Emotion.SURPRISED to Sprite.SURPRISE, Emotion.SHY to Sprite.SHY, Emotion.THINKING to Sprite.THOUGHTFUL,
            Emotion.SLEEPY to Sprite.SLEEPY, Emotion.CURIOUS to Sprite.SLY,
        )
        emotions.forEach { (e, s) -> assertEquals(s to null, SpriteAnimator.choose(pose(emotion = e)), e.name) }
    }

    @Test
    fun missingStatesGetNearestSpriteAndANote() {
        for (act in listOf(Activity.JUMPING, Activity.FALLING, Activity.DRAGGED, Activity.CLIMBING, Activity.HANGING, Activity.RUNNING)) {
            val (_, note) = SpriteAnimator.choose(pose(act))
            assertNotNull(note, act.name)
        }
        val (s, note) = SpriteAnimator.choose(pose(Activity.SITTING, Emotion.HAPPY))
        assertEquals(Sprite.SIT, s); assertNotNull(note)
        // Every combination yields some sprite.
        Activity.entries.forEach { act -> Emotion.entries.forEach { e -> SpriteAnimator.choose(pose(act, e)) } }
    }

    @Test
    fun brainWavesOnAppearingAndOnTap() {
        val b = AvatarBrain(1080f, 2400f, 300f, Random(1), startX = 500f, startY = 2100f).apply { wanderEnabled = false }
        assertTrue(b.update(0.016f).waving)
        var p = b.update(0.016f)
        repeat(300) { p = b.update(0.016f) }
        assertTrue(!p.waving, "greeting ends")
        b.tapped()
        assertTrue(b.update(0.016f).waving)
        b.setThinking(true)
        assertNull(SpriteAnimator.choose(b.update(0.016f)).second)
        assertEquals(Sprite.PHONE, SpriteAnimator.choose(b.update(0.016f)).first)
    }
}
