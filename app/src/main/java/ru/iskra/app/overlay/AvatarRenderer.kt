package ru.iskra.app.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
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
 * Small effects over the character: "z" asleep, "…" thinking, sound arcs listening, "?"/"!" and sparks of her name,
 * a burst of sparks when she appears and hearts when she is petted (0.16.0).
 * Drawn in a 100-unit box with the origin at the bottom centre (y up is negative), unmirrored. Marks and letters have a
 * dark edge, so they show over light apps as well as dark ones. [sprites]: the drawn character is taller, so the marks move
 * to the upper right corner, clear of the face.
 */
class AvatarEffects(typeface: Typeface? = null) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        if (typeface != null) this.typeface = typeface else isFakeBoldText = true
    }
    private val edge = Paint(text).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = INK }
    private val oval = RectF()
    private val path = Path()
    private val accent = Color.parseColor("#FF8FB8")
    private val gold = Color.parseColor("#FFC46B")
    private val lilac = Color.parseColor("#B98CFF")

    fun draw(c: Canvas, p: AvatarPose, sprites: Boolean) {
        val mx = if (sprites) 36f else 26f   // "?" / "!" position
        val my = if (sprites) -86f else -78f
        text.textSize = 9f
        when {
            p.activity == Activity.SLEEPING -> {
                val k = (p.tailPhase % 3f) / 3f
                val zy = if (sprites) -62f else -80f // the sleeping sprite lies low
                letter(c, "z", 18f + k * 6f, zy - k * 10f, Color.argb((255 * (1 - k)).toInt(), 214, 204, 255), 9f)
                letter(c, "z", 13f, zy + 6f, Color.rgb(214, 204, 255), 6f)
            }
            p.thinking -> letter(c, ".".repeat(1 + ((p.tailPhase * 0.8f).toInt() % 3)), if (sprites) mx else 0f, if (sprites) my else -84f, Color.WHITE, 9f)
            p.listening -> {
                val cx = if (sprites) 34f else 24f
                for (i in 1..2) {
                    val r = 6f + i * 4f + (sin(p.tailPhase * 3f) + 1f) * 1.5f
                    oval.set(cx - r, -60f - r, cx + r, -60f + r)
                    stroke.color = INK; stroke.strokeWidth = 2.6f; c.drawArc(oval, -40f, 80f, false, stroke)
                    stroke.color = accent; stroke.strokeWidth = 1.5f; c.drawArc(oval, -40f, 80f, false, stroke)
                }
            }
            p.emotion == Emotion.CURIOUS -> letter(c, "?", mx, my, Color.WHITE, 9f)
            p.emotion == Emotion.SURPRISED -> letter(c, "!", mx, my, Color.WHITE, 9f)
            p.emotion == Emotion.EXCITED || p.emotion == Emotion.SHY -> {
                for (i in 0 until 3) {
                    val a = p.tailPhase * 0.7f + i * 2.1f
                    val tw = 0.6f + 0.4f * sin(p.tailPhase * 2.3f + i * 1.7f)
                    spark(c, cos(a) * 34f, -55f + sin(a) * 24f, 2.6f * tw, if (i == 1) gold else accent, 255)
                }
            }
        }
        if (p.appear < 1f) appearing(c, p.appear)
        if (p.hearts in 0f..1f) hearts(c, p.hearts, if (sprites) -78f else -70f)
    }

    /** A letter or a mark in white (or [color]) with a dark edge, readable over any app. */
    private fun letter(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float) {
        text.textSize = size; edge.textSize = size
        edge.strokeWidth = size * 0.24f
        edge.alpha = Color.alpha(color)
        c.drawText(s, x, y, edge)
        text.color = color
        c.drawText(s, x, y, text)
    }

    /** The four-pointed star of her name, with a thin dark edge. */
    private fun spark(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.cubicTo(cx + r * 0.09f, cy - r * 0.42f, cx + r * 0.42f, cy - r * 0.09f, cx + r, cy)
        path.cubicTo(cx + r * 0.42f, cy + r * 0.09f, cx + r * 0.09f, cy + r * 0.42f, cx, cy + r)
        path.cubicTo(cx - r * 0.09f, cy + r * 0.42f, cx - r * 0.42f, cy + r * 0.09f, cx - r, cy)
        path.cubicTo(cx - r * 0.42f, cy - r * 0.09f, cx - r * 0.09f, cy - r * 0.42f, cx, cy - r)
        path.close()
        stroke.color = INK; stroke.alpha = alpha / 2; stroke.strokeWidth = r * 0.28f
        c.drawPath(path, stroke)
        fill.color = color; fill.alpha = alpha
        c.drawPath(path, fill)
    }

    /** Sparks flying out of her as she appears, slowing and fading. */
    private fun appearing(c: Canvas, k: Float) {
        val far = 1f - (1f - k).pow(3)
        val a = ((1f - k) * 255).toInt().coerceIn(0, 255)
        for (i in 0 until 10) {
            val angle = i * 2f * PI.toFloat() / 10f + 0.3f
            val d = 16f + 30f * far * (0.8f + 0.2f * ((i * 7) % 3))
            spark(c, cos(angle) * d, -48f + sin(angle) * d * 0.9f, 3.2f * (1f - 0.5f * k), when (i % 3) { 0 -> gold; 1 -> accent; else -> lilac }, a)
        }
    }

    /** Three hearts rising from her head and swaying, one after another. */
    private fun hearts(c: Canvas, k: Float, top: Float) {
        for (i in 0 until 3) {
            val q = ((k - i * 0.18f) / 0.64f)
            if (q !in 0f..1f) continue
            val x = (i - 1) * 9f + sin(q * 7f + i) * 3f
            val y = top - q * 22f
            val s = 3.2f * (0.6f + 0.4f * sin(q * PI.toFloat()))
            val alpha = (sin(q * PI.toFloat()) * 255).toInt().coerceIn(0, 255)
            path.reset()
            path.moveTo(x, y + s * 0.9f)
            path.cubicTo(x - s * 1.5f, y - s * 0.1f, x - s * 0.6f, y - s * 1.2f, x, y - s * 0.35f)
            path.cubicTo(x + s * 0.6f, y - s * 1.2f, x + s * 1.5f, y - s * 0.1f, x, y + s * 0.9f)
            path.close()
            stroke.color = INK; stroke.alpha = alpha / 2; stroke.strokeWidth = 0.9f
            c.drawPath(path, stroke)
            fill.color = accent; fill.alpha = alpha
            c.drawPath(path, fill)
        }
    }

    private companion object {
        val INK = Color.parseColor("#2B1A3D")
    }
}
