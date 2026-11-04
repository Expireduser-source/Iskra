package ru.iskra.core.avatar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Live 2D rig of the character: 10 picture layers (assets/rig, prototype "iskra-live" by Astra) moved
 * by code, with a face (eyes, brows, mouth, blush) drawn as vector graphics.
 * Port of web/rig.js to Kotlin; drawing goes through [RigPainter], so the same code draws on Android's
 * Canvas and, in unit tests, into a PNG with Java2D.
 */

/** One picture layer: drawn into [rect] (model px), rotated around [pivot]. */
data class RigPart(val name: String, val file: String, val rect: List<Float>, val pivot: List<Float>)

/** `iskra.rig.json`. */
data class RigModel(val canvas: Float, val parts: Map<String, RigPart>) {
    companion object {
        val NAMES = listOf("tail", "legL", "legR", "torso", "armL", "armR", "head", "earL", "earR", "pony")

        fun parse(json: String): RigModel {
            val root = Json.parseToJsonElement(json).jsonObject
            require(root["format"]?.jsonPrimitive?.content == "iskra-rig-1") { "unknown rig format" }
            val parts = root["parts"]!!.jsonObject.mapValues { (name, v) ->
                val o = v.jsonObject
                fun nums(k: String) = o[k]!!.jsonArray.map { it.jsonPrimitive.float }
                RigPart(name, o["file"]!!.jsonPrimitive.content, nums("rect").also { require(it.size == 4) }, nums("pivot").also { require(it.size == 2) })
            }
            require(parts.keys.containsAll(NAMES)) { "rig parts missing: ${NAMES - parts.keys}" }
            return RigModel(root["canvas"]!!.jsonArray[0].jsonPrimitive.float, parts)
        }
    }
}

/** Minimal drawing surface, a subset of HTML canvas 2D. Colors are ARGB. Angles in radians. */
interface RigPainter {
    fun save()
    fun restore()
    fun translate(x: Float, y: Float)
    fun rotate(rad: Float)
    fun scale(x: Float, y: Float)
    fun image(part: String, left: Float, top: Float, right: Float, bottom: Float)
    fun beginPath()
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float)
    fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float)
    fun closePath()
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float)
    fun fill(color: Int)
    /** Round caps and joins. */
    fun stroke(color: Int, width: Float)
    /** Clips to the current path until the matching [restore]. */
    fun clip()
    /** Disc of [color] at [alpha] in the centre fading to transparent at [radius]. */
    fun glow(cx: Float, cy: Float, radius: Float, color: Int, alpha: Float)
}

/** Facial parameters; see [RigState.EXPRESSIONS]. */
data class Face(
    val eye: Float, val brow: Float, val smile: Float, val open: Float, val blush: Float,
    val asym: Float, val up: Float, val side: Float, val joy: Float, val fang: Float,
) {
    fun mix(o: Face, k: Float) = Face(
        mix(eye, o.eye, k), mix(brow, o.brow, k), mix(smile, o.smile, k), mix(open, o.open, k), mix(blush, o.blush, k),
        mix(asym, o.asym, k), mix(up, o.up, k), mix(side, o.side, k), mix(joy, o.joy, k), mix(fang, o.fang, k),
    )
}

internal fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

/** Angle with velocity: follows a target with inertia (critically under-damped spring). */
class Spring(private val stiffness: Float, private val damping: Float) {
    var angle = 0f; private set
    var velocity = 0f; private set
    /** [drive] is an extra angular acceleration (from the head or the body moving). */
    fun step(dt: Float, target: Float, drive: Float = 0f) {
        velocity += ((target - angle) * stiffness - velocity * damping - drive) * dt
        angle = (angle + velocity * dt).coerceIn(-0.9f, 0.9f)
    }
}

/**
 * Where the body and limbs go for an activity, as offsets from the standing rig (radians, model px).
 * Angles in screen terms: positive turns clockwise. armL up is +, armR up is −; ears > 0 flatten them.
 * Arms stay below ~1.4 rad: the big chibi head is drawn over them, a hand raised higher would vanish behind it.
 */
