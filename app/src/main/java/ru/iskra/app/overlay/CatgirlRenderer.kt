package ru.iskra.app.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.persona.Emotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural chibi cat-girl drawn with Canvas primitives in a 100x100 unit box.
 * No bitmap assets → no licensing issues, tiny APK, any resolution. Will be replaced by a
 * rigged Rive/Spine model in a later version behind the same [AvatarPose] interface.
 */
class CatgirlRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val oval = RectF()

    private val hair = Color.parseColor("#7A4FB0")
    private val hairDark = Color.parseColor("#5B3788")
    private val skin = Color.parseColor("#FFE6D8")
    private val earInner = Color.parseColor("#F4A7C3")
    private val eye = Color.parseColor("#3A2350")
    private val iris = Color.parseColor("#B07CFF")
    private val dress = Color.parseColor("#2E2246")
    private val accent = Color.parseColor("#FF8FB8")
    private val line = Color.parseColor("#2B1A3D")

    fun draw(c: Canvas, w: Float, h: Float, p: AvatarPose) {
        val s = minOf(w, h) / 100f
        c.save()
        c.translate(w / 2f, h)
        c.scale(s, s)
        if (p.facingRight) c.scale(-1f, 1f)
        c.translate(0f, -2f + p.bob * 0.6f)

        drawShadow(c)
        drawTail(c, p)
        drawBody(c, p)
        c.save()
        c.rotate(p.tilt, 0f, -42f)
        drawHead(c, p)
        c.restore()
        c.restore()

        // Effects drawn unmirrored, in view space.
        c.save()
        c.translate(w / 2f, h)
        c.scale(s, s)
        drawEffects(c, p)
        c.restore()
    }

    private fun drawShadow(c: Canvas) {
        fill.color = Color.argb(60, 0, 0, 0)
        oval.set(-22f, -4f, 22f, 2f); c.drawOval(oval, fill)
    }

    private fun drawTail(c: Canvas, p: AvatarPose) {
        val sway = sin(p.tailPhase) * 8f
        path.reset()
        path.moveTo(10f, -14f)
        path.cubicTo(28f, -14f, 30f + sway * 0.5f, -34f, 22f + sway, -48f)
        stroke.color = hair; stroke.strokeWidth = 6f
        c.drawPath(path, stroke)
        fill.color = hairDark
        c.drawCircle(22f + sway, -48f, 3.6f, fill)
    }

    private fun drawBody(c: Canvas, p: AvatarPose) {
        // Legs
        val leg = sin(p.legPhase) * 3f
        stroke.color = skin; stroke.strokeWidth = 5f
        c.drawLine(-5f, -14f, -6f + leg, -2f, stroke)
        c.drawLine(5f, -14f, 6f - leg, -2f, stroke)
        fill.color = line
        oval.set(-10f + leg, -4f, -2f + leg, 0f); c.drawOval(oval, fill)
        oval.set(2f - leg, -4f, 10f - leg, 0f); c.drawOval(oval, fill)
        // Dress (trapezoid)
        path.reset()
        path.moveTo(-8f, -32f); path.lineTo(8f, -32f); path.lineTo(15f, -12f); path.lineTo(-15f, -12f); path.close()
        fill.color = dress; c.drawPath(path, fill)
        fill.color = accent
        c.drawCircle(0f, -30f, 2.2f, fill) // bow
        // Arms
        stroke.color = skin; stroke.strokeWidth = 4.5f
        when {
            p.activity == Activity.DRAGGED -> {
                c.drawLine(-7f, -30f, -15f, -44f, stroke); c.drawLine(7f, -30f, 15f, -44f, stroke)
            }
            p.activity == Activity.THINKING -> {
                c.drawLine(-7f, -30f, -13f, -20f, stroke); c.drawLine(7f, -30f, 5f, -38f, stroke)
            }
            p.emotion == Emotion.EXCITED || p.emotion == Emotion.HAPPY -> {
                val wave = sin(p.tailPhase * 1.5f) * 4f
                c.drawLine(-7f, -30f, -14f, -22f, stroke); c.drawLine(7f, -30f, 15f, -40f + wave, stroke)
            }
            else -> { c.drawLine(-7f, -30f, -12f, -18f, stroke); c.drawLine(7f, -30f, 12f, -18f, stroke) }
        }
    }

    private fun drawHead(c: Canvas, p: AvatarPose) {
        val cy = -56f
        val perk = if (p.activity == Activity.LISTENING) -4f else 0f
        val droop = if (p.emotion == Emotion.SAD || p.activity == Activity.SLEEPING) 5f else 0f
        val twitch = p.earTwitch * 6f
        // Ears
        drawEar(c, -1f, cy, perk + droop, twitch)
        drawEar(c, 1f, cy, perk + droop, 0f)
        // Back hair
        fill.color = hairDark
        oval.set(-25f, cy - 22f, 25f, cy + 20f); c.drawOval(oval, fill)
        // Face
        fill.color = skin
        oval.set(-21f, cy - 18f, 21f, cy + 18f); c.drawOval(oval, fill)
        // Bangs
        path.reset()
        path.moveTo(-23f, cy - 2f)
        path.cubicTo(-22f, cy - 24f, 22f, cy - 24f, 23f, cy - 2f)
        path.lineTo(16f, cy - 8f); path.lineTo(10f, cy - 3f); path.lineTo(4f, cy - 10f)
        path.lineTo(-3f, cy - 4f); path.lineTo(-9f, cy - 10f); path.lineTo(-15f, cy - 4f); path.close()
        fill.color = hair; c.drawPath(path, fill)

        drawEyes(c, p, cy)
        drawMouth(c, p, cy)
        // Blush
        val blushA = when (p.emotion) { Emotion.SHY -> 200; Emotion.HAPPY, Emotion.EXCITED -> 130; else -> 70 }
        fill.color = Color.argb(blushA, 255, 140, 170)
        oval.set(-17f, cy + 5f, -10f, cy + 9f); c.drawOval(oval, fill)
        oval.set(10f, cy + 5f, 17f, cy + 9f); c.drawOval(oval, fill)
        if (p.emotion == Emotion.POUTY) { // puffed cheek
            fill.color = skin; c.drawCircle(15f, cy + 7f, 4f, fill)
            stroke.color = line; stroke.strokeWidth = 1f; c.drawArc(oval.apply { set(11f, cy + 3f, 19f, cy + 11f) }, -60f, 120f, false, stroke)
        }
    }

    private fun drawEar(c: Canvas, side: Float, cy: Float, raise: Float, twitch: Float) {
        path.reset()
        path.moveTo(side * 8f, cy - 15f)
        path.lineTo(side * (20f + twitch * 0.3f), cy - 31f + raise - twitch)
        path.lineTo(side * 22f, cy - 8f)
        path.close()
        fill.color = hair; c.drawPath(path, fill)
        path.reset()
        path.moveTo(side * 11f, cy - 15f)
        path.lineTo(side * (19f + twitch * 0.3f), cy - 26f + raise - twitch)
        path.lineTo(side * 19.5f, cy - 11f)
        path.close()
        fill.color = earInner; c.drawPath(path, fill)
    }

    private fun drawEyes(c: Canvas, p: AvatarPose, cy: Float) {
        val ey = cy + 1f
        stroke.color = line; stroke.strokeWidth = 1.8f
        // Brows
        val browTilt = when (p.emotion) { Emotion.POUTY -> 3f; Emotion.SAD -> -3f; else -> 0f }
        c.drawLine(-13f, ey - 9f - browTilt, -6f, ey - 9f + browTilt, stroke)
        c.drawLine(6f, ey - 9f + browTilt, 13f, ey - 9f - browTilt, stroke)

        val happyArcs = p.emotion == Emotion.HAPPY || p.emotion == Emotion.EXCITED
        if (p.eyeOpen < 0.2f || happyArcs && p.activity != Activity.LISTENING) {
            stroke.strokeWidth = 2f
            for (x in floatArrayOf(-9.5f, 9.5f)) {
                if (happyArcs && p.eyeOpen >= 0.2f) {
                    oval.set(x - 4.5f, ey - 2f, x + 4.5f, ey + 5f); c.drawArc(oval, 200f, 140f, false, stroke)
                } else {
                    oval.set(x - 4.5f, ey - 3f, x + 4.5f, ey + 3f); c.drawArc(oval, 20f, 140f, false, stroke)
                }
            }
            return
        }
        val big = if (p.emotion == Emotion.SURPRISED) 1.25f else 1f
        val open = p.eyeOpen
        val lookX = if (p.emotion == Emotion.SHY) 1.5f else 0f
        for (x in floatArrayOf(-9.5f, 9.5f)) {
            val rx = 4.6f * big; val ry = 6f * big * open
            fill.color = eye; oval.set(x - rx, ey - ry, x + rx, ey + ry); c.drawOval(oval, fill)
            fill.color = iris; oval.set(x - rx * 0.7f + lookX, ey - ry * 0.2f, x + rx * 0.7f + lookX, ey + ry * 0.85f); c.drawOval(oval, fill)
            fill.color = Color.WHITE
            c.drawCircle(x - 1.6f + lookX, ey - ry * 0.45f, 1.5f * big, fill)
            c.drawCircle(x + 1.6f + lookX, ey + ry * 0.35f, 0.7f, fill)
        }
    }

    private fun drawMouth(c: Canvas, p: AvatarPose, cy: Float) {
        val my = cy + 10f
        stroke.color = line; stroke.strokeWidth = 1.5f
        if (p.mouthOpen > 0.1f) {
            fill.color = Color.parseColor("#C2416B")
            val h = 1.5f + p.mouthOpen * 3.5f
            oval.set(-2.6f, my - h / 2f, 2.6f, my + h / 2f); c.drawOval(oval, fill)
            return
        }
        when (p.emotion) {
            Emotion.SAD -> { oval.set(-3f, my, 3f, my + 4f); c.drawArc(oval, 200f, 140f, false, stroke) }
            Emotion.POUTY -> c.drawLine(-2.5f, my + 1f, 2.5f, my + 0.5f, stroke)
            else -> { // cat mouth "ω"
                oval.set(-4f, my - 2f, 0f, my + 2f); c.drawArc(oval, 0f, 180f, false, stroke)
                oval.set(0f, my - 2f, 4f, my + 2f); c.drawArc(oval, 0f, 180f, false, stroke)
            }
        }
    }

    private fun drawEffects(c: Canvas, p: AvatarPose) {
        text.textSize = 9f
        when {
            p.activity == Activity.SLEEPING -> {
                val k = (p.tailPhase % 3f) / 3f
                text.color = Color.argb((255 * (1 - k)).toInt(), 200, 190, 255)
                c.drawText("z", 18f + k * 6f, -80f - k * 10f, text)
                text.textSize = 6f; c.drawText("z", 13f, -74f, text)
            }
            p.activity == Activity.THINKING -> {
                text.color = Color.WHITE
                val dots = ".".repeat(1 + ((p.tailPhase * 0.8f).toInt() % 3))
                c.drawText(dots, 0f, -84f, text)
            }
            p.activity == Activity.LISTENING -> {
                stroke.color = accent; stroke.strokeWidth = 1.5f
                for (i in 1..2) {
                    val r = 6f + i * 4f + (sin(p.tailPhase * 3f) + 1f) * 1.5f
                    oval.set(24f - r, -60f - r, 24f + r, -60f + r); c.drawArc(oval, -40f, 80f, false, stroke)
                }
            }
            p.emotion == Emotion.CURIOUS -> { text.color = Color.WHITE; c.drawText("?", 26f, -78f, text) }
            p.emotion == Emotion.SURPRISED -> { text.color = Color.WHITE; c.drawText("!", 26f, -78f, text) }
            p.emotion == Emotion.EXCITED || p.emotion == Emotion.SHY -> {
                fill.color = accent
                for (i in 0 until 3) {
                    val a = p.tailPhase * 0.7f + i * 2.1f
                    c.drawCircle(cos(a) * 30f, -60f + sin(a) * 22f, 1.6f, fill)
                }
            }
        }
    }
}
