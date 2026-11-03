package ru.iskra.core.avatar

import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/** Where to draw a sprite inside the character box, in box pixels (before mirroring and breathing). */
data class SpriteRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Scale and placement of the sprites in a square character box.
 *
 * - Feet on one baseline ([BASELINE] of the box), head centre on the box centre line.
 * - The upright sprites share one scale: the artist drew them at the same size.
 * - [Sprite.SIT] and [Sprite.SLEEP] get their own scale so that the head is as big as in [Sprite.STAND].
 */
object SpriteLayout {
    /** Height of the standing figure as a share of the box (room above the ears for "z", "…"). */
    const val FILL = 0.9f
    /** Feet line as a share of the box height (room for the shadow). */
    const val BASELINE = 0.98f

    private val ownScale = setOf(Sprite.SIT, Sprite.SLEEP)

    /** Source pixels → box pixels for [s]. */
    fun scale(s: Sprite, box: Float): Float {
        val base = box * FILL / (Sprite.STAND.feetY - Sprite.STAND.top)
        return if (s in ownScale) base * Sprite.STAND.head / s.head else base
    }

    fun rect(s: Sprite, box: Float): SpriteRect {
        val k = scale(s, box)
        val left = box / 2f - s.anchorX * k
        val top = box * BASELINE - s.feetY * k
        return SpriteRect(left, top, left + Sprite.SIZE * k, top + Sprite.SIZE * k)
    }

    /** Head size in box pixels — the same for every sprite by design. */
    fun headSize(s: Sprite, box: Float) = s.head * scale(s, box)
}

/** One picture of the frame. During a crossfade there are two: the old one fading out first. */
data class SpriteLayer(val sprite: Sprite, val alpha: Float, val mirror: Boolean)

/**
 * What to draw this frame.
 * [scaleY] is the breathing squash around the feet (1 = none).
 * [fallback] is set when the state has no own sprite: a short description for the journal.
 */
data class SpriteFrame(val layers: List<SpriteLayer>, val scaleY: Float, val fallback: String?) {
    val sprite get() = layers.last().sprite
}

/**
 * Picks the sprite for an [AvatarPose] and animates between sprites:
 * walking alternates two frames (~6 fps), standing/sitting/sleeping "breathe" (1–2 % vertical squash),
 * other changes crossfade for [FADE_SEC].
 *
 * Pure logic, unit-tested; the app only draws the returned [SpriteFrame].
 * The sprites face the viewer; they are mirrored when she faces right, so turning around is visible.
 */
class SpriteAnimator {
    private var t = 0f
    private var walkClock = 0f
    private var current: SpriteLayer? = null
    private var previous: SpriteLayer? = null
    private var fade = 1f   // 0 → 1 while crossfading from previous to current

    /** True while a crossfade runs: the view should draw at full rate to keep it smooth. */
    val fading get() = previous != null

    fun update(dt: Float, p: AvatarPose): SpriteFrame {
        val d = dt.coerceIn(0f, 0.1f)
        t += d
        val pick = choose(p)
        val walking = p.activity == Activity.WALKING || p.activity == Activity.RUNNING
        val sprite = if (walking) {
            walkClock += d
            val fps = if (p.activity == Activity.RUNNING) RUN_FPS else WALK_FPS
            if (floor(walkClock * fps).toInt() % 2 == 0) Sprite.WALK01 else Sprite.WALK02
        } else {
            walkClock = 0f
            pick.first
        }
        val mirror = p.facingRight
        val cur = current
        when {
            cur == null -> current = SpriteLayer(sprite, 1f, mirror)
            cur.sprite == sprite -> current = cur.copy(mirror = mirror)
            // Walk frames are a flip-book: a hard cut, no fade.
            isWalk(cur.sprite) && isWalk(sprite) -> current = SpriteLayer(sprite, 1f, mirror)
            else -> {
                previous = cur.copy(alpha = 1f)
                current = SpriteLayer(sprite, 1f, mirror)
                fade = 0f
            }
        }
        if (previous != null) {
            fade += d / FADE_SEC
            if (fade >= 1f) { fade = 1f; previous = null }
        }

        val layers = previous?.let { prev ->
            // Both stay opaque in the middle of the fade, so she never looks see-through.
            listOf(prev.copy(alpha = (2f * (1f - fade)).coerceIn(0f, 1f)), current!!.copy(alpha = (2f * fade).coerceIn(0f, 1f)))
        } ?: listOf(current!!)
        return SpriteFrame(layers, breathing(p), pick.second)
    }

    /** Vertical squash: a slow breath while still, none while moving. */
    private fun breathing(p: AvatarPose): Float {
        val (amp, period) = when (p.activity) {
            Activity.SLEEPING -> SLEEP_BREATH to 4.5f
            Activity.IDLE, Activity.SITTING -> IDLE_BREATH to 3.2f
            else -> return 1f
        }
        val k = 0.5f - 0.5f * cos(2f * PI.toFloat() * t / period)
        return 1f - amp * k
    }

    companion object {
        const val WALK_FPS = 6f
        const val RUN_FPS = 10f
        const val FADE_SEC = 0.18f
        const val IDLE_BREATH = 0.015f
        const val SLEEP_BREATH = 0.02f

        private fun isWalk(s: Sprite) = s == Sprite.WALK01 || s == Sprite.WALK02

        private fun emotionSprite(e: Emotion): Sprite = when (e) {
            Emotion.NEUTRAL -> Sprite.STAND
            Emotion.HAPPY, Emotion.EXCITED -> Sprite.JOY
            Emotion.CURIOUS -> Sprite.SLY
            Emotion.SHY -> Sprite.SHY
            Emotion.POUTY -> Sprite.ANGRY
            Emotion.SAD -> Sprite.SAD
            Emotion.SURPRISED -> Sprite.SURPRISE
            Emotion.SLEEPY -> Sprite.SLEEPY
            Emotion.THINKING -> Sprite.THOUGHTFUL
        }

        /**
         * Sprite for the pose and, when there is no sprite of its own, a journal note
         * "state → nearest sprite". Walking returns [Sprite.WALK01]; the animator alternates the frames.
         */
        fun choose(p: AvatarPose): Pair<Sprite, String?> {
            val emo = p.emotion != Emotion.NEUTRAL
            fun near(s: Sprite, what: String) = s to "$what → ${s.file}"
            return when (p.activity) {
                Activity.SLEEPING -> Sprite.SLEEP to null
                Activity.SITTING -> if (emo) near(Sprite.SIT, "сидит + ${p.emotion}") else Sprite.SIT to null
                Activity.WALKING -> if (emo) near(Sprite.WALK01, "идёт + ${p.emotion}") else Sprite.WALK01 to null
                Activity.RUNNING -> near(Sprite.WALK01, "бежит (ходьба быстрее)")
                Activity.JUMPING -> near(Sprite.JOY, "прыжок")
                Activity.FALLING -> near(Sprite.SURPRISE, "падает")
                Activity.DRAGGED -> near(Sprite.SURPRISE, "её несут пальцем")
                Activity.CLIMBING -> near(Sprite.WAVE, "держится за край")
                Activity.HANGING -> near(Sprite.WAVE, "висит на верхнем крае")
                Activity.PEEKING -> near(emotionSprite(p.emotion), "выглядывает")
                Activity.HIDDEN -> Sprite.STAND to null
                Activity.IDLE -> when {
                    p.waving -> Sprite.WAVE to null
                    p.thinking -> Sprite.PHONE to null   // waiting for the answer: looks it up on her phone
                    else -> emotionSprite(p.emotion) to null
                }
            }
        }
    }
}
