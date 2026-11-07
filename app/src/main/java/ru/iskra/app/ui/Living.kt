package ru.iskra.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.diag.Diag
import ru.iskra.app.overlay.CanvasRigPainter
import ru.iskra.app.overlay.loadRigModel
import ru.iskra.app.overlay.loadRigTextures
import ru.iskra.app.system.DeviceHelper
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.RigDrawer
import ru.iskra.core.avatar.RigFraming
import ru.iskra.core.avatar.RigModel
import ru.iskra.core.avatar.RigState
import ru.iskra.core.i18n.tr
import ru.iskra.core.persona.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** A pause between frames of her life in the app: about 30 a second — breathing and blinking need no more. */
private const val FRAME_PAUSE_MS = 24L

/** The rig model and its layers per drawn size, decoded once (off the main thread) and shared by her screens. */
internal object RigCache {
    @Volatile private var model: RigModel? = null
    private val textures = LruCache<Int, Map<String, Bitmap>>(4)

    fun model(ctx: Context): RigModel = model ?: synchronized(this) { model ?: loadRigModel(ctx).also { model = it } }

    fun textures(ctx: Context, boxPx: Int): Map<String, Bitmap> = textures.get(boxPx) ?: synchronized(this) {
        textures.get(boxPx) ?: loadRigTextures(ctx, model(ctx), boxPx).also { textures.put(boxPx, it) }
    }
}

/**
 * Iskra as she lives in the app (0.16.0): the overlay's rig, driven by the talk instead of the screen — the emotion of her
 * last words, thinking (eyes up), listening (ears up, a nod), watching the person type (eyes down), lips moving while she
 * speaks; breathing, blinking, glances, ears, tail and ponytail of her own.
 */
internal class LiveRig(private val model: RigModel, textures: Map<String, Bitmap>) {
    val state = RigState().apply { setActivity(Activity.IDLE) }
    private val painter = CanvasRigPainter(textures)
    private var emotion = Emotion.NEUTRAL
    var thinking = false
    var listening = false
    var watching = false
    private var wasListening = false
    private var talkLeft = 0f
    private var lipSync = false
    private var level = 0f
    private var t = 0f

    fun feel(e: Emotion) {
        if (e == emotion) return
        val glad = e == Emotion.HAPPY || e == Emotion.EXCITED
        if (glad && emotion != Emotion.HAPPY && emotion != Emotion.EXCITED) state.joy()
        if (e == Emotion.POUTY) state.gesture(RigState.Gesture.SHAKE, 1.2f)
        emotion = e
    }

    /** [sync]: her voice plays and [mouth] levels follow until [stopTalking]; else the lips move for a while. */
    fun talk(text: String, sync: Boolean) { lipSync = sync; talkLeft = if (sync) 60f else (0.6f + text.length * 0.055f).coerceAtMost(6f) }
    fun mouth(v: Float) { level = v.coerceIn(0f, 1f) }
    fun stopTalking() { talkLeft = 0f; lipSync = false; level = 0f }
    fun wave() = state.gesture(RigState.Gesture.WAVE, 1.6f)
    fun hop() { state.joy(); state.gesture(RigState.Gesture.NOD, 0.7f) }

    fun advance(dt: Float) {
        val d = dt.coerceIn(0f, 0.1f)
        t += d
        if (listening && !wasListening) state.gesture(RigState.Gesture.NOD, 0.9f)
        wasListening = listening
        state.setEmotion(if (thinking) Emotion.THINKING else emotion)
        state.listening = listening
        state.setMouth(when {
            lipSync -> level
            talkLeft > 0f -> { talkLeft -= d; 0.2f + 0.45f * abs(sin(t * 12.5f)) * (0.7f + 0.3f * sin(t * 3.3f)) }
            else -> 0f
        })
        // Positive y looks up (RigDrawer): thinking — up and aside; watching the person type — down at the words.
        when {
            thinking -> state.lookAt(0.45f, 0.75f)
            watching -> state.lookAt(0f, -0.85f)
            else -> state.lookAt(null)
        }
        state.tick(d)
    }

    /** One still frame (Android's animations off): the face of the moment fully mixed in, the eyes open. */
    fun settle() {
        repeat(30) { advance(0.1f) }
        var guard = 0
        while (state.blink > 0.01f && guard++ < 5) advance(0.1f)
    }

    fun draw(c: android.graphics.Canvas, p: RigFraming.Placement) {
        painter.canvas = c
        c.save()
        c.translate(p.left, p.top)
        RigDrawer.draw(painter, model, state, p.box)
        c.restore()
        painter.canvas = null
    }
}

