package ru.iskra.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import ru.iskra.core.avatar.Activity
import ru.iskra.core.avatar.AvatarBrain
import ru.iskra.core.avatar.AvatarPose
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The character's window content. Runs the [AvatarBrain] simulation on Choreographer (vsync) frames:
 * every display refresh while she moves, a lower rate while she stands still, 10 fps asleep.
 *
 * The window may show only part of her (at a screen edge); [offsetX]/[offsetY] place the full
 * [box]-sized character relative to the window.
 */
@SuppressLint("ViewConstructor")
class AvatarView(
    context: Context,
    val brain: AvatarBrain,
    private val box: Int,
    private val host: Host,
) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onPose(pose: AvatarPose)
        fun onTap()
        fun onDoubleTap()
        fun onLongPress()
        fun onDragFinished(flung: Boolean)
    }

    /** Max frames per second while moving; 0 = every vsync (the display's refresh rate). */
    var movingFps = 0
    /** Max frames per second while standing still. */
    var idleFps = 60

    var offsetX = 0f
    var offsetY = 0f

    private val renderer = CatgirlRenderer()
    private var pose: AvatarPose = brain.update(0f)
    private var lastFrameNs = 0L
    private var lastVsyncNs = 0L
    private var running = false

    // Drag state (raw screen coordinates, because the window itself moves under the finger).
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { host.onTap(); brain.tapped(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { brain.tapped(); host.onDoubleTap(); return true }
        override fun onLongPress(e: MotionEvent) { if (!dragging) host.onLongPress() }
    })

    init {
        contentDescription = "Искра — анимированный персонаж. Нажми, чтобы поговорить; удерживай для меню."
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Measure the real vsync rate (what the display gives us) for the settings screen.
        if (lastVsyncNs != 0L) {
            val dt = frameTimeNanos - lastVsyncNs
            if (dt in 4_000_000L..50_000_000L) FrameStats.addVsync(dt)
        }
        lastVsyncNs = frameTimeNanos

        val fps = when {
            pose.activity == Activity.SLEEPING -> 10
            pose.moving || pose.talking || dragging -> movingFps
            else -> idleFps
        }
        // 1.5 ms slack so vsync jitter does not make us skip every other frame.
        val minIntervalNs = if (fps <= 0) 0L else 1_000_000_000L / fps - 1_500_000L
        if (lastFrameNs == 0L || frameTimeNanos - lastFrameNs >= minIntervalNs) {
            val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
            lastFrameNs = frameTimeNanos
            pose = brain.update(dt)
            host.onPose(pose)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(offsetX, offsetY)
        renderer.draw(canvas, box.toFloat(), pose)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                grabDx = e.rawX - brain.x; grabDy = e.rawY - brain.y
                samples.clear(); sample(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) {
                    dragging = true
                    brain.dragStart()
                    // If she was half off-screen, keep the grab point under the finger once clamped.
                    grabDx = grabDx.coerceIn(0f, box.toFloat()); grabDy = grabDy.coerceIn(0f, box.toFloat())
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    gestures.onTouchEvent(cancel); cancel.recycle()
                }
                if (dragging) { brain.dragTo(e.rawX - grabDx, e.rawY - grabDy); sample(e) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    sample(e)
                    val (vx, vy) = velocity()
                    host.onDragFinished(brain.dragEnd(vx, vy))
                }
            }
        }
        return true
    }

    private fun sample(e: MotionEvent) {
        samples.addLast(Triple(e.eventTime, e.rawX, e.rawY))
        while (samples.size > 6) samples.removeFirst()
    }

    private fun velocity(): Pair<Float, Float> {
        if (samples.size < 2) return 0f to 0f
        val a = samples.first(); val b = samples.last()
        val dt = (b.first - a.first) / 1000f
        if (dt <= 0.005f) return 0f to 0f
        val vx = (b.second - a.second) / dt; val vy = (b.third - a.third) / dt
        return if (abs(vx) < 50 && abs(vy) < 50) 0f to 0f else vx to vy
    }
}

/** Measured display refresh (from vsync spacing), shown in settings so the owner can verify 120 Hz. */
object FrameStats {
    @Volatile private var avgNs = 0.0
    fun addVsync(ns: Long) { avgNs = if (avgNs == 0.0) ns.toDouble() else avgNs * 0.95 + ns * 0.05 }
    val hz: Int get() = if (avgNs <= 0.0) 0 else (1e9 / avgNs).toInt()
}
