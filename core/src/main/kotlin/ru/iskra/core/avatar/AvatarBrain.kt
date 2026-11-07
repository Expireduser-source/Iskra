package ru.iskra.core.avatar

import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** What the body is doing. Talking/listening/thinking are separate flags so she can e.g. talk while hanging. */
enum class Activity { IDLE, WALKING, RUNNING, JUMPING, FALLING, DRAGGED, CLIMBING, HANGING, SITTING, PEEKING, HIDDEN, SLEEPING }

enum class Edge { LEFT, RIGHT, TOP }

/** Everything the renderer needs for one frame. Pure data — no Android types. */
data class AvatarPose(
    val x: Float,               // top-left of the character box in screen px (may be partly off-screen)
    val y: Float,
    val facingRight: Boolean,
    val activity: Activity,
    val emotion: Emotion,
    val eyeOpen: Float,         // 0 closed .. 1 open
    val mouthOpen: Float,       // 0 .. 1
    val bob: Float,             // vertical body offset in "character units"
    val tailPhase: Float,       // radians
    val earTwitch: Float,       // 0 .. 1
    val tilt: Float,            // degrees, head tilt
    val legPhase: Float,        // walk/climb cycle radians
    val talking: Boolean = false,
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val wallSide: Int = 0,      // -1 clinging to the left edge, +1 right, 0 none
    val rotation: Float = 0f,   // whole-body rotation around the box centre, degrees
    val lookX: Float = 0f,      // -1 .. 1, where the eyes look (screen space)
    val moving: Boolean = false, // position or limbs change every frame → render at full refresh rate
    val waving: Boolean = false, // greeting: on appearing and when tapped while standing
    /** 0 → 1 over her first moment on the screen (and again when she comes back): a little burst of sparks. */
    val appear: Float = 1f,
    /** 0 → 1 while hearts rise after she was petted; negative when there are none. */
    val hearts: Float = -1f,
)