/** Her face for each emotion beside her messages: drawn once per size from the rig, as a still portrait. */
internal object RigFaces {
    private val cache = HashMap<Long, ImageBitmap>()
    private fun key(px: Int, e: Emotion) = (px.toLong() shl 8) or e.ordinal.toLong()

    fun cached(px: Int, e: Emotion): ImageBitmap? = synchronized(cache) { cache[key(px, e)] }

    fun render(ctx: Context, px: Int, e: Emotion): ImageBitmap? = cached(px, e) ?: runCatching {
        val place = RigFraming.portrait(px.toFloat(), px.toFloat())
        val rig = LiveRig(RigCache.model(ctx), RigCache.textures(ctx, place.box.roundToInt()))
        rig.feel(e)
        rig.settle()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        rig.draw(android.graphics.Canvas(bmp), place)
        bmp.asImageBitmap().also { synchronized(cache) { cache[key(px, e)] = it } }
    }.onFailure { Diag.e("avatar", tr("Лицо для чата не нарисовалось", "The chat face wasn't drawn"), it) }.getOrNull()
}

enum class Framing { FULL, PORTRAIT }

/** Android's «remove animations»: then she is a still picture in the app and nothing flies or pulses. */
@Composable
fun rememberStill(): Boolean {
    val ctx = LocalContext.current
    return remember { DeviceHelper.animationsOff(ctx) }
}

/** Seconds since the first frame, ~30 times a second while [running]; read it in drawing, not in composition. */
@Composable
fun rememberSeconds(running: Boolean): State<Float> {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it } - (s.floatValue * 1e9f).toLong()
        while (isActive) {
            withFrameNanos { s.floatValue = (it - start) / 1e9f }
            delay(FRAME_PAUSE_MS)
        }
    }
    return s
}

/**
 * Iskra alive on her own screens: in the chat header, the welcome and the introduction. [Framing.FULL] — the whole
 * figure standing on the bottom of the area; [Framing.PORTRAIT] — her head filling a circle (clip it to one).
 * Bump [wave] or [hop] to make her wave or hop with joy. With Android's animations off ([still]) one still frame that
 * follows the emotion. Until her layers are decoded (a moment, off the main thread) nothing is drawn; then she fades in.
 */
@Composable
fun LiveIskra(
    modifier: Modifier,
    framing: Framing,
    emotion: Emotion = Emotion.NEUTRAL,
    thinking: Boolean = false,
    listening: Boolean = false,
    watching: Boolean = false,
    still: Boolean = rememberStill(),
    wave: Int = 0,
    hop: Int = 0,
    /** Lips follow her speech ([CompanionBus]). */
    speaks: Boolean = true,
    alpha: () -> Float = { 1f },
    scale: () -> Float = { 1f },
) {
    val ctx = LocalContext.current.applicationContext
    BoxWithConstraints(modifier) {
        if (!constraints.hasBoundedWidth || constraints.maxWidth == 0) return@BoxWithConstraints
        val w = constraints.maxWidth.toFloat()
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else w
        val place = remember(framing, w, h) { if (framing == Framing.FULL) RigFraming.full(w, h) else RigFraming.portrait(w, h) }
        val boxPx = place.box.roundToInt().coerceAtLeast(1)
        val loaded by produceState<LiveRig?>(null, boxPx) {
            value = withContext(Dispatchers.Default) {
                runCatching { LiveRig(RigCache.model(ctx), RigCache.textures(ctx, boxPx)) }
                    .onFailure { Diag.e("avatar", tr("Живая Искра в приложении не загрузилась", "Live Iskra in the app didn't load"), it) }
                    .getOrNull()
            }
        }
        val rig = loaded ?: return@BoxWithConstraints
        SideEffect { rig.thinking = thinking; rig.listening = listening; rig.watching = watching }
        LaunchedEffect(rig, emotion) { rig.feel(emotion) }
        LaunchedEffect(rig, wave) { if (wave > 0 && !still) rig.wave() }
        LaunchedEffect(rig, hop) { if (hop > 0 && !still) rig.hop() }
        if (speaks) LaunchedEffect(rig) {
            CompanionBus.cues.collect { cue ->
                when (cue) {
                    is AvatarCue.Talk -> rig.talk(cue.text, cue.lipSync)
                    is AvatarCue.Mouth -> rig.mouth(cue.level)
                    AvatarCue.StopTalking -> rig.stopTalking()
                    else -> Unit
                }
            }
        }
        val frame = remember(rig) { mutableIntStateOf(0) }
        val shown = remember(rig) { Animatable(0f) }
        LaunchedEffect(rig) { shown.animateTo(1f, tween(260)) }
        LaunchedEffect(rig, still, if (still) Triple(emotion, thinking, listening) else null) {
            if (still) { rig.settle(); frame.intValue++; return@LaunchedEffect }
            var last = 0L
            while (isActive) {
                withFrameNanos { now ->
                    rig.advance(if (last == 0L) 0f else (now - last) / 1e9f)
                    last = now
                    frame.intValue++
                }
                delay(FRAME_PAUSE_MS)
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            this.alpha = shown.value * alpha()
            val s = scale()
            scaleX = s; scaleY = s
            transformOrigin = TransformOrigin(0.5f, if (framing == Framing.FULL) 0.9f else 0.5f)
        }) {
            frame.intValue // a new frame: draw again
            drawIntoCanvas { rig.draw(it.nativeCanvas, place) }
        }
    }
}

