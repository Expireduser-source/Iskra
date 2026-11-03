package ru.iskra.core.avatar

import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

enum class Activity { IDLE, WALKING, LISTENING, THINKING, TALKING, DRAGGED, FALLING, SLEEPING }

/** Everything the renderer needs for one frame. Pure data — no Android types. */
data class AvatarPose(
    val x: Float,               // top-left of the character box in screen px
    val y: Float,
    val facingRight: Boolean,
    val activity: Activity,
    val emotion: Emotion,
    val eyeOpen: Float,         // 0 closed .. 1 open
    val mouthOpen: Float,       // 0 .. 1
    val bob: Float,             // vertical body offset in "character units"
    val tailPhase: Float,       // radians
    val earTwitch: Float,       // 0 .. 1
    val tilt: Float,            // degrees, head/body tilt
    val legPhase: Float,        // walk cycle radians
)

/**
 * Behaviour/animation state machine for the floating character. Deterministic given the random
 * seed and the sequence of [update] calls, so it is unit-tested on the JVM.
 *
 * Coordinates: screen pixels; the character occupies a [size] x [size] box.
 */
class AvatarBrain(
    private var screenW: Float,
    private var screenH: Float,
    val size: Float,
    private val random: Random = Random.Default,
    startX: Float = screenW - size,
    startY: Float = screenH * 0.6f,
) {
    var x = startX; private set
    var y = startY; private set
    private var vx = 0f
    private var vy = 0f
    var activity = Activity.IDLE; private set
    var emotion = Emotion.NEUTRAL; private set
    private var emotionUntil = 0f
    private var facingRight = false
    private var t = 0f                     // seconds since start
    private var lastInteraction = 0f
    private var nextBlink = 2f
    private var blinkT = -1f
    private var walkTargetX = x
    private var nextIdleDecision = 5f
    private var talkUntil = 0f
    private var earTwitchT = -1f
    private var mouthLevel = 0f            // external (TTS amplitude) if > 0

    /** Seconds without interaction before falling asleep. */
    var sleepAfterSec = 90f
    /** Whether the character may wander on its own (owner can disable). */
    var wanderEnabled = true

    fun resize(w: Float, h: Float) {
        screenW = w; screenH = h
        x = x.coerceIn(0f, (w - size).coerceAtLeast(0f))
        y = y.coerceIn(0f, (h - size).coerceAtLeast(0f))
    }

    fun setEmotion(e: Emotion, durationSec: Float = 6f) {
        emotion = e
        emotionUntil = t + durationSec
        wake()
    }

    fun setListening(on: Boolean) { if (on) { activity = Activity.LISTENING; wake() } else if (activity == Activity.LISTENING) activity = Activity.IDLE }
    fun setThinking(on: Boolean) { if (on) { activity = Activity.THINKING; wake() } else if (activity == Activity.THINKING) activity = Activity.IDLE }

    /** Talk for roughly the time it takes to say [text] (used when TTS is off or as fallback). */
    fun talk(text: String) {
        talkUntil = t + (0.6f + text.length * 0.055f).coerceAtMost(12f)
        activity = Activity.TALKING
        wake()
    }
    fun stopTalking() { talkUntil = 0f; if (activity == Activity.TALKING) activity = Activity.IDLE }
    fun setMouthLevel(level: Float) { mouthLevel = level.coerceIn(0f, 1f) }

    fun tapped() { wake(); earTwitchT = 0f }

    fun dragStart() { activity = Activity.DRAGGED; vx = 0f; vy = 0f; wake() }
    fun dragTo(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, screenW - size); y = ny.coerceIn(0f, screenH - size)
    }
    /** Release with the finger velocity in px/s. Returns true if it was a "fling". */
    fun dragEnd(velX: Float, velY: Float): Boolean {
        vx = velX.coerceIn(-4000f, 4000f); vy = velY.coerceIn(-4000f, 4000f)
        activity = Activity.FALLING
        return abs(velX) + abs(velY) > 2500f
    }

    private fun wake() {
        lastInteraction = t
        if (activity == Activity.SLEEPING) activity = Activity.IDLE
    }

    private fun floorY() = screenH - size

    /** Advance the simulation by [dt] seconds and return the pose to draw. */
    fun update(dt: Float): AvatarPose {
        val d = dt.coerceIn(0f, 0.1f) // avoid huge jumps after the app was paused
        t += d
        if (emotion != Emotion.NEUTRAL && t > emotionUntil) emotion = Emotion.NEUTRAL

        when (activity) {
            Activity.FALLING -> {
                vy += 2600f * d
                x += vx * d; y += vy * d
                vx *= 0.985f
                if (x < 0f) { x = 0f; vx = -vx * 0.4f }
                if (x > screenW - size) { x = screenW - size; vx = -vx * 0.4f }
                if (y < 0f) { y = 0f; vy = -vy * 0.3f }
                if (y >= floorY()) {
                    y = floorY()
                    if (abs(vy) > 900f) vy = -vy * 0.3f else { vy = 0f; vx = 0f; activity = Activity.IDLE }
                }
            }
            Activity.WALKING -> {
                val dir = if (walkTargetX > x) 1f else -1f
                facingRight = dir > 0
                x += dir * 70f * d
                if (abs(walkTargetX - x) < 3f) { x = walkTargetX; activity = Activity.IDLE }
            }
            Activity.TALKING -> if (t > talkUntil && mouthLevel <= 0f) activity = Activity.IDLE
            Activity.IDLE -> {
                if (t - lastInteraction > sleepAfterSec) activity = Activity.SLEEPING
                else if (wanderEnabled && t > nextIdleDecision) {
                    nextIdleDecision = t + 8f + random.nextFloat() * 14f
                    // Only wander along the floor; otherwise stay put where the owner placed her.
                    if (y >= floorY() - 1f && random.nextFloat() < 0.5f) {
                        walkTargetX = (random.nextFloat() * (screenW - size)).coerceIn(0f, screenW - size)
                        activity = Activity.WALKING
                    } else earTwitchT = 0f
                }
            }
            else -> Unit
        }

        // Blinking (not while sleeping).
        if (blinkT >= 0f) { blinkT += d; if (blinkT > 0.16f) blinkT = -1f }
        else if (t > nextBlink) { blinkT = 0f; nextBlink = t + 2.5f + random.nextFloat() * 3.5f }
        val eye = when {
            activity == Activity.SLEEPING -> 0f
            emotion == Emotion.SLEEPY -> 0.35f
            blinkT >= 0f -> abs(blinkT - 0.08f) / 0.08f
            else -> 1f
        }

        if (earTwitchT >= 0f) { earTwitchT += d; if (earTwitchT > 0.3f) earTwitchT = -1f }
        val twitch = if (earTwitchT >= 0f) sin(earTwitchT / 0.3f * PI).toFloat() else 0f

        val mouth = when {
            mouthLevel > 0f -> mouthLevel
            activity == Activity.TALKING -> (0.5f + 0.5f * sin(t * 18f)).coerceIn(0f, 1f)
            emotion == Emotion.SURPRISED -> 0.8f
            else -> 0f
        }
        val bobSpeed = when (activity) { Activity.SLEEPING -> 1.2f; Activity.WALKING -> 9f; Activity.TALKING -> 5f; else -> 2.4f }
        val tilt = when {
            activity == Activity.DRAGGED -> 12f * sin(t * 6f)
            activity == Activity.THINKING -> 10f
            emotion == Emotion.CURIOUS -> 8f
            activity == Activity.LISTENING -> -6f
            else -> 0f
        }
        val tailSpeed = when (emotion) { Emotion.EXCITED, Emotion.HAPPY -> 6f; Emotion.POUTY -> 10f; else -> 2f }

        return AvatarPose(
            x = x, y = y, facingRight = facingRight, activity = activity, emotion = emotion,
            eyeOpen = eye.coerceIn(0f, 1f), mouthOpen = mouth,
            bob = sin(t * bobSpeed) * (if (activity == Activity.SLEEPING) 1.5f else 2.5f),
            tailPhase = t * tailSpeed, earTwitch = twitch, tilt = tilt,
            legPhase = if (activity == Activity.WALKING) t * 9f else 0f,
        )
    }
}
