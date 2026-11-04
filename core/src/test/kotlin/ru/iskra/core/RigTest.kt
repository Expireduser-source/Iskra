package ru.iskra.core

import ru.iskra.core.avatar.RigDrawer
import ru.iskra.core.avatar.RigModel
import ru.iskra.core.avatar.RigPainter
import ru.iskra.core.avatar.RigState
import ru.iskra.core.avatar.Sprite
import ru.iskra.core.avatar.SpriteLayout
import ru.iskra.core.persona.Emotion
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Java2D implementation of [RigPainter]: renders the rig on the JVM into a PNG. */
class Java2DPainter(private val g: java.awt.Graphics2D, private val images: Map<String, BufferedImage>) : RigPainter {
    private val stack = ArrayDeque<Pair<AffineTransform, java.awt.Shape?>>()
    private var path = Path2D.Float()
    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }
    override fun save() { stack.addLast(g.transform to g.clip) }
    override fun restore() { val (t, c) = stack.removeLast(); g.transform = t; g.clip = c }
    override fun translate(x: Float, y: Float) = g.translate(x.toDouble(), y.toDouble())
    override fun rotate(rad: Float) = g.rotate(rad.toDouble())
    override fun scale(x: Float, y: Float) = g.scale(x.toDouble(), y.toDouble())
    override fun image(part: String, left: Float, top: Float, right: Float, bottom: Float) {
        val img = images.getValue(part)
        val t = AffineTransform(g.transform)
        g.translate(left.toDouble(), top.toDouble())
        g.scale(((right - left) / img.width).toDouble(), ((bottom - top) / img.height).toDouble())
        g.drawImage(img, 0, 0, null)
        g.transform = t
    }
    override fun beginPath() { path = Path2D.Float() }
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadTo(cx, cy, x, y)
    override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) = path.curveTo(c1x, c1y, c2x, c2y, x, y)
    override fun closePath() = path.closePath()
    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        path.append(Ellipse2D.Float(cx - rx, cy - ry, 2 * rx, 2 * ry), false)
    override fun fill(color: Int) { g.color = Color(color, true); g.fill(path) }
    override fun stroke(color: Int, width: Float) {
        g.color = Color(color, true); g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path)
    }
    override fun clip() = g.clip(path)
    override fun glow(cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        val c0 = Color((color and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24), true)
        g.paint = RadialGradientPaint(Point2D.Float(cx, cy), radius, floatArrayOf(0f, 1f), arrayOf(c0, Color(color and 0xFFFFFF, true)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE)
        g.fill(Ellipse2D.Float(cx - radius, cy - radius, 2 * radius, 2 * radius))
    }
}

class RigTest {
    private val dir = File("../app/src/main/assets/rig")
    private val model = RigModel.parse(File(dir, "iskra.rig.json").readText())
    private val images = model.parts.mapValues { (_, p) -> ImageIO.read(File(dir, p.file)) }

    private fun render(s: RigState, box: Int = 330): BufferedImage {
        val img = BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        RigDrawer.draw(Java2DPainter(g, images), model, s, box.toFloat())
        g.dispose()
        return img
    }

    @Test
    fun modelHasAllTenLayers() {
        assertEquals(RigModel.NAMES.toSet(), model.parts.keys)
        model.parts.values.forEach { assertTrue(File(dir, it.file).isFile, it.file) }
    }