/** The four-pointed star of her name in a unit box (radius 1, centred at 0). */
internal fun sparkPath() = Path().apply {
    moveTo(0f, -1f)
    cubicTo(0.09f, -0.42f, 0.42f, -0.09f, 1f, 0f)
    cubicTo(0.42f, 0.09f, 0.09f, 0.42f, 0f, 1f)
    cubicTo(-0.09f, 0.42f, -0.42f, 0.09f, -1f, 0f)
    cubicTo(-0.42f, -0.09f, -0.09f, -0.42f, 0f, -1f)
    close()
}

private fun DrawScope.spark(path: Path, at: Offset, radius: Float, degrees: Float, color: Color) =
    withTransform({ translate(at.x, at.y); rotate(degrees, Offset.Zero); scale(radius, radius, Offset.Zero) }) { drawPath(path, color) }

/** Her glow: a soft light behind her that breathes slowly; [strength] above 1 while she thinks or listens. */
@Composable
fun Aura(modifier: Modifier, still: Boolean, strength: Float = 1f) {
    val t = rememberSeconds(!still)
    val color = iskraColors.aura
    Canvas(modifier) {
        val breath = if (still) 0.5f else 0.5f + 0.5f * sin(t.value * 1.6f)
        val r = size.minDimension / 2f * (0.9f + 0.1f * breath)
        val a = ((0.2f + 0.1f * breath) * strength).coerceAtMost(0.6f)
        drawCircle(Brush.radialGradient(0f to color.copy(alpha = a), 0.55f to color.copy(alpha = a * 0.4f), 1f to Color.Transparent,
            center = center, radius = r), radius = r, center = center)
    }
}

private class Twinkle(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val color: Int)

/** Little sparks twinkling around her, in her three colours and white; a ring around the middle of the area. */
@Composable
fun Sparkles(modifier: Modifier, still: Boolean, count: Int = 8, seed: Int = 3) {
    val t = rememberSeconds(!still)
    val colors = iskraColors.sparks
    val path = remember { sparkPath() }
    val stars = remember(count, seed) {
        val rnd = Random(seed)
        List(count) { i ->
            val a = i * 2 * PI / count + rnd.nextDouble(-0.25, 0.25)
            val r = 0.36 + rnd.nextDouble(0.0, 0.12)
            Twinkle((0.5 + cos(a) * r).toFloat(), (0.48 + sin(a) * r * 0.92).toFloat(), 3f + rnd.nextFloat() * 4f,
                0.8f + rnd.nextFloat() * 1.1f, rnd.nextFloat() * 6.3f, i)
        }
    }
    Canvas(modifier) {
        val time = t.value
        for (s in stars) {
            val k = if (still) 0.55f else max(0f, sin(time * s.speed + s.phase)).pow(2)
            if (k < 0.03f) continue
            spark(path, Offset(s.x * size.width, s.y * size.height), s.size.dp.toPx() * (0.45f + 0.55f * k),
                if (still) 0f else time * 24f * s.speed, colors[s.color % colors.size].copy(alpha = 0.9f * k))
        }
    }
}

/**
 * Her birth (0.16.0), on the first page of the introduction. [Birth.MS] long, `p` running 0 → 1: a spark lights up
 * where her heart will be and beats twice (to 0.38), flares and scatters into sparks (0.38–0.95) while she comes out of
 * the light (from 0.42); the words come last (from 0.62).
 */