data class RigPose(
    val armL: Float = 0f, val armR: Float = 0f,
    val legL: Float = 0f, val legR: Float = 0f,
    /** Knees up (model px): the leg layer is lifted, so the feet tuck under her. */
    val tuck: Float = 0f,
    /** Whole-body tilt around the hips; negative leans forward (she faces image-left when not mirrored). */
    val lean: Float = 0f,
    /** > 0 squashed (wider and lower), < 0 stretched. */
    val squash: Float = 0f,
    val headTilt: Float = 0f,
    val tail: Float = 0f,
    val ears: Float = 0f,
    val hair: Float = 0f,
    /** 0..1 rhythmic parts: running stride, flailing arms and legs, alternating climbing reach, dangling legs. */
    val run: Float = 0f, val flail: Float = 0f, val climb: Float = 0f, val dangle: Float = 0f,
) {
    fun mix(o: RigPose, k: Float) = RigPose(
        mix(armL, o.armL, k), mix(armR, o.armR, k), mix(legL, o.legL, k), mix(legR, o.legR, k), mix(tuck, o.tuck, k),
        mix(lean, o.lean, k), mix(squash, o.squash, k), mix(headTilt, o.headTilt, k), mix(tail, o.tail, k), mix(ears, o.ears, k),
        mix(hair, o.hair, k), mix(run, o.run, k), mix(flail, o.flail, k), mix(climb, o.climb, k), mix(dangle, o.dangle, k),
    )

    companion object {
        val STAND = RigPose()

        /**
         * The rig's own poses for everything that had none (docs/AVATAR.md: "rig has no own pose").
         * [vy]: vertical speed in box sizes per second (negative = going up) — a jump stretches on the way up
         * and opens its arms on the way down.
         */
        fun forActivity(a: Activity, vy: Float = 0f): RigPose = when (a) {
            Activity.IDLE, Activity.WALKING, Activity.SITTING, Activity.SLEEPING, Activity.HIDDEN -> STAND
            Activity.RUNNING -> RigPose(lean = -0.11f, run = 1f, tail = 0.25f, ears = 0.12f, hair = 0.2f)
            Activity.JUMPING -> if (vy < 0f)
                RigPose(armL = 1.15f, armR = -1.15f, legL = 0.12f, legR = -0.12f, tuck = 34f, squash = -0.07f, tail = 0.35f, ears = 0.18f, hair = 0.25f)
            else RigPose(armL = 0.9f, armR = -0.9f, legL = 0.22f, legR = -0.22f, tuck = 14f, squash = -0.03f, tail = 0.15f, ears = -0.1f, hair = -0.25f)
            Activity.FALLING -> RigPose(armL = 1.05f, armR = -1.05f, legL = 0.28f, legR = -0.28f, squash = -0.04f, tail = 0.5f,
                ears = -0.22f, hair = -0.4f, flail = 1f)
            Activity.DRAGGED -> RigPose(armL = 0.55f, armR = -0.55f, legL = 0.05f, legR = -0.05f, squash = -0.05f, tail = -0.15f,
                ears = 0.1f, dangle = 1f)
            Activity.CLIMBING -> RigPose(armL = 1.25f, armR = -1.25f, legL = 0.1f, legR = -0.1f, tuck = 10f, lean = -0.05f, tail = 0.2f, climb = 1f)
            Activity.HANGING -> RigPose(armL = 1.32f, armR = -1.32f, squash = -0.06f, tail = -0.3f, ears = 0.06f, hair = 0.1f, dangle = 0.8f)
            Activity.PEEKING -> RigPose(armL = 1.1f, lean = -0.07f, headTilt = -0.13f, ears = -0.05f, tail = 0.2f)
        }
    }
}