    @Test
    fun rendersFeetOnTheSpriteBaselineAndHeadAsBigAsTheSprites() {
        val img = render(RigState().apply { repeat(15) { tick(1f / 30f) } })
        var bottom = 0; var top = img.height
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) ushr 24) > 128) { bottom = maxOf(bottom, y); top = minOf(top, y) }
        val box = img.height.toFloat()
        assertTrue(abs(bottom - box * SpriteLayout.BASELINE) <= 4f, "feet at $bottom")
        assertTrue(top >= 0 && top < box * 0.15f, "ears at $top")
        val rigHead = RigDrawer.HEAD * RigDrawer.scale(box)
        val spriteHead = SpriteLayout.headSize(Sprite.STAND, box)
        assertTrue(abs(rigHead / spriteHead - 1f) < 0.06f, "rig head $rigHead vs sprite $spriteHead")
    }

    @Test
    fun allExpressionsBlendAndRender() {
        val out = File("build/rig-preview").apply { mkdirs() }
        val names = RigState.EXPRESSIONS.keys.toList()
        val sheet = BufferedImage(330 * names.size, 330, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        g.color = Color(0xE6, 0xF0, 0xE6); g.fillRect(0, 0, sheet.width, sheet.height)
        g.composite = AlphaComposite.SrcOver
        names.forEachIndexed { i, n ->
            val s = RigState(); s.setExpression(n); repeat(45) { s.tick(1f / 30f) }
            g.drawImage(render(s), i * 330, 0, null)
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(out, "expressions.png"))
    }

    @Test
    fun gesturesRender() {
        val out = File("build/rig-preview").apply { mkdirs() }
        val cases = listOf("wave" to RigState.Gesture.WAVE, "nod" to RigState.Gesture.NOD, "shake" to RigState.Gesture.SHAKE)
        val sheet = BufferedImage(330 * 4, 330, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        g.color = Color(0xE6, 0xF0, 0xE6); g.fillRect(0, 0, sheet.width, sheet.height)
        cases.forEachIndexed { i, (_, ges) ->
            val s = RigState(); s.tick(0.1f); s.gesture(ges, 2f); repeat(14) { s.tick(1f / 30f) }
            assertTrue(s.envelope > 0.9f)
            g.drawImage(render(s), i * 330, 0, null)
        }
        val w = RigState(); repeat(30) { w.tick(1f / 30f, walking = true) }
        g.drawImage(render(w), 3 * 330, 0, null)
        g.dispose()
        ImageIO.write(sheet, "png", File(out, "gestures.png"))
    }

    @Test
    fun emotionsBlendSmoothlyNotInOneFrame() {
        val s = RigState(); repeat(30) { s.tick(1f / 30f) }
        s.setEmotion(Emotion.SAD)
        s.tick(1f / 30f)
        val brow1 = s.face.brow
        assertTrue(brow1 < 0f && brow1 > -0.9f * 0.5f, "after one frame brow=$brow1")
        repeat(60) { s.tick(1f / 30f) }
        assertEquals(-0.9f, s.face.brow, 0.02f)
        assertEquals("sad", s.expression)
        assertEquals(9, RigState.EXPRESSIONS.size) // neutral + 8 emotions
        Emotion.entries.forEach { assertTrue(RigState.expressionFor(it).first in RigState.EXPRESSIONS) }
    }

    @Test
    fun blinksEveryFewSeconds() {
        val s = RigState()
        var blinks = 0; var was = false
        repeat(30 * 20) { s.tick(1f / 30f); val b = s.blink > 0.5f; if (b && !was) blinks++; was = b }
        assertTrue(blinks in 3..8, "blinks in 20 s: $blinks")
    }

    @Test
    fun earsTailPonytailSwingWhenSheIsYankedThenSettle() {
        val s = RigState(); s.energy = 0f; repeat(90) { s.tick(1f / 30f) }
        repeat(6) { s.tick(1f / 30f, bodyAccX = 25f) }        // sudden push to the side
        val swung = listOf(s.pony.angle, s.tail.angle, s.earL.angle)
        assertTrue(swung.all { abs(it) > 0.02f }, "swing $swung")
        repeat(30 * 4) { s.tick(1f / 30f) }
        assertTrue(listOf(s.pony.angle, s.tail.angle, s.earL.angle).all { abs(it) < 0.01f }, "settled")
    }

    @Test
    fun stableAtLowFrameRate() {
        val s = RigState()
        repeat(24 * 60) { s.tick(1f / 24f, walking = it % 200 < 100, bodyAccX = if (it % 50 == 0) 30f else 0f) }
        listOf(s.pony.angle, s.tail.angle, s.earL.angle, s.earR.angle, s.head).forEach { assertTrue(it.isFinite() && abs(it) < 0.9f) }
    }

    /** 0.11 poses: one frame per activity and two film strips; written to core/build/rig-preview/poses*.png for review. */
    @Test
    fun posesForEveryActivity() {
        val out = File("build/rig-preview").apply { mkdirs() }
        val acts = listOf(ru.iskra.core.avatar.Activity.IDLE to 0f, ru.iskra.core.avatar.Activity.RUNNING to 0f,
            ru.iskra.core.avatar.Activity.JUMPING to -2f, ru.iskra.core.avatar.Activity.JUMPING to 2f, ru.iskra.core.avatar.Activity.FALLING to 3f,
            ru.iskra.core.avatar.Activity.DRAGGED to 0f, ru.iskra.core.avatar.Activity.CLIMBING to 0f, ru.iskra.core.avatar.Activity.HANGING to 0f,
            ru.iskra.core.avatar.Activity.PEEKING to 0f)
        val box = 300
        val sheet = BufferedImage(box * acts.size, box, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics(); g.color = Color(0x2a1f3a); g.fillRect(0, 0, sheet.width, sheet.height)
        acts.forEachIndexed { i, (a, vy) ->
            val s = RigState()
            repeat(36) { k -> s.setActivity(a, vy); s.tick(1f / 30f, walking = a == ru.iskra.core.avatar.Activity.RUNNING, bodyAccX = if (a == ru.iskra.core.avatar.Activity.DRAGGED && k in 20..24) 18f else 0f) }
            listOf(s.pose.armL, s.pose.armR, s.pony.angle, s.tail.angle, s.legSwingL.angle).forEach { assertTrue(it.isFinite()) }
            g.drawImage(render(s, box), i * box, 0, null)
        }
        g.dispose(); ImageIO.write(sheet, "png", File(out, "poses.png"))

        // Jump up → come down → land (squash) → stand; and a fall with flailing, 10 frames each.
        fun strip(name: String, plan: (Int) -> Pair<ru.iskra.core.avatar.Activity, Float>) {
            val frames = 10; val img = BufferedImage(box * frames, box, BufferedImage.TYPE_INT_ARGB)
            val gg = img.createGraphics(); gg.color = Color(0x2a1f3a); gg.fillRect(0, 0, img.width, img.height)
            val s = RigState()
            for (f in 0 until frames) {
                repeat(4) { val (a, vy) = plan(f); s.setActivity(a, vy); s.tick(1f / 30f) }
                gg.drawImage(render(s, box), f * box, 0, null)
            }
            gg.dispose(); ImageIO.write(img, "png", File(out, "poses-$name.png"))
        }
        strip("jump") { f -> when { f < 3 -> ru.iskra.core.avatar.Activity.JUMPING to -2f; f < 6 -> ru.iskra.core.avatar.Activity.JUMPING to 2f; else -> ru.iskra.core.avatar.Activity.IDLE to 0f } }
        strip("fall") { f -> if (f < 7) ru.iskra.core.avatar.Activity.FALLING to 3f else ru.iskra.core.avatar.Activity.IDLE to 0f }
    }

    @Test
    fun posesEaseInLandingSquashesAndJoyWags() {
        val s = RigState(); s.energy = 0f
        s.setActivity(ru.iskra.core.avatar.Activity.FALLING, 3f)
        s.tick(1f / 30f)
        assertTrue(s.pose.armL in 0.01f..0.9f, "eases in, not a jump: ${s.pose.armL}")
        repeat(30) { s.tick(1f / 30f) }
        assertTrue(s.pose.armL > 0.95f, "arms up while falling: ${s.pose.armL}")
        s.setActivity(ru.iskra.core.avatar.Activity.IDLE)
        assertTrue(s.landing > 0.1f, "landing squash")
        repeat(30) { s.tick(1f / 30f) }
        assertTrue(s.landing < 0.02f && abs(s.pose.armL) < 0.05f, "back to standing")
        // Dragging to the side swings the dangling legs, then they settle.
        s.setActivity(ru.iskra.core.avatar.Activity.DRAGGED)
        repeat(20) { s.tick(1f / 30f) }
        repeat(5) { s.tick(1f / 30f, bodyAccX = 20f) }
        assertTrue(abs(s.legSwingL.angle) > 0.02f, "legs swing: ${s.legSwingL.angle}")
        s.joy(); assertTrue(s.wag == 1f && s.landing > 0.05f)
        repeat(30 * 3) { s.tick(1f / 30f) }
        assertTrue(s.wag < 0.01f, "wag fades")
    }

    /** Frames of a short demo (wave, walk, jump, land, joy) for the site and README: build/rig-preview/demo/. */
    @Test
    fun demoFrames() {
        val out = File("build/rig-preview/demo").apply { deleteRecursively(); mkdirs() }
        val s = RigState()
        val fps = 20; val size = 360; val box = 280
        val jumpStart = 2.0f; val jumpEnd = 3.0f
        for (f in 0 until fps * 5) {
            val t = f.toFloat() / fps
            val a = when {
                t in jumpStart..(jumpStart + 0.5f) -> ru.iskra.core.avatar.Activity.JUMPING to -2f
                t in (jumpStart + 0.5f)..jumpEnd -> ru.iskra.core.avatar.Activity.JUMPING to 2f
                else -> ru.iskra.core.avatar.Activity.IDLE to 0f
            }
            if (f == 2) s.gesture(RigState.Gesture.WAVE, 1.4f)
            if (f == (jumpEnd * fps).toInt() + 1) { s.setExpression("joy"); s.joy() }
            if (t > 4.4f) s.setExpression("neutral")
            repeat(2) { s.setActivity(a.first, a.second); s.tick(1f / (fps * 2), walking = t in 1.1f..1.95f) }
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            // Transparent background (the site has light and dark themes). A soft floor shadow, smaller while she is up in the air.
            val h = if (t in jumpStart..jumpEnd) kotlin.math.sin(((t - jumpStart) / (jumpEnd - jumpStart)) * Math.PI).toFloat() else 0f
            val sw = (150 * (1f - 0.45f * h)).toInt()
            g.color = Color(0, 0, 0, 70); g.fillOval(size / 2 - sw / 2, size - 52, sw, 18)
            g.drawImage(render(s, box), (size - box) / 2, (size - box) / 2 + 10 - (h * 48).toInt(), null)
            g.dispose()
            ImageIO.write(img, "png", File(out, "f%03d.png".format(f)))
        }
        assertTrue(File(out, "f099.png").exists())
    }
}
