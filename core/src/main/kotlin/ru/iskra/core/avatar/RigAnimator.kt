package ru.iskra.core.avatar

import ru.iskra.core.persona.Emotion

/** What one layer of the frame shows: the live rig or one of the sprites. */
sealed interface Visual {
    data object Rig : Visual
    data class Picture(val sprite: Sprite) : Visual
}

data class LookLayer(val visual: Visual, val alpha: Float, val mirror: Boolean)

/** [scaleY]: breathing of sprite layers (the rig breathes by itself). [fallback]: journal note, see [SpriteFrame]. */
data class LookFrame(val layers: List<LookLayer>, val scaleY: Float, val fallback: String?) {
    val visual get() = layers.last().visual
}

/**
 * The "rig" look: the live rig for standing, walking, talking and everything in the air; the sprites
 * of 4.1 for sitting, sleeping and the phone (while she waits for an answer). Switching between them
 * crossfades. Drives [rig] from the pose: emotion, mouth, gaze, wave / nod / shake, walking, and the
 * body's acceleration for ears, tail and ponytail.
 */
class RigAnimator(val rig: RigState = RigState()) {
    private var t = 0f
    private var current: LookLayer? = null
    private var previous: LookLayer? = null
    private var fade = 1f

    private var lastX = Float.NaN
    private var lastVx = 0f
    private var accX = 0f
    private var wasWaving = false
    private var wasListening = false
    private var lastEmotion = Emotion.NEUTRAL

    val fading get() = previous != null

    /** [box]: character size in px, to turn screen motion into "box sizes per second". */
    fun update(dt: Float, p: AvatarPose, box: Float): LookFrame {
        val d = dt.coerceIn(0f, 0.1f)
        t += d
        drive(d, p, box)
        val (visual, note) = choose(p)

        val mirror = p.facingRight
        val cur = current
        when {
            cur == null -> current = LookLayer(visual, 1f, mirror)
            cur.visual == visual -> current = cur.copy(mirror = mirror)
            else -> { previous = cur; current = LookLayer(visual, 1f, mirror); fade = 0f }
        }
        if (previous != null) {
            fade += d / SpriteAnimator.FADE_SEC
            if (fade >= 1f) { fade = 1f; previous = null }
        }
        val layers = previous?.let { prev ->
            listOf(prev.copy(alpha = (2f * (1f - fade)).coerceIn(0f, 1f)), current!!.copy(alpha = (2f * fade).coerceIn(0f, 1f)))
        } ?: listOf(current!!)
        val breathe = when (p.activity) {
            Activity.SLEEPING -> 1f - SpriteAnimator.SLEEP_BREATH * wave(4.5f)
            Activity.SITTING, Activity.IDLE -> 1f - SpriteAnimator.IDLE_BREATH * wave(3.2f)
            else -> 1f
        }
        return LookFrame(layers, breathe, note)
    }

    private fun wave(period: Float) = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * t / period)

    private fun drive(d: Float, p: AvatarPose, box: Float) {
        rig.setEmotion(p.emotion)
        rig.setMouth(p.mouthOpen)
        rig.lookAt(if (p.lookX != 0f) p.lookX * (if (p.facingRight) -1f else 1f) else null)
        if (p.waving && !wasWaving) rig.gesture(RigState.Gesture.WAVE, 1.6f)
        if (p.listening && !wasListening) rig.gesture(RigState.Gesture.NOD, 0.9f)
        if (p.emotion == Emotion.POUTY && lastEmotion != Emotion.POUTY) rig.gesture(RigState.Gesture.SHAKE, 1.2f)
        wasWaving = p.waving; wasListening = p.listening; lastEmotion = p.emotion

        // Body acceleration in box sizes / s², in her own facing (the picture is mirrored when she faces right).
        if (d > 0f && box > 0f) {
            if (!lastX.isNaN()) {
                val vx = (p.x - lastX) / box / d
                val raw = (vx - lastVx) / d
                accX = accX + (raw - accX) * 0.5f
                lastVx = vx
            }
            lastX = p.x
        }
        val walking = p.activity == Activity.WALKING || p.activity == Activity.RUNNING
        rig.tick(d, walking, if (p.facingRight) -accX else accX)
    }

    companion object {
        /** Rig unless the pose has its own sprite; the note names states the rig only approximates. */
        fun choose(p: AvatarPose): Pair<Visual, String?> {
            val emo = p.emotion != Emotion.NEUTRAL
            fun pic(s: Sprite, what: String?) = Visual.Picture(s) to what
            fun rig(what: String) = Visual.Rig to "$what → риг без своей позы"
            return when (p.activity) {
                Activity.SLEEPING -> pic(Sprite.SLEEP, null)
                Activity.SITTING -> pic(Sprite.SIT, if (emo) "сидит + ${p.emotion} → sit" else null)
                Activity.IDLE -> if (p.thinking) pic(Sprite.PHONE, null) else Visual.Rig to null
                Activity.WALKING, Activity.HIDDEN -> Visual.Rig to null
                Activity.RUNNING -> rig("бежит")
                Activity.JUMPING -> rig("прыжок")
                Activity.FALLING -> rig("падает")
                Activity.DRAGGED -> rig("её несут пальцем")
                Activity.CLIMBING -> rig("держится за край")
                Activity.HANGING -> rig("висит на верхнем крае")
                Activity.PEEKING -> rig("выглядывает")
            }
        }
    }
}