/**
 * Animation state of the rig: expression blend, blink, gaze, mouth, head with inertia, ears, tail and
 * the side ponytail on springs, gestures wave / nod / shake, walking in place.
 * Deterministic for a given sequence of calls (own LCG random, as in rig.js).
 */
class RigState {
    enum class Gesture { IDLE, WAVE, NOD, SHAKE }

    var face = EXPRESSIONS.getValue("neutral"); private set
    private var target = face
    var expression = "neutral"; private set
    var gesture = Gesture.IDLE; private set
    var gestureStart = 0f; private set
    var gestureUntil = 0f; private set
    var time = 0f; private set
    var gazeX = 0f; private set
    var gazeY = 0f; private set
    private var gazeTX = 0f
    private var gazeTY = 0f
    private var externalGaze = false
    var mouth = 0f; private set
    private var mouthTarget = 0f
    var blink = 0f; private set
    private var blinkAt = 2.8f
    private var blinkStart = -10f
    private var nextIdleLook = 7f
    private var seed = 71L
    var head = 0f; private set
    private var headVelocity = 0f
    val pony = Spring(40f, 5f)
    val earL = Spring(90f, 9f)
    val earR = Spring(90f, 9f)
    val tail = Spring(25f, 4f)
    /** 0..1, how much she walks in place. */
    var walk = 0f; private set
    var walkPhase = 0f; private set
    var energy = 1f
    /** The body pose, easing towards the one of the current activity. */
    var pose = RigPose.STAND; private set
    private var poseTarget = RigPose.STAND
    var activity = Activity.IDLE; private set
    /** Squash on landing after a jump or a fall, decays in about 0.3 s. */
    var landing = 0f; private set
    /** Legs swinging with inertia while she dangles (carried, hanging). */
    val legSwingL = Spring(30f, 3.2f)
    val legSwingR = Spring(30f, 3.6f)

    /** Tail wagging after something nice happened (1 → 0 in about 1.5 s). */
    var wag = 0f; private set
    /** Ears perked up: she heard her name and listens. */
    var listening = false

    /** Joy: a little hop (squash) and a burst of tail wagging. */
    fun joy() {
        landing = max(landing, 0.09f)
        wag = 1f
    }

    /** What she is doing now; [vy] = vertical speed in box sizes / s (negative = up). */
    fun setActivity(a: Activity, vy: Float = 0f) {
        if ((activity == Activity.JUMPING || activity == Activity.FALLING) && a != Activity.JUMPING && a != Activity.FALLING &&
            a != Activity.DRAGGED && a != Activity.HANGING && a != Activity.CLIMBING) landing = 0.16f
        activity = a
        poseTarget = RigPose.forActivity(a, vy)
    }

    private fun random(): Float {
        seed = (1664525L * seed + 1013904223L) and 0xFFFFFFFFL
        return seed / 4294967296f
    }

    fun setExpression(name: String, intensity: Float = 1f): Boolean {
        val e = EXPRESSIONS[name] ?: return false
        expression = name
        target = EXPRESSIONS.getValue("neutral").mix(e, intensity.coerceIn(0f, 1f))
        return true
    }

    fun setEmotion(e: Emotion) {
        val (name, k) = expressionFor(e)
        if (name != expression) setExpression(name, k)
    }

    /** Where to look, -1..1; null gives the gaze back to random idle glances. */
    fun lookAt(x: Float?, y: Float = 0f) {
        if (x == null) { externalGaze = false; return }
        externalGaze = true
        gazeTX = x.coerceIn(-1f, 1f); gazeTY = y.coerceIn(-1f, 1f)
    }

    fun setMouth(v: Float) { mouthTarget = v.coerceIn(0f, 1f) }

    fun gesture(g: Gesture, duration: Float) {
        gesture = g; gestureStart = time; gestureUntil = time + duration.coerceIn(0.2f, 15f)
    }

    /** Envelope 0..1 of the running gesture (fades in and out over 0.25 s). */
    val envelope: Float get() = if (gesture == Gesture.IDLE) 0f
        else min(1f, min((time - gestureStart) * 4f, (gestureUntil - time) * 4f)).coerceAtLeast(0f)

