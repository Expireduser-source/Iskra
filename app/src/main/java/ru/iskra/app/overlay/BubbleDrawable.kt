package ru.iskra.app.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Her speech bubble over other apps (0.16.0): deep violet with a soft lilac edge and a tail pointing at her — down to
 * her ears when the bubble is above her, up when it is below. White text on it keeps far more than 4.5:1.
 */
class BubbleDrawable(density: Float) : Drawable() {
    /** Where her middle is, in px from the bubble's left edge; the tail points there. Negative: the middle. */
    var tailX = -1f
    /** The bubble is above her: the tail is at the bottom, pointing down. */
    var tailDown = true
    /** How far the tail sticks out. */
    val tail = 7f * density
    /** The tail's x after keeping it on the straight part of the edge (the bubble's pivot when it pops out). */
    var tailAt = 0f; private set

    private val radius = 18f * density
    private val inset = 0.75f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F21C1430") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; strokeJoin = Paint.Join.ROUND; color = Color.parseColor("#80B98CFF")
    }
    private val body = Path()
    private val tri = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        val top = b.top + inset + if (tailDown) 0f else tail
        val bottom = b.bottom - inset - if (tailDown) tail else 0f
        rect.set(b.left + inset, top, b.right - inset, bottom)
        val lo = rect.left + radius + tail * 0.6f
        val hi = rect.right - radius - tail * 0.6f
        val want = if (tailX < 0f) rect.centerX() else b.left + tailX
        tailAt = if (lo <= hi) want.coerceIn(lo, hi) else rect.centerX()
        body.reset()
        body.addRoundRect(rect, radius, radius, Path.Direction.CW)
        tri.reset()
        if (tailDown) {
            tri.moveTo(tailAt - tail, bottom - 1f); tri.lineTo(tailAt, bottom + tail - inset); tri.lineTo(tailAt + tail, bottom - 1f)
        } else {
            tri.moveTo(tailAt - tail, top + 1f); tri.lineTo(tailAt, top - tail + inset); tri.lineTo(tailAt + tail, top + 1f)
        }
        tri.close()
        body.op(tri, Path.Op.UNION)
        c.drawPath(body, fill)
        c.drawPath(body, edge)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha * 0xF2 / 255
        edge.alpha = alpha * 0x80 / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter; edge.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
