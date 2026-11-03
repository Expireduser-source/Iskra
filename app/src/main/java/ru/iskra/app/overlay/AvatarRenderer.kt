package ru.iskra.app.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.persona.Emotion
import kotlin.math.cos
import kotlin.math.sin

/** Draws the character for an [AvatarPose]. Two looks: [SpriteRenderer] (new) and [CatgirlRenderer] (old). */
interface AvatarRenderer {
    /** Called once per simulation step, before [draw]. */
    fun advance(dt: Float, pose: AvatarPose) = Unit

    /** Draws into a [box] x [box] square whose top-left is the canvas origin. */
    fun draw(c: Canvas, box: Float, p: AvatarPose)

    /** Whether this frame needs the full "moving" frame rate; otherwise the idle rate (30 fps) is enough. */
    fun wantsFullRate(p: AvatarPose): Boolean

    fun release() = Unit
}

/**
 * Small effects over the character: "z" asleep, "…" thinking, sound arcs listening, "?"/"!" and sparkles.
 * Drawn in a 100-unit box with the origin at the bottom centre (y up is negative), unmirrored.
 * [sprites]: the drawn character is taller, so the marks move to the upper right corner, clear of the face.
 */
class AvatarEffects {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val oval = RectF()
    private val accent = Color.parseColor("#FF8FB8")

    fun draw(c: Canvas, p: AvatarPose, sprites: Boolean) {
        val mx = if (sprites) 36f else 26f   // "?" / "!" position
        val my = if (sprites) -86f else -78f
        text.textSize = 9f
        when {
            p.activity == Activity.SLEEPING -> {
                val k = (p.tailPhase % 3f) / 3f
                val zy = if (sprites) -62f else -80f // the sleeping sprite lies low
                text.color = Color.argb((255 * (1 - k)).toInt(), 200, 190, 255)
                c.drawText("z", 18f + k * 6f, zy - k * 10f, text)
                text.textSize = 6f; c.drawText("z", 13f, zy + 6f, text)
            }
            p.thinking -> {
                text.color = Color.WHITE
                val dots = ".".repeat(1 + ((p.tailPhase * 0.8f).toInt() % 3))
                c.drawText(dots, if (sprites) mx else 0f, if (sprites) my else -84f, text)
            }
            p.listening -> {
                stroke.color = accent; stroke.strokeWidth = 1.5f
                val cx = if (sprites) 34f else 24f
                for (i in 1..2) {
                    val r = 6f + i * 4f + (sin(p.tailPhase * 3f) + 1f) * 1.5f
                    oval.set(cx - r, -60f - r, cx + r, -60f + r); c.drawArc(oval, -40f, 80f, false, stroke)
                }
            }
            p.emotion == Emotion.CURIOUS -> { text.color = Color.WHITE; c.drawText("?", mx, my, text) }
            p.emotion == Emotion.SURPRISED -> { text.color = Color.WHITE; c.drawText("!", mx, my, text) }
            p.emotion == Emotion.EXCITED || p.emotion == Emotion.SHY -> {
                fill.color = accent
                for (i in 0 until 3) {
                    val a = p.tailPhase * 0.7f + i * 2.1f
                    c.drawCircle(cos(a) * 34f, -55f + sin(a) * 24f, 1.6f, fill)
                }
            }
        }
    }
}