/**
 * Behaviour/animation state machine for the floating character. Deterministic given the random
 * seed and the sequence of [update] calls, so it is unit-tested on the JVM.
 *
 * Coordinates: screen pixels; the character occupies a [size] x [size] box. The box may leave the
 * screen (peeking, hiding); the app clips the window to the visible part.
 *
 * Passive life (when [wanderEnabled]): walks, jumps, sits, climbs a side edge and clings to it,
 * crawls along the top edge, runs off-screen and peeks back from a side or from above.
 * Thrown against an edge, she grabs it.
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
    private var nextIdleDecision = 5f
    private var earTwitchT = -1f
    private var lookX = 0f
    private var lookUntil = 0f
    private var waveUntil = 2.5f           // she waves when she appears
    private var appearAt = 0f              // when she (re)appeared: the sparks of 0.16.0
    private var pettedAt = -10f

    // Expression flags
    var isTalking = false; private set
    private var talkUntil = 0f
    private var lipSync = false
    private var mouthLevel = 0f
    var isListening = false; private set
    var isThinking = false; private set

    // Behaviour state
    private enum class Then { NOTHING, CLIMB, HIDE }
    private var targetX = x
    private var targetY = y
    private var then = Then.NOTHING
    private var phaseUntil = 0f
    private var wallSide = 0
    private var climbDir = 0               // -1 up, +1 down, 0 clinging
    private var toCeiling = false
    private var peekEdge = Edge.LEFT
    private var peekStage = 0              // 0 in, 1 hold, 2 out
    private var peekProgress = 0f
    private var peeksInRow = 0
    private var limbPhase = 0f

    /** Seconds without interaction before falling asleep. */
    var sleepAfterSec = 90f
    /** Whether the character may move on her own (owner can disable). */
    var wanderEnabled = true
    /** 0 calm, 1 normal, 2 lively — scales how often she starts something. */
    var liveliness = 1

    // Speeds relative to her size so they look the same on any screen density.
    private val gravity get() = size * 8f
    private val walkSpeed get() = size * 0.25f
    private val runSpeed get() = size * 1.1f
    private val climbSpeed get() = size * 0.45f
    private val crawlSpeed get() = size * 0.3f

    private fun floorY() = screenH - size
    private fun maxX() = (screenW - size).coerceAtLeast(0f)

    fun resize(w: Float, h: Float) {
        screenW = w; screenH = h
        if (activity == Activity.HIDDEN || activity == Activity.PEEKING) { comeBack(); return }
        x = x.coerceIn(0f, maxX())
        y = y.coerceIn(0f, (h - size).coerceAtLeast(0f))
        if (activity == Activity.CLIMBING) x = if (wallSide < 0) 0f else maxX()
    }

    fun setEmotion(e: Emotion, durationSec: Float = 6f) {
        emotion = e
        emotionUntil = t + durationSec
        wake()
    }

    fun setListening(on: Boolean) { isListening = on; if (on) { wake(); comeBack() } }
    fun setThinking(on: Boolean) { isThinking = on; if (on) wake() }

    /**
     * Start talking. With [lipSync] the mouth follows [setMouthLevel] until [stopTalking];
     * otherwise it is animated for roughly the time it takes to say [text].
     */
    fun talk(text: String, lipSync: Boolean = false) {
        this.lipSync = lipSync
        talkUntil = t + if (lipSync) 120f else (0.6f + text.length * 0.055f).coerceAtMost(12f)
        isTalking = true
        wake(); comeBack()
    }
    fun stopTalking() { talkUntil = 0f; isTalking = false; lipSync = false; mouthLevel = 0f }
    fun setMouthLevel(level: Float) { mouthLevel = level.coerceIn(0f, 1f) }

    /** Wave for [sec] seconds (shown while she stands). */
    fun wave(sec: Float = 1.5f) { waveUntil = t + sec }

    /** Petted (a double tap): hearts rise for a moment. */
    fun petted() { pettedAt = t; wake() }

    /** Back on the screen after stepping away (a chat was in front): the sparks of appearing, and a wave. */
    fun appearAgain() { appearAt = t; if (activity == Activity.IDLE) wave() }

    fun tapped() {
        if (activity == Activity.IDLE) wave()
        wake(); earTwitchT = 0f
        if (activity == Activity.PEEKING) { emotion = Emotion.SURPRISED; emotionUntil = t + 2f; comeBack() }
    }

    fun dragStart() {
        activity = Activity.DRAGGED; vx = 0f; vy = 0f; wallSide = 0; climbDir = 0; then = Then.NOTHING
        wake()
    }
    fun dragTo(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, maxX()); y = ny.coerceIn(0f, (screenH - size).coerceAtLeast(0f))
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
        nextIdleDecision = maxOf(nextIdleDecision, t + 4f)
    }

    private val busy get() = isTalking || isListening || isThinking

    // ---------------------------------------------------------------- behaviours

    private fun startWalk(tx: Float, run: Boolean, after: Then = Then.NOTHING) {
        targetX = tx; then = after
        activity = if (run) Activity.RUNNING else Activity.WALKING
    }

    private fun startJump() {
        val dir = when {
            x < size * 0.5f -> 1f
            x > maxX() - size * 0.5f -> -1f
            else -> if (random.nextBoolean()) 1f else -1f
        }
        facingRight = dir > 0
        vx = dir * size * (0.6f + random.nextFloat() * 1.2f)
        vy = -size * (3.2f + random.nextFloat() * 1.8f)
        activity = Activity.JUMPING
    }

    private fun cling(side: Int) {
        activity = Activity.CLIMBING
        wallSide = side; facingRight = side > 0
        x = if (side < 0) 0f else maxX()
        vx = 0f; vy = 0f; climbDir = 0; toCeiling = false
        phaseUntil = t + 2f + random.nextFloat() * 4f
    }

    private fun hang() {
        activity = Activity.HANGING
        wallSide = 0; climbDir = 0; vx = 0f; vy = 0f; y = 0f
        targetX = random.nextFloat() * maxX()
        phaseUntil = -1f // set when she reaches targetX
    }

    private fun startPeek() {
        peekEdge = when (random.nextInt(3)) { 0 -> Edge.LEFT; 1 -> Edge.RIGHT; else -> Edge.TOP }
        peekStage = 0; peekProgress = 0f
        activity = Activity.PEEKING
        when (peekEdge) {
            Edge.LEFT -> { y = screenH * 0.2f + random.nextFloat() * (floorY() - screenH * 0.2f); facingRight = true }
            Edge.RIGHT -> { y = screenH * 0.2f + random.nextFloat() * (floorY() - screenH * 0.2f); facingRight = false }
            Edge.TOP -> { x = random.nextFloat() * maxX() }
        }
        placePeek()
    }

    private fun placePeek() {
        val k = easeInOut(peekProgress)
        when (peekEdge) {
            Edge.LEFT -> x = lerp(-size, -size * 0.5f, k)
            Edge.RIGHT -> x = lerp(screenW, screenW - size * 0.5f, k)
            Edge.TOP -> y = lerp(-size, -size * 0.4f, k)
        }
    }

    /** Return from hiding/peeking to the visible screen (also used when she is needed). */
    private fun comeBack() {
        when (activity) {
            Activity.HIDDEN, Activity.PEEKING -> Unit
            else -> return
        }
        peeksInRow = 0
        if (activity == Activity.HIDDEN || peekEdge == Edge.TOP && random.nextBoolean()) {
            // Run in along the floor from the nearest side.
            val fromLeft = if (activity == Activity.HIDDEN) x < screenW / 2 else random.nextBoolean()
            x = if (fromLeft) -size else screenW
            y = floorY()
            facingRight = fromLeft
            startWalk(if (fromLeft) size * 0.3f + random.nextFloat() * size else maxX() - size * 0.3f - random.nextFloat() * size, run = true)
            return
        }
        when (peekEdge) {
            Edge.LEFT, Edge.RIGHT -> { // leap into the screen
                val dir = if (peekEdge == Edge.LEFT) 1f else -1f
                facingRight = dir > 0
                vx = dir * size * 1.6f; vy = -size * 2.2f
                activity = Activity.JUMPING
            }
            Edge.TOP -> { y = -size * 0.4f; vx = 0f; vy = 0f; activity = Activity.FALLING }
        }
    }

    private fun decide() {
        val e = emotion
        val lively = e == Emotion.HAPPY || e == Emotion.EXCITED
        val low = e == Emotion.SLEEPY || e == Emotion.SAD
        val options = listOf(
            0 to 30f,                                              // walk
            1 to 14f * (if (lively) 2f else if (low) 0.3f else 1f), // jump
            2 to 12f * (if (low) 0.5f else 1f),                    // climb a side edge
            3 to 12f * (if (low) 2.5f else if (lively) 0.5f else 1f), // sit
            4 to 9f * (if (e == Emotion.CURIOUS) 1.8f else 1f) * (if (low) 0.4f else 1f), // run off & peek
            5 to 18f,                                              // look around / ear twitch
        )
        var r = random.nextFloat() * options.sumOf { it.second.toDouble() }.toFloat()
        val choice = options.first { r -= it.second; r <= 0f }.first
        when (choice) {
            0 -> startWalk((random.nextFloat() * maxX()), run = random.nextFloat() < 0.15f)
            1 -> startJump()
            2 -> {
                val left = x < screenW / 2
                startWalk(if (left) 0f else maxX(), run = random.nextFloat() < 0.4f, after = Then.CLIMB)
            }
            3 -> { activity = Activity.SITTING; phaseUntil = t + 8f + random.nextFloat() * 14f }
            4 -> {
                val left = x < screenW / 2
                startWalk(if (left) -size * 1.05f else screenW + size * 0.05f, run = true, after = Then.HIDE)
            }
            else -> { earTwitchT = 0f; lookX = if (random.nextBoolean()) 1f else -1f; lookUntil = t + 1.2f }
        }
    }

    private fun nextDecisionDelay(): Float {
        val scale = when (liveliness) { 0 -> 1.8f; 2 -> 0.5f; else -> 1f }
        return (7f + random.nextFloat() * 13f) * scale
    }

    // ---------------------------------------------------------------- simulation

    private fun physics(d: Float) {
        vy += gravity * d
        x += vx * d; y += vy * d
        vx *= exp(-0.9f * d)
        val high = y < floorY() - size * 0.6f
        val canGrab = high && (activity == Activity.JUMPING || abs(vx) > size * 1.8f)
        if (x < 0f && vx < 0f) {
            if (canGrab) { cling(-1); return }
            x = 0f; vx = -vx * 0.4f
        }
        if (x > maxX() && vx > 0f) {
            if (canGrab) { cling(1); return }
            x = maxX(); vx = -vx * 0.4f
        }
        if (y < 0f && vy < 0f) {
            if (activity == Activity.FALLING && vy < -size * 2.5f) { x = x.coerceIn(0f, maxX()); hang(); return }
            y = 0f; vy = -vy * 0.3f
        }
        if (y >= floorY()) {
            y = floorY()
            if (abs(vy) > size * 2.7f) vy = -vy * 0.3f
            else {
                vy = 0f; vx = 0f; activity = Activity.IDLE
                x = x.coerceIn(0f, maxX())
                nextIdleDecision = t + 2f + random.nextFloat() * 4f
            }
        }
    }

    /** Advance the simulation by [dt] seconds and return the pose to draw. */
    fun update(dt: Float): AvatarPose {
        val d = dt.coerceIn(0f, 0.1f) // avoid huge jumps after the app was paused
        t += d
        if (emotion != Emotion.NEUTRAL && t > emotionUntil) emotion = Emotion.NEUTRAL
        if (isTalking && t > talkUntil && mouthLevel <= 0f) { isTalking = false; lipSync = false }
        if (t > lookUntil) lookX = 0f
        var moving = false

        when (activity) {
            Activity.FALLING, Activity.JUMPING -> { physics(d); moving = true }
            Activity.WALKING, Activity.RUNNING -> {
                val speed = if (activity == Activity.RUNNING) runSpeed else walkSpeed
                val dir = if (targetX > x) 1f else -1f
                facingRight = dir > 0
                x += dir * speed * d
                limbPhase += d * (if (activity == Activity.RUNNING) 16f else 9f)
                moving = true
                if (abs(targetX - x) <= speed * d + 1f) {
                    x = targetX
                    when (then) {
                        Then.CLIMB -> {
                            cling(if (x <= 0f) -1 else 1)
                            climbDir = -1
                            targetY = (floorY() - size * 0.8f - random.nextFloat() * (floorY() * 0.6f)).coerceAtLeast(0f)
                        }
                        Then.HIDE -> { activity = Activity.HIDDEN; phaseUntil = t + 3f + random.nextFloat() * 7f }
                        Then.NOTHING -> activity = Activity.IDLE
                    }
                    then = Then.NOTHING
                }
            }
            Activity.CLIMBING -> {
                if (climbDir != 0) {
                    moving = true
                    y += climbDir * climbSpeed * d
                    limbPhase += d * 8f
                    val reached = if (climbDir < 0) y <= targetY else y >= targetY
                    if (reached) {
                        y = targetY
                        when {
                            toCeiling -> hang()
                            y >= floorY() -> { wallSide = 0; activity = Activity.IDLE; climbDir = 0 }
                            else -> { climbDir = 0; phaseUntil = t + 3f + random.nextFloat() * 5f }
                        }
                    }
                } else if (t > phaseUntil && !busy) {
                    val r = random.nextFloat()
                    when {
                        r < 0.35f -> { // let go
                            wallSide.let { side -> vx = -side * size * 0.6f }
                            vy = 0f; wallSide = 0; activity = Activity.FALLING
                        }
                        r < 0.65f -> { climbDir = 1; targetY = floorY() }
                        else -> { climbDir = -1; targetY = 0f; toCeiling = true }
                    }
                }
            }
            Activity.HANGING -> {
                y = 0f
                if (phaseUntil < 0f) {
                    val dir = if (targetX > x) 1f else -1f
                    facingRight = dir > 0
                    x += dir * crawlSpeed * d
                    limbPhase += d * 7f
                    moving = true
                    if (abs(targetX - x) <= crawlSpeed * d + 1f) { x = targetX; phaseUntil = t + 2f + random.nextFloat() * 5f }
                } else if (t > phaseUntil && !busy) { vx = 0f; vy = 0f; activity = Activity.FALLING }
                else limbPhase += d * 2f // gentle swing
            }
            Activity.HIDDEN -> if (t > phaseUntil) {
                if (busy) comeBack() else startPeek()
            }
            Activity.PEEKING -> {
                moving = peekStage != 1
                when (peekStage) {
                    0 -> { peekProgress = (peekProgress + d / 0.6f).coerceAtMost(1f); if (peekProgress >= 1f) { peekStage = 1; phaseUntil = t + 2f + random.nextFloat() * 4f } }
                    1 -> if (t > phaseUntil) {
                        peeksInRow++
                        if (peeksInRow >= 2 || random.nextFloat() < 0.5f) comeBack() else peekStage = 2
                    }
                    else -> {
                        peekProgress = (peekProgress - d / 0.4f).coerceAtLeast(0f)
                        if (peekProgress <= 0f) { activity = Activity.HIDDEN; phaseUntil = t + 2f + random.nextFloat() * 5f }
                    }
                }
                if (activity == Activity.PEEKING) placePeek()
            }
            Activity.SITTING -> {
                if (t - lastInteraction > sleepAfterSec) activity = Activity.SLEEPING
                else if (t > phaseUntil) { activity = Activity.IDLE; nextIdleDecision = t + 3f }
            }
            Activity.IDLE -> {
                if (y < floorY() - 1f) activity = Activity.FALLING // e.g. after resize
                else if (t - lastInteraction > sleepAfterSec && !busy) activity = Activity.SLEEPING
                else if (wanderEnabled && !busy && t > nextIdleDecision) {
                    nextIdleDecision = t + nextDecisionDelay()
                    decide()
                }
            }
            Activity.DRAGGED, Activity.SLEEPING -> Unit
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
            lipSync -> mouthLevel
            mouthLevel > 0f -> mouthLevel
            isTalking -> (0.5f + 0.5f * sin(t * 18f)).coerceIn(0f, 1f)
            emotion == Emotion.SURPRISED -> 0.8f
            else -> 0f
        }
        val bobSpeed = when (activity) {
            Activity.SLEEPING -> 1.2f; Activity.WALKING -> 9f; Activity.RUNNING -> 16f
            else -> if (isTalking) 5f else 2.4f
        }
        val bobAmp = when (activity) {
            Activity.SLEEPING -> 1.5f; Activity.HANGING, Activity.CLIMBING, Activity.PEEKING, Activity.JUMPING, Activity.FALLING -> 0f
            Activity.SITTING -> 1.2f
            else -> 2.5f
        }
        val peekLean = if (activity == Activity.PEEKING) when (peekEdge) { Edge.LEFT -> 14f; Edge.RIGHT -> -14f; Edge.TOP -> 0f } else 0f
        val tilt = when {
            activity == Activity.DRAGGED -> 12f * sin(t * 6f)
            activity == Activity.PEEKING -> 8f * sin(t * 1.3f)
            isThinking -> 10f
            emotion == Emotion.CURIOUS -> 8f
            isListening -> -6f
            else -> 0f
        }
        val tailSpeed = when (emotion) { Emotion.EXCITED, Emotion.HAPPY -> 6f; Emotion.POUTY -> 10f; else -> 2f }
        val look = when {
            activity == Activity.PEEKING -> when (peekEdge) { Edge.LEFT -> 1f; Edge.RIGHT -> -1f; Edge.TOP -> 0f }
            else -> lookX
        }

        return AvatarPose(
            x = x, y = y, facingRight = facingRight, activity = activity, emotion = emotion,
            eyeOpen = eye.coerceIn(0f, 1f), mouthOpen = mouth,
            bob = sin(t * bobSpeed) * bobAmp,
            tailPhase = t * tailSpeed, earTwitch = twitch, tilt = tilt,
            legPhase = limbPhase,
            talking = isTalking, listening = isListening, thinking = isThinking,
            wallSide = if (activity == Activity.CLIMBING) wallSide else 0,
            rotation = if (activity == Activity.PEEKING && peekEdge == Edge.TOP) 180f else peekLean,
            lookX = look,
            moving = moving || activity == Activity.DRAGGED,
            waving = activity == Activity.IDLE && t < waveUntil && !isThinking,
            appear = ((t - appearAt) / APPEAR_SEC).coerceIn(0f, 1f),
            hearts = ((t - pettedAt) / HEARTS_SEC).let { if (it in 0f..1f) it else -1f },
        )
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
    private fun easeInOut(k: Float) = k * k * (3f - 2f * k)

    companion object {
        const val APPEAR_SEC = 0.9f
        const val HEARTS_SEC = 1.6f
    }
}