    /**
     * Advances by [dt]. [walking]: legs step in place. [bodyAccX] is the body's horizontal acceleration
     * in box sizes per s² (already in the character's own facing), it swings ears, tail and ponytail.
     */
    fun tick(dt: Float, walking: Boolean = false, bodyAccX: Float = 0f) {
        val d = dt.coerceIn(0f, 0.1f)
        var left = d
        while (left > 1e-6f) { // springs are stiff: sub-steps of ≤ 1/120 s keep them stable at 24 fps
            val h = min(left, 1f / 120f)
            step(h, walking, bodyAccX)
            left -= h
        }
    }

    private fun step(dt: Float, walking: Boolean, bodyAccX: Float) {
        time += dt
        val s = 1f - exp(-dt * 8f)
        face = face.mix(target, s)
        gazeX = mix(gazeX, gazeTX, s); gazeY = mix(gazeY, gazeTY, s)
        mouth = mix(mouth, mouthTarget, 1f - exp(-dt * 22f))
        if (gesture != Gesture.IDLE && time >= gestureUntil) gesture = Gesture.IDLE
        if (time >= blinkAt) { blinkStart = time; blinkAt = time + 2.6f + random() * 4.2f }
        val b = (time - blinkStart) / 0.19f
        blink = if (b in 0f..1f) sin(PI.toFloat() * b) else 0f
        if (!externalGaze && time >= nextIdleLook) {
            nextIdleLook = time + 6f + random() * 5f
            gazeTX = (random() - 0.5f) * 0.6f; gazeTY = (random() - 0.5f) * 0.4f
        }
        walk = mix(walk, if (walking) 1f else 0f, 1f - exp(-dt * 6f))
        walkPhase += dt * (1f + pose.run * 0.65f)
        pose = pose.mix(poseTarget, 1f - exp(-dt * 9f))
        landing *= exp(-dt * 9f)
        wag *= exp(-dt * 1.8f)
        val want = (gazeX * 0.035f + sin(time * 0.82f) * 0.009f) * energy
        headVelocity += (want - head) * 70f * dt - headVelocity * 13f * dt
        head += headVelocity * dt

        val acc = bodyAccX.coerceIn(-30f, 30f) * 0.6f
        pony.step(dt, sin(time * 1.65f) * 0.025f * energy + pose.hair, headVelocity * 9f + acc)
        val twitch = max(0f, sin(time * 0.61f)).pow(18) * sin(time * 17f) * 0.04f * energy
        val perk = if (listening) 0.07f else 0f
        earL.step(dt, -0.014f * sin(time * 2f) * energy + twitch - pose.ears + perk, headVelocity * 4f + acc * 0.5f)
        earR.step(dt, 0.014f * sin(time * 2f + 0.5f) * energy - twitch * 0.7f + pose.ears - perk, headVelocity * 4f + acc * 0.5f)
        tail.step(dt, (sin(time * 1.7f) * 0.065f + gazeX * 0.025f) * energy + pose.tail + sin(time * 9f) * 0.3f * wag, acc * 0.8f)
        // Dangling legs: a little pendulum of their own, pushed by the body's sideways motion.
        val d = pose.dangle
        legSwingL.step(dt, sin(time * 2.3f) * 0.05f * d, acc * 1.1f * d)
        legSwingR.step(dt, sin(time * 2.3f + 1.1f) * 0.05f * d, acc * 1.25f * d)
    }

