package ru.iskra.app.overlay

import ru.iskra.core.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import ru.iskra.app.diag.Diag
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.avatar.RigAnimator
import ru.iskra.core.avatar.RigDrawer
import ru.iskra.core.avatar.RigModel
import ru.iskra.core.avatar.RigPainter
import ru.iskra.core.avatar.Visual
import kotlin.math.roundToInt

/** [RigPainter] on Android's Canvas. Paths and paints are reused: no allocations per frame except shaders once. */
class CanvasRigPainter(private val textures: Map<String, Bitmap>) : RigPainter {
    var canvas: Canvas? = null
    private val c get() = canvas!!
    private val path = Path()
    private val oval = RectF()
    private val dst = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaders = HashMap<Long, Shader>()

    override fun save() { c.save() }
    override fun restore() = c.restore()
    override fun translate(x: Float, y: Float) = c.translate(x, y)
    override fun rotate(rad: Float) = c.rotate(Math.toDegrees(rad.toDouble()).toFloat())
    override fun scale(x: Float, y: Float) = c.scale(x, y)
    override fun image(part: String, left: Float, top: Float, right: Float, bottom: Float) {
        val b = textures[part] ?: return
        dst.set(left, top, right, bottom)
        c.drawBitmap(b, null, dst, bitmapPaint)
    }
    override fun beginPath() = path.reset()
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadTo(cx, cy, x, y)
    override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) = path.cubicTo(c1x, c1y, c2x, c2y, x, y)
    override fun closePath() = path.close()
    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry); path.addOval(oval, Path.Direction.CW)
    }
    override fun fill(color: Int) { fillPaint.color = color; c.drawPath(path, fillPaint) }
    override fun stroke(color: Int, width: Float) { strokePaint.color = color; strokePaint.strokeWidth = width; c.drawPath(path, strokePaint) }
    override fun clip() { c.clipPath(path) }
    override fun glow(cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        val key = (cx.toLong() shl 40) xor (cy.toLong() shl 20) xor radius.toLong() xor (color.toLong() shl 8)
        glowPaint.shader = shaders.getOrPut(key) {
            RadialGradient(cx, cy, radius, color or (0xFF shl 24), color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        glowPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        c.drawCircle(cx, cy, radius, glowPaint)
    }
}

/** The rig model from assets/rig. */
fun loadRigModel(ctx: Context): RigModel =
    RigModel.parse(ctx.assets.open("rig/iskra.rig.json").use { it.readBytes().toString(Charsets.UTF_8) })

/** The rig's layers, scaled once to the size they are drawn at in a [boxPx] box (the overlay and the app's own screens). */
fun loadRigTextures(ctx: Context, model: RigModel, boxPx: Int): Map<String, Bitmap> {
    val k = RigDrawer.scale(boxPx.toFloat())
    return model.parts.mapValues { (_, p) ->
        val raw = ctx.assets.open("rig/" + p.file).use { BitmapFactory.decodeStream(it) } ?: error("layer ${p.file}")
        val w = ((p.rect[2] - p.rect[0]) * k).roundToInt().coerceAtLeast(1)
        val h = ((p.rect[3] - p.rect[1]) * k).roundToInt().coerceAtLeast(1)
        // Drawn a bit larger than 1:1 when rotated; 1.5× keeps edges smooth without the full texture.
        val tw = (w * 1.5f).roundToInt(); val th = (h * 1.5f).roundToInt()
        if (tw < raw.width) Bitmap.createScaledBitmap(raw, tw, th, true).also { if (it !== raw) raw.recycle() } else raw
    }
}

/**
 * «Живой риг» look (4.2): the rig for standing, walking, talking and in the air; sit / sleep / phone
 * from the sprites (4.1), switched with a crossfade. Textures come from assets/rig and are scaled once
 * to the size they are drawn at.
 */
class RigRenderer(ctx: Context, private val boxPx: Int) : AvatarRenderer {
    private val model: RigModel
    private val painter: CanvasRigPainter
    private val sprites = SpriteBitmaps(ctx, boxPx)
    private val animator = RigAnimator()
    private val effects = AvatarEffects()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val notes = FallbackNotes()
    private var frame = animator.update(0f, AvatarPoseDefaults.pose, boxPx.toFloat())

    init {
        model = loadRigModel(ctx)
        val tex = loadRigTextures(ctx, model, boxPx)
        painter = CanvasRigPainter(tex)
        Diag.i("avatar", tr("Риг загружен: ${tex.size} слоёв, ${boxPx}px", "Rig loaded: ${tex.size} layers, ${boxPx}px"))
    }

    override fun advance(dt: Float, pose: AvatarPose) {
        frame = animator.update(dt, pose, boxPx.toFloat())
        frame.fallback?.let(notes::log)
    }

    override fun wantsFullRate(p: AvatarPose) = p.moving || animator.fading

    override fun draw(c: Canvas, box: Float, p: AvatarPose) {
        c.save()
        LookPlacement.begin(c, box, p)
        for (layer in frame.layers) {
            val alpha = (layer.alpha * 255).roundToInt()
            if (alpha <= 0) continue
            when (val v = layer.visual) {
                Visual.Rig -> {
                    val faded = alpha < 255
                    if (faded) c.saveLayerAlpha(0f, 0f, box, box, alpha) else c.save()
                    LookPlacement.mirrorAndBreathe(c, box, layer.mirror, 1f)
                    painter.canvas = c
                    RigDrawer.draw(painter, model, animator.rig, box)
                    painter.canvas = null
                    c.restore()
                }
                is Visual.Picture -> {
                    val bmp = sprites.get(v.sprite) ?: continue
                    c.save()
                    LookPlacement.mirrorAndBreathe(c, box, layer.mirror, frame.scaleY)
                    paint.alpha = alpha
                    LookPlacement.drawSprite(c, bmp, v.sprite, box, paint, dst)
                    c.restore()
                }
            }
        }
        c.restore()
        if (p.rotation == 0f) drawEffects(c, box, p, effects)
    }

    override fun release() = sprites.release()
}
