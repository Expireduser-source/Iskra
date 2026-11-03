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
 * The character's window content. Runs the [AvatarBrain] simulation on Choreographer frames
 * (≤30 fps, ≤10 fps while sleeping) and translates touches into brain/host events.
 */
@SuppressLint("ViewConstructor")
class AvatarView(context: Context, val brain: AvatarBrain, private val host: Host) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun onMoved(x: Int, y: Int)
        fun onTap()
        fun onDoubleTap()
        fun onLongPress()
        fun onDragFinished(flung: Boolean)
    }

    private val renderer = CatgirlRenderer()
    private var pose: AvatarPose = brain.update(0f)
    private var lastFrameNs = 0L
    private var running = false
    private var lastDrawnX = Int.MIN_VALUE
    private var lastDrawnY = Int.MIN_VALUE

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
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { brain.tapped(); host.onTap(); return true }
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
        val minIntervalNs = if (brain.activity == Activity.SLEEPING) 100_000_000L else 33_000_000L
        if (lastFrameNs == 0L || frameTimeNanos - lastFrameNs >= minIntervalNs) {
            val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1e9f
            lastFrameNs = frameTimeNanos
            pose = brain.update(dt)
            val nx = pose.x.toInt(); val ny = pose.y.toInt()
            if (nx != lastDrawnX || ny != lastDrawnY) {
                lastDrawnX = nx; lastDrawnY = ny
                host.onMoved(nx, ny)
            }
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width.toFloat(), height.toFloat(), pose)
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
                    // Cancel pending long-press / tap detection.
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