    companion object {
        val EXPRESSIONS: Map<String, Face> = mapOf(
            "neutral" to Face(1f, 0f, .7f, .32f, .25f, 0f, 0f, 0f, 0f, 1f),
            "joy" to Face(.15f, .25f, 1f, .8f, .55f, 0f, 0f, 0f, 1f, 1f),
            "sad" to Face(.7f, -.9f, -.7f, 0f, .22f, 0f, -.3f, 0f, 0f, 0f),
            "surprise" to Face(1.25f, .75f, 0f, .85f, .2f, 0f, 0f, 0f, 0f, 0f),
            "angry" to Face(.65f, 1.15f, -.9f, 0f, .4f, 0f, 0f, 0f, 0f, 0f),
            "shy" to Face(.85f, -.45f, .45f, 0f, .95f, 0f, -.3f, -.55f, 0f, 0f),
            "thoughtful" to Face(.95f, 0f, -.1f, 0f, .22f, .7f, .6f, .6f, 0f, 0f),
            "sleepy" to Face(.26f, 0f, 0f, .35f, .2f, 0f, -.2f, 0f, 0f, 0f),
            "sly" to Face(.5f, .2f, .95f, .25f, .25f, .7f, 0f, .3f, 0f, 1f),
        )

        /** The companion's emotions → rig expression and intensity. */
        fun expressionFor(e: Emotion): Pair<String, Float> = when (e) {
            Emotion.NEUTRAL -> "neutral" to 1f
            Emotion.HAPPY -> "joy" to 0.8f
            Emotion.EXCITED -> "joy" to 1f
            Emotion.CURIOUS -> "sly" to 0.7f
            Emotion.SHY -> "shy" to 1f
            Emotion.POUTY -> "angry" to 0.8f
            Emotion.SAD -> "sad" to 1f
            Emotion.SURPRISED -> "surprise" to 1f
            Emotion.SLEEPY -> "sleepy" to 1f
            Emotion.THINKING -> "thoughtful" to 1f
        }
    }
}

/** Draws [RigState] with a [RigPainter]; port of rig.js render()/drawFace()/eye(). */
object RigDrawer {
    // Measured on the layers (tools: ear tips at the top of earL/earR, feet at the bottom of the legs).
    const val TOP = 28f
    const val FEET = 1265f
    const val ANCHOR_X = 612.5f
    const val HEAD = 645.6f   // ear-tip distance, comparable to Sprite.head

    private const val INK = 0xFF492039.toInt()

    /** Model px → box px, so that she is as tall as the standing sprite. */
    fun scale(box: Float) = box * SpriteLayout.FILL / (FEET - TOP)

    /** Draws the rig into a [box]-sized square (origin top-left), feet on [SpriteLayout.BASELINE]. */
    fun draw(c: RigPainter, m: RigModel, s: RigState, box: Float) {
        val k = scale(box)
        c.save()
        c.translate(box / 2f - ANCHOR_X * k, box * SpriteLayout.BASELINE - FEET * k)
        c.scale(k, k)
        val t = s.time; val e = s.energy
        val pose = s.pose
        val breath = sin(t * 2.1f) * 2.6f * e
        val phase = t - s.gestureStart
        val env = s.envelope
        val walk = sin(s.walkPhase * 8f) * s.walk
        val stride = 1f + pose.run * 0.9f
        // Whole body: squash/stretch on the feet (landing, jumping), lean around the hips.
        val squash = (pose.squash + s.landing).coerceIn(-0.2f, 0.25f)
        c.translate(ANCHOR_X, FEET); c.scale(1f + squash * 0.6f, 1f - squash); c.translate(-ANCHOR_X, -FEET)
        c.translate(615f, 975f); c.rotate(pose.lean + walk * pose.run * 0.03f); c.translate(-615f, -975f)
        c.translate(0f, breath - abs(walk) * 6f * stride)
        val flail = pose.flail
        val climb = pose.climb
        val kickL = sin(t * 11f) * 0.25f * flail + sin(t * 5f) * 0.18f * climb
        val kickR = -sin(t * 11f + 1.3f) * 0.25f * flail - sin(t * 5f) * 0.18f * climb
        part(c, m, "tail", s.tail.angle)
        part(c, m, "legL", walk * .15f * stride + pose.legL + kickL + s.legSwingL.angle, 0f, -max(0f, walk) * 12f * stride - pose.tuck)
        part(c, m, "legR", -walk * .15f * stride + pose.legR + kickR + s.legSwingR.angle, 0f, -max(0f, -walk) * 12f * stride - pose.tuck)
        val reach = sin(t * 5f) * 0.24f * climb
        val armSwing = 0.12f + pose.run * 0.3f
        part(c, m, "armR", sin(t * 2.1f) * .012f * e - walk * armSwing + pose.armR - sin(t * 13f + 0.9f) * 0.35f * flail - reach)
        part(c, m, "torso", 0f, 0f, 0f, 1f, 1f + breath * .0008f)
        val waving = if (s.gesture == RigState.Gesture.WAVE) env * (1.55f + sin(phase * 10f) * .17f) else 0f
        if (waving == 0f) part(c, m, "armL", walk * armSwing - sin(t * 2.1f) * .012f * e + pose.armL + sin(t * 13f) * 0.35f * flail - reach)
        var head = s.head + pose.headTilt
        var hy = s.gazeY * 5f
        if (s.gesture == RigState.Gesture.NOD) hy += sin(phase * 7f) * 9f * env
        if (s.gesture == RigState.Gesture.SHAKE) head += sin(phase * 8f) * .065f * env
        c.save()
        c.translate(615f + s.gazeX * 6f, 700f + hy); c.rotate(head); c.translate(-615f, -700f)
        part(c, m, "pony", s.pony.angle)
        part(c, m, "earL", s.earL.angle)
        part(c, m, "earR", s.earR.angle)
        part(c, m, "head")
        drawFace(c, s)
        c.restore()
        if (waving != 0f) part(c, m, "armL", waving)
        c.restore()
    }

