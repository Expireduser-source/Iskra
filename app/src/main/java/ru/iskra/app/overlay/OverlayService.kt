package ru.iskra.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.R
import ru.iskra.app.system.DeviceHelper
import ru.iskra.app.ui.MainActivity
import ru.iskra.app.ui.QuickChatActivity
import ru.iskra.core.avatar.AvatarBrain
import ru.iskra.core.persona.MoodEvent

/**
 * Keeps the character on screen over other apps. A foreground service is required so Android
 * (and HyperOS battery management) does not kill it; the notification is the visible "she's here".
 */
class OverlayService : Service(), AvatarView.Host {
    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private var avatar: AvatarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { bubble?.visibility = View.GONE }
    private var sizePx = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> avatar?.stop()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> avatar?.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            IskraApp.instance.settings.let { it.save(it.load().copy(overlayEnabled = false)) }
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!DeviceHelper.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (avatar == null) showAvatar()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Искра на экране")
            .setContentText("Нажми на неё, чтобы поговорить")
            .setContentIntent(open)
            .addAction(0, "Убрать с экрана", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun showAvatar() {
        val settings = IskraApp.instance.settings.load()
        sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, settings.avatarSizeDp.toFloat(), resources.displayMetrics).toInt()
        val (w, h) = screenSize()
        val brain = AvatarBrain(w.toFloat(), (h - navBarGuess()).toFloat(), sizePx.toFloat(),
            startX = (w - sizePx).toFloat(), startY = h * 0.55f)
        brain.wanderEnabled = settings.wander

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = brain.x.toInt(); y = brain.y.toInt() }
        val view = AvatarView(this, brain, this)
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or HyperOS blocked the window.
            stopSelf(); return
        }
        avatar = view; params = lp
        view.start()
        createBubble()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch {
            CompanionBus.cues.collect { cue ->
                val b = avatar?.brain ?: return@collect
                when (cue) {
                    is AvatarCue.Feel -> b.setEmotion(cue.emotion)
                    is AvatarCue.Talk -> b.talk(cue.text)
                    AvatarCue.StopTalking -> b.stopTalking()
                    is AvatarCue.Thinking -> b.setThinking(cue.on)
                    is AvatarCue.Listening -> b.setListening(cue.on)
                    is AvatarCue.Say -> say(cue.text)
                }
            }
        }
    }

    /** Rough nav-bar allowance so she stands above the gesture/nav bar. */
    private fun navBarGuess(): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()

    private fun createBubble() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply { cornerRadius = 18 * resources.displayMetrics.density; setColor(Color.parseColor("#E62A1F3A")) }
            visibility = View.GONE
            maxLines = 4
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(tv, lp); bubble = tv; bubbleParams = lp } catch (_: Exception) { }
    }

    private fun say(text: String) {
        val tv = bubble ?: return
        if (text.isBlank()) return
        tv.text = if (text.length > 160) text.take(157) + "…" else text
        tv.visibility = View.VISIBLE
        positionBubble()
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, (2500L + text.length * 45L).coerceAtMost(9000L))
    }

    private fun positionBubble() {
        val tv = bubble ?: return
        val lp = bubbleParams ?: return
        val p = params ?: return
        val (w, _) = screenSize()
        tv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        lp.x = (p.x + sizePx / 2 - tv.measuredWidth / 2).coerceIn(0, (w - tv.measuredWidth).coerceAtLeast(0))
        lp.y = (p.y - tv.measuredHeight - 8).coerceAtLeast(0)
        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) { }
    }

    // --- AvatarView.Host ---
    override fun onMoved(x: Int, y: Int) {
        val v = avatar ?: return
        val lp = params ?: return
        lp.x = x; lp.y = y
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
        if (bubble?.visibility == View.VISIBLE) positionBubble()
    }

    override fun onTap() {
        startActivity(Intent(this, QuickChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDoubleTap() = IskraApp.instance.chat.react(MoodEvent.Petted)

    override fun onLongPress() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDragFinished(flung: Boolean) =
        IskraApp.instance.chat.react(if (flung) MoodEvent.Flung else MoodEvent.Dragged)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = screenSize()
        avatar?.brain?.resize(w.toFloat(), (h - navBarGuess()).toFloat())
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        avatar?.let { it.stop(); runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        avatar = null; bubble = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "ru.iskra.app.STOP_OVERLAY"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }
}