object Birth {
    const val MS = 2600
    const val FLARE = 0.38f
    fun figure(p: Float) = ((p - 0.42f) / 0.2f).coerceIn(0f, 1f)
    fun figureScale(p: Float) = 0.8f + 0.2f * backOut(((p - 0.42f) / 0.36f).coerceIn(0f, 1f))
    fun words(p: Float) = ((p - 0.62f) / 0.25f).coerceIn(0f, 1f)
    private fun backOut(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }
    internal fun easeOut(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
}

private class Ember(val angle: Float, val dist: Float, val size: Float, val spin: Float, val color: Int)

/** The light of her birth, drawn over her: [progress] from [Birth]; [heart] — where it shines, as a share of the area. */
@Composable
fun BirthFlare(modifier: Modifier, progress: () -> Float, heart: Offset) {
    val colors = iskraColors
    val path = remember { sparkPath() }
    val embers = remember {
        val rnd = Random(11)
        List(32) { i ->
            Ember((i * 2 * PI / 32 + rnd.nextDouble(-0.1, 0.1)).toFloat(), 0.28f + rnd.nextFloat() * 0.36f, 2.6f + rnd.nextFloat() * 2.8f,
                (if (rnd.nextBoolean()) 1f else -1f) * (0.5f + rnd.nextFloat()), i)
        }
    }
    Canvas(modifier) {
        val p = progress()
        if (p <= 0f || p >= 1f) return@Canvas
        val c = Offset(heart.x * size.width, heart.y * size.height)
        val unit = size.minDimension
        val gold = colors.sparkGold; val pink = colors.sparkPink
        // The spark: lights up, beats twice, flares and is gone.
        val base = 7.dp.toPx()
        val (core, coreAlpha) = when {
            p < 0.2f -> base * Birth.easeOut(p / 0.2f) to 1f
            p < Birth.FLARE -> base * (1f + 0.3f * max(0f, sin((p - 0.2f) / 0.18f * 4f * PI.toFloat()))) to 1f
            p < 0.5f -> ((p - Birth.FLARE) / 0.12f).let { k -> base * (1f + 2.6f * Birth.easeOut(k)) to 1f - k }
            else -> 0f to 0f
        }
        if (coreAlpha > 0f && core > 0f) {
            val glow = core * 6f
            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = coreAlpha), 0.18f to gold.copy(alpha = 0.85f * coreAlpha),
                0.5f to pink.copy(alpha = 0.35f * coreAlpha), 1f to Color.Transparent, center = c, radius = glow), glow, c)
            spark(path, c, core * 1.9f, p * 90f, Color.White.copy(alpha = coreAlpha))
        }
        // The flash she comes out of: white at the heart, pink at the edge (a gold haze looked muddy in the preview).
        if (p in Birth.FLARE..0.64f) {
            val k = (p - Birth.FLARE) / (0.64f - Birth.FLARE)
            val r = (unit * 0.62f * Birth.easeOut(k * 2f)).coerceAtLeast(1f)
            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.92f * (1f - k)), 0.35f to pink.copy(alpha = 0.16f * (1f - k)),
                1f to Color.Transparent, center = c, radius = r), r, c)
        }
        // A ring running out.
        if (p in Birth.FLARE..0.78f) {
            val k = (p - Birth.FLARE) / (0.78f - Birth.FLARE)
            drawCircle(pink.copy(alpha = 0.7f * (1f - k)), unit * 0.55f * Birth.easeOut(k), c,
                style = Stroke(width = (3f * (1f - k) + 0.6f).dp.toPx()))
        }
        // Sparks flying apart, slowing down, rising a little and fading.
        if (p in Birth.FLARE..0.97f) {
            val k = (p - Birth.FLARE) / (0.97f - Birth.FLARE)
            val far = Birth.easeOut(k)
            val sparks = colors.sparks
            for (e in embers) {
                val at = Offset(c.x + cos(e.angle) * e.dist * unit * far, c.y + sin(e.angle) * e.dist * unit * far - k * unit * 0.06f)
                spark(path, at, e.size.dp.toPx() * (1f - 0.55f * k), k * 200f * e.spin, sparks[e.color % sparks.size].copy(alpha = (1f - k).pow(1.4f)))
            }
        }
    }
}

/**
 * Comes in: rises a little, grows from [fromEnd]'s corner and fades in, with a soft spring, after [delayMs]. Off when
 * [animate] is false (an old message, or Android's animations are off — then the spring ends at once anyway).
 */
@Composable
fun Appear(animate: Boolean, modifier: Modifier = Modifier, delayMs: Int = 0, fromEnd: Boolean = false, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
    }
    Box(modifier.graphicsLayer {
        val v = p.value
        // Never wholly invisible: on a slow frame (the emulator stalled ~1 s right after a send) the new message would
        // vanish until the next one.
        alpha = (0.45f + v * 0.9f).coerceIn(0f, 1f)
        translationY = (1f - v) * 14.dp.toPx()
        val s = 0.94f + 0.06f * v
        scaleX = s; scaleY = s
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
    }) { content() }
}