    private fun part(c: RigPainter, m: RigModel, name: String, angle: Float = 0f, dx: Float = 0f, dy: Float = 0f, sx: Float = 1f, sy: Float = 1f) {
        val p = m.parts[name] ?: return
        val (x, y, r, b) = p.rect
        val px = p.pivot[0]; val py = p.pivot[1]
        c.save()
        c.translate(px + dx, py + dy); c.rotate(angle); c.scale(sx, sy)
        c.image(name, x - px, y - py, r - px, b - py)
        c.restore()
    }

    private fun drawFace(c: RigPainter, s: RigState) {
        val p = s.face
        for (x in listOf(447f, 786f)) {
            c.glow(x, 606f, 52f, 0xFFF68868.toInt(), .15f + p.blush * .45f)
            for (i in -1..1) {
                c.beginPath(); c.moveTo(x + i * 14 - 3, 603f); c.lineTo(x + i * 14 + 4, 591f); c.stroke(0xFFF69D76.toInt(), 7f)
            }
        }
        eye(c, s, 466f, 510f, false)
        eye(c, s, 754f, 494f, true)
        for (i in 0..1) {
            val x = if (i == 1) 744f else 453f
            val side = if (i == 1) 1f else -1f
            val angry = max(0f, p.brow); val sad = max(0f, -p.brow)
            val y = (if (i == 1) 399f else 416f) - p.asym * side * 20f - (if (s.expression == "surprise") p.open * 20f else 0f)
            c.beginPath(); c.moveTo(x - 40f, y + side * (angry - sad) * 22f)
            c.quadTo(x, y - 6f, x + 40f, y - side * (angry - sad) * 22f)
            c.stroke(INK, 8f)
        }
        c.beginPath(); c.ellipse(615f, 557f, 6f, 4.4f); c.fill(INK)
        val open = (p.open + s.mouth * .9f).coerceIn(0f, 1.2f)
        val smile = p.smile
        val r = mix(26f, 53f, abs(smile))
        val y = 607f
        val tilt = p.asym * 9f
        if (open < .07f) {
            c.beginPath(); c.moveTo(615f - r, y - tilt); c.quadTo(615f, y + smile * 29f, 615f + r, y + tilt); c.stroke(INK, 7f)
        } else {
            c.beginPath(); c.moveTo(615f - r, y - 12f + tilt)
            c.quadTo(615f, y - 12f + smile * 19f, 615f + r, y - 12f - tilt)
            c.cubicTo(615f + r * .7f, y + open * 74f, 615f - r * .7f, y + open * 74f, 615f - r, y - 12f + tilt)
            c.fill(0xFFF8AE92.toInt()); c.stroke(INK, 8f)
            c.beginPath(); c.moveTo(615f - r + 6f, y - 8f + tilt)
            c.quadTo(615f, y + smile * 8f, 615f + r - 6f, y - 8f - tilt)
            c.quadTo(615f, y + 18f, 615f - r + 6f, y - 8f + tilt)
            c.fill(INK)
            if (p.fang > .2f) {
                c.beginPath(); c.moveTo(615f + r - 23f, y - 4f - tilt * .5f); c.lineTo(615f + r - 8f, y - 8f - tilt); c.lineTo(615f + r - 13f, y + 9f); c.closePath()
                c.fill(0xFFFFF9EC.toInt())
            }
        }
    }

