package ru.iskra.app.overlay

import ru.iskra.core.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ru.iskra.app.R
import ru.iskra.app.diag.Diag
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.avatar.Sprite
import ru.iskra.core.avatar.SpriteAnimator
import ru.iskra.core.avatar.SpriteLayout
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * The 15 sprites (drawable-nodpi, lossless WebP), decoded on first use and scaled once to the size they
 * are drawn at, so each frame is a 1:1 copy (crisp and cheap). ~0.5 MB per sprite at 120 dp.
 */
class SpriteBitmaps(private val ctx: Context, private val box: Int) {
    private val cache = EnumMap<Sprite, Bitmap>(Sprite::class.java)
    private var failed = false

    fun get(s: Sprite): Bitmap? = cache[s] ?: load(s)?.also { cache[s] = it }

    private fun load(s: Sprite): Bitmap? {
        if (failed) return null
        return try {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            val raw = BitmapFactory.decodeResource(ctx.resources, resId(s), opts) ?: error("decode returned null")
            val px = (Sprite.SIZE * SpriteLayout.scale(s, box.toFloat())).roundToInt().coerceAtLeast(1)
            if (px < raw.width) Bitmap.createScaledBitmap(raw, px, px, true).also { if (it !== raw) raw.recycle() } else raw
        } catch (e: Throwable) {
            failed = true
            Diag.e("avatar", tr("Спрайт ${s.file} не загрузился", "Sprite ${s.file} didn't load"), e)
            null
        }
    }

    fun release() { cache.values.forEach { it.recycle() }; cache.clear() }

    companion object {
        fun resId(s: Sprite): Int = when (s) {
            Sprite.STAND -> R.drawable.iskra_stand
            Sprite.SIT -> R.drawable.iskra_sit
            Sprite.SLEEP -> R.drawable.iskra_sleep
            Sprite.WALK01 -> R.drawable.iskra_walk_01
            Sprite.WALK02 -> R.drawable.iskra_walk_02
            Sprite.WAVE -> R.drawable.iskra_wave
            Sprite.PHONE -> R.drawable.iskra_phone
            Sprite.JOY -> R.drawable.iskra_joy
            Sprite.SAD -> R.drawable.iskra_sad
            Sprite.ANGRY -> R.drawable.iskra_angry
            Sprite.SURPRISE -> R.drawable.iskra_surprise
            Sprite.SHY -> R.drawable.iskra_shy
            Sprite.THOUGHTFUL -> R.drawable.iskra_thoughtful
            Sprite.SLEEPY -> R.drawable.iskra_sleepy
            Sprite.SLY -> R.drawable.iskra_sly
        }
    }
}

/** Common placement for the new looks: shadow, wall offset, peeking rotation, mirroring, breathing. */
internal object LookPlacement {
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 0, 0, 0) }
    private val oval = RectF()

    fun onFloor(a: Activity) = a == Activity.IDLE || a == Activity.WALKING || a == Activity.RUNNING ||
        a == Activity.SITTING || a == Activity.SLEEPING

    /** Applies the body transform for [p] (call inside save/restore). */
    fun begin(c: Canvas, box: Float, p: AvatarPose) {
        if (onFloor(p.activity)) {
            val y = box * SpriteLayout.BASELINE
            oval.set(box * 0.3f, y - box * 0.025f, box * 0.7f, y + box * 0.015f)
            c.drawOval(oval, shadow)
        }
        if (p.rotation != 0f) c.rotate(p.rotation, box / 2f, box / 2f)
        if (p.wallSide != 0) c.translate(p.wallSide * box * 0.2f, 0f)
    }

    /** Mirror around the box centre and squash around the feet. */
    fun mirrorAndBreathe(c: Canvas, box: Float, mirror: Boolean, scaleY: Float) {
        if (mirror) c.scale(-1f, 1f, box / 2f, 0f)
        if (scaleY != 1f) c.scale(1f, scaleY, box / 2f, box * SpriteLayout.BASELINE)
    }

    fun drawSprite(c: Canvas, bmp: Bitmap, s: Sprite, box: Float, paint: Paint, dst: RectF) {
        val r = SpriteLayout.rect(s, box)
        dst.set(r.left, r.top, r.right, r.bottom)
        c.drawBitmap(bmp, null, dst, paint)
    }
}

/** «Спрайты» look (4.1): one picture per state, 2-frame walk, breathing, crossfade between states. */
class SpriteRenderer(ctx: Context, box: Int) : AvatarRenderer {
    private val bitmaps = SpriteBitmaps(ctx, box)
    private val animator = SpriteAnimator()
    private val effects = AvatarEffects(nunitoBold(ctx))
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private var frame = animator.update(0f, AvatarPoseDefaults.pose)
    private val notes = FallbackNotes()

    init { check(bitmaps.get(Sprite.STAND) != null) { "sprites not available" } }

    override fun advance(dt: Float, pose: AvatarPose) {
        frame = animator.update(dt, pose)
        frame.fallback?.let(notes::log)
    }

    override fun wantsFullRate(p: AvatarPose) = p.moving || animator.fading

    override fun draw(c: Canvas, box: Float, p: AvatarPose) {
        c.save()
        LookPlacement.begin(c, box, p)
        val talk = if (p.talking) 1f - 0.012f * p.mouthOpen else 1f
        for (layer in frame.layers) {
            val bmp = bitmaps.get(layer.sprite) ?: continue
            c.save()
            LookPlacement.mirrorAndBreathe(c, box, layer.mirror, frame.scaleY * talk)
            paint.alpha = (layer.alpha * 255).roundToInt()
            LookPlacement.drawSprite(c, bmp, layer.sprite, box, paint, dst)
            c.restore()
        }
        c.restore()
        if (p.rotation == 0f) drawEffects(c, box, p, effects)
    }

    override fun release() = bitmaps.release()
}

internal fun drawEffects(c: Canvas, box: Float, p: AvatarPose, effects: AvatarEffects) {
    c.save()
    c.translate(box / 2f, box)
    c.scale(box / 100f, box / 100f)
    effects.draw(c, p, sprites = true)
    c.restore()
}

/** Her font for the marks over her ("?", "!", "z"), if it loads. */
internal fun nunitoBold(ctx: Context): android.graphics.Typeface? =
    runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.nunito_bold) }.getOrNull()

/** Writes each "no own sprite/pose for this state" note to the journal once per session. */
internal class FallbackNotes {
    private val seen = HashSet<String>()
    fun log(note: String) {
        if (seen.size < 40 && seen.add(note)) Diag.i("avatar", tr("Нет своей позы: $note", "No own pose: $note"))
    }
}

internal object AvatarPoseDefaults {
    val pose = AvatarPose(0f, 0f, false, Activity.IDLE, ru.iskra.core.persona.Emotion.NEUTRAL, 1f, 0f, 0f, 0f, 0f, 0f, 0f)
}