    private fun eye(c: RigPainter, s: RigState, cx: Float, cy: Float, right: Boolean) {
        val p = s.face
        val op = (p.eye * (1f - s.blink)).coerceIn(0f, 1.3f)
        val mirror = if (right) -1f else 1f
        c.save(); c.translate(cx, cy); c.scale(mirror, 1f)
        if (op < .1f || p.joy > .65f) {
            c.beginPath(); c.moveTo(-84f, 5f); c.quadTo(0f, if (p.joy > .65f) -65f else 18f, 84f, 4f); c.stroke(INK, 16f)
            c.beginPath(); c.moveTo(-70f, 3f); c.quadTo(-96f, 3f, -108f, -13f); c.stroke(INK, 16f)
            c.restore(); return
        }
        val top = -54f * op; val bottom = 61f * op
        val slant = if (s.expression == "angry") p.brow * 17f else 0f
        fun outline() {
            c.beginPath(); c.moveTo(-88f, top + 25f)
            c.cubicTo(-56f, top - 13f, 44f, top - 14f, 83f, top + 38f + slant)
            c.cubicTo(101f, 12f, 80f, bottom - 4f, 60f, bottom)
            c.cubicTo(-35f, bottom + 14f, -89f, bottom - 8f, -91f, 10f)
            c.closePath()
        }
        outline(); c.fill(0xFFFFFCF1.toInt())
        c.save(); c.clip()
        val ix = 22f + mirror * (s.gazeX * 16f + p.side * 18f)
        val iy = 7f - (s.gazeY * 13f + p.up * 18f)
        c.beginPath(); c.ellipse(ix, iy, 56f, 70f); c.fill(0xFF54233F.toInt())
        c.beginPath(); c.ellipse(ix, iy + 34f, 49f, 33f); c.fill(0xFFAD68AD.toInt())
        c.beginPath(); c.ellipse(ix + 1f, iy + 7f, 19f, 27f); c.fill(0xFF61284C.toInt())
        c.beginPath(); c.ellipse(ix + mirror * 18f, iy - 32f, 17f, 18f); c.fill(0xFFFFFDF3.toInt())
        c.restore()
        outline(); c.stroke(INK, 5f)
        c.beginPath(); c.moveTo(-91f, top + 32f)
        c.cubicTo(-100f, top + 27f, -110f, top + 14f, -112f, top + 7f)
        c.quadTo(-90f, top + 17f, -79f, top + 8f)
        c.quadTo(-92f, top - 6f, -90f, top - 17f)
        c.quadTo(-67f, top + 3f, -50f, top - 2f)
        c.cubicTo(10f, top - 25f, 64f, top - 4f, 87f, top + 34f + slant)
        c.cubicTo(23f, top + 9f, -39f, top + 10f, -91f, top + 32f)
        c.fill(INK)
        c.restore()
    }
}
