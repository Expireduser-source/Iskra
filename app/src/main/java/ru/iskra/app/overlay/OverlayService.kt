package ru.iskra.app.overlay

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.core.persona.Emotion
import ru.iskra.app.R
import ru.iskra.app.data.AppSettings
import ru.iskra.app.data.AvatarLook
import ru.iskra.app.data.FpsMode
import ru.iskra.app.diag.PowerWatch
import ru.iskra.core.skills.ProactiveFrequency
import ru.iskra.core.skills.ProactivePolicy
import ru.iskra.core.skills.ProactiveState
import ru.iskra.core.skills.ProactiveTrigger
import ru.iskra.app.diag.Diag
import ru.iskra.app.system.DeviceHelper
import ru.iskra.app.ui.MainActivity
import ru.iskra.app.ui.QuickChatActivity
import ru.iskra.app.voice.MicArbiter
import ru.iskra.app.voice.SpeechModel
import ru.iskra.app.voice.VoiceEar
import ru.iskra.core.avatar.AvatarBrain
import ru.iskra.core.avatar.AvatarPose
import ru.iskra.core.persona.MoodEvent
import kotlin.math.floor

/**
 * Keeps the character on screen over other apps, and (if the owner enabled it) listens for her
 * name. A foreground service is required so Android/HyperOS do not kill it; the notification is
 * the visible "she's here / she's listening", with buttons to stop either.
 */
class OverlayService : Service(), AvatarView.Host, VoiceEar.Listener {
    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private var avatar: AvatarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { bubble?.visibility = View.GONE }
    private var sizePx = 0
    private var screenW = 0
    private var screenH = 0
    private var boxX = 0f
    private var boxY = 0f
    private var windowHidden = false

    private var ear: VoiceEar? = null
    private var micInForeground = false
    private var earProblem: String? = null
    private var screenOn = true
    private var screenOnSince: Long? = null
    private var unlockedSince: Long? = null
    private val proactiveCheck = Runnable { checkProactive() }
    private val proactiveTick = object : Runnable {
        override fun run() { checkProactive(); handler.postDelayed(this, 3 * 60_000L) }
    }
    private var settings = AppSettings()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false; screenOnSince = null; unlockedSince = null
                    handler.removeCallbacks(proactiveCheck)
                    avatar?.stop(); PowerWatch.sample("экран выключен")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; screenOnSince = System.currentTimeMillis()
                    unlockedSince = screenOnSince.takeUnless { isScreenLocked() }
                    avatar?.start(); PowerWatch.sample("экран включён")
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true; unlockedSince = System.currentTimeMillis(); avatar?.start()
                    // Morning greeting / "welcome back" a moment after unlocking, not in the middle of it.
                    handler.removeCallbacks(proactiveCheck); handler.postDelayed(proactiveCheck, 15_000)
                }
            }
            updateEarPause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        screenOnSince = System.currentTimeMillis().takeIf { screenOn }
        unlockedSince = screenOnSince.takeUnless { isScreenLocked() }
        Diag.i("overlay", "Служба создана")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Diag.i("overlay", "Убрана с экрана кнопкой в уведомлении")
                IskraApp.instance.settings.let { it.save(it.load().copy(overlayEnabled = false)) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_EAR -> {
                val repo = IskraApp.instance.settings
                repo.save(repo.load().copy(alwaysListen = !repo.load().alwaysListen))
            }
        }
        settings = IskraApp.instance.settings.load()
        startInForeground()
        if (!DeviceHelper.canDrawOverlays(this)) {
            Diag.w("overlay", "Нет разрешения «поверх приложений» — служба остановлена")
            Diag.overlayState = "остановлен: нет разрешения поверх приложений"
            stopSelf()
            return START_NOT_STICKY
        }
        if (avatar == null) showAvatar()
        syncEar()
        return START_STICKY
    }

    // ------------------------------------------------------------------ foreground + notification

    private fun wantsEar() = settings.alwaysListen && hasMic() && SpeechModel.isReady(this)
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_MIN))
        }
        // The microphone type must be declared to hear in the background. Android only allows it
        // while the app is visible (it is started from the app), so fall back gracefully.
        micInForeground = false
        if (wantsEar() && Build.VERSION.SDK_INT >= 30) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listening = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micInForeground = true
                earProblem = null
                return
            } catch (e: Exception) {
                earProblem = "Открой приложение Искры, чтобы включить слух (Android разрешает микрофон только так)."
                Diag.w("ear", "Android не дал микрофон в фоне (служба запущена не из открытого приложения)", e)
            }
        } else if (wantsEar()) micInForeground = true // before Android 11 no type is needed
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(listening = micInForeground), type)
    }

    private fun notification(listening: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_EAR), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            listening -> "🎤 Слушаю, на устройстве. Скажи «${settings.wakeNameList.firstOrNull() ?: "Искра"}, …»"
            earProblem != null && settings.alwaysListen -> earProblem!!
            else -> "Нажми на неё, чтобы поговорить"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Искра на экране")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Убрать с экрана", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        if (hasMic() && SpeechModel.isReady(this)) b.addAction(0, if (settings.alwaysListen) "Не слушать" else "Слушать", toggle)
        return b.build()
    }

    // ------------------------------------------------------------------ always-on listening

    private fun syncEar() {
        val want = wantsEar() && micInForeground
        if (want && ear == null) {
            ear = VoiceEar(this, settings.wakeNameList, settings.followUp, this, economical = settings.economicalEar).also { it.start() }
            if (ear?.isRunning == true) Diag.i("ear", "Слух включён")
        } else if (!want && ear != null) {
            ear?.stop(); ear = null
            Diag.i("ear", "Слух выключен")
            CompanionBus.emit(AvatarCue.Listening(false))
        }
        updateEarPause()
    }

    private fun updateEarPause() {
        val e = ear ?: return updateEarState()
        val app = IskraApp.instance
        val speaking = app.speaker.speaking.value
        val micBusy = MicArbiter.busy.value
        val screenOff = !screenOn && !settings.listenScreenOff
        e.setPaused(speaking || micBusy || screenOff)
        updateEarState(when {
            speaking -> "пауза: Искра говорит"
            micBusy -> "пауза: микрофон занят чатом"
            screenOff -> "пауза: экран выключен"
            else -> "слушает"
        })
    }

    private fun updateEarState(state: String? = null) {
        Diag.earState = when {
            ear?.isRunning == true -> state ?: "слушает"
            settings.alwaysListen && earProblem != null -> "не работает: $earProblem"
            settings.alwaysListen && !hasMic() -> "не работает: нет доступа к микрофону"
            settings.alwaysListen && !SpeechModel.isReady(this) -> "не работает: модель не скачана"
            settings.alwaysListen -> "не запущен"
            else -> "выключен в настройках"
        }
    }

    override fun onPerk() {
        CompanionBus.emit(AvatarCue.Listening(true))
        if (IskraApp.instance.speaker.speaking.value) IskraApp.instance.chat.stopSpeaking()
    }

    override fun onCommand(text: String) {
        CompanionBus.emit(AvatarCue.Listening(false))
        // Only the length — what the owner said never goes into the journal.
        Diag.i("ear", "Голосовая команда распознана (${text.length} симв.)")
        val chat = IskraApp.instance.chat
        if (chat.busy.value) { say("Подожди, я ещё думаю над прошлым…"); return }
        say("🎤 «$text»")
        chat.send(text)
    }

    override fun onStopRequested() {
        CompanionBus.emit(AvatarCue.Listening(false))
        IskraApp.instance.chat.cancel()
    }

    override fun onAttentionLost() = CompanionBus.emit(AvatarCue.Listening(false))

    override fun onEarError(message: String) {
        earProblem = message
        ear = null
        Diag.e("ear", message)
        updateEarState()
        say(message)
        CompanionBus.emit(AvatarCue.Listening(false))
    }

    // ------------------------------------------------------------------ window

    private fun readScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }

    /** Pick the display mode for the chosen smoothness (same resolution, highest / 60 Hz refresh). */
    private fun preferredMode(): Display.Mode? {
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val cur = display.mode
        val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        return when (settings.fpsMode) {
            FpsMode.AUTO -> same.maxByOrNull { it.refreshRate }
            else -> same.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        }
    }

    /** Name of the look actually drawn (after a fallback it differs from the setting). */
    private var lookName = AvatarLook.CLASSIC.name

    /** The chosen look; if its pictures cannot be loaded, the next simpler one, with a journal entry. */
    private fun createRenderer(): AvatarRenderer {
        val order = when (settings.avatarLook) {
            AvatarLook.RIG -> listOf(AvatarLook.RIG, AvatarLook.SPRITES)
            AvatarLook.SPRITES -> listOf(AvatarLook.SPRITES)
            AvatarLook.CLASSIC -> emptyList()
        }
        for (look in order) {
            try {
                val r = when (look) {
                    AvatarLook.RIG -> RigRenderer(this, sizePx)
                    else -> SpriteRenderer(this, sizePx)
                }
                lookName = look.name
                return r
            } catch (e: Throwable) {
                Diag.e("avatar", "Вид ${look.name} не загрузился, беру попроще", e)
            }
        }
        lookName = AvatarLook.CLASSIC.name
        return CatgirlRenderer()
    }

    private fun showAvatar() {
        sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, settings.avatarSizeDp.toFloat(), resources.displayMetrics).toInt()
        readScreen()
        val brain = AvatarBrain(screenW.toFloat(), (screenH - navBarGuess()).toFloat(), sizePx.toFloat(),
            startX = (screenW - sizePx).toFloat(), startY = (screenH - navBarGuess() - sizePx).toFloat())
        brain.wanderEnabled = settings.wander
        brain.liveliness = settings.liveliness

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = brain.x.toInt(); y = brain.y.toInt()
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            preferredMode()?.let { preferredDisplayModeId = it.modeId; preferredRefreshRate = it.refreshRate }
        }
        val view = AvatarView(this, brain, sizePx, this, createRenderer()).apply {
            // Standing still: 24–30 fps is enough for breathing and blinking and saves battery.
            when (settings.fpsMode) {
                FpsMode.AUTO -> { movingFps = 0; idleFps = 30 }
                FpsMode.HZ60 -> { movingFps = 60; idleFps = 30 }
                FpsMode.ECO -> { movingFps = 30; idleFps = 24 }
            }
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            // Permission revoked between the check and now, or HyperOS blocked the window.
            Diag.e("overlay", "Окно Искры не добавлено", e)
            Diag.overlayState = "ошибка: окно не добавлено"
            stopSelf(); return
        }
        Diag.overlayState = "на экране"
        Diag.i("overlay", "Искра на экране, ${sizePx}px, вид ${lookName}, режим кадров ${settings.fpsMode.name}")
        PowerWatch.start(this) { lookName }
        avatar = view; params = lp
        view.start()
        createBubble()
        handler.postDelayed(proactiveTick, 60_000)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val app = IskraApp.instance
        scope.launch {
            CompanionBus.cues.collect { cue ->
                val b = avatar?.brain ?: return@collect
                when (cue) {
                    is AvatarCue.Feel -> b.setEmotion(cue.emotion)
                    is AvatarCue.Talk -> b.talk(cue.text, cue.lipSync)
                    is AvatarCue.Mouth -> b.setMouthLevel(cue.level)
                    AvatarCue.StopTalking -> b.stopTalking()
                    is AvatarCue.Thinking -> b.setThinking(cue.on)
                    is AvatarCue.Listening -> b.setListening(cue.on)
                    is AvatarCue.Say -> say(cue.text)
                }
            }
        }
        // Pause the ear while she talks or another recogniser has the mic; re-arm follow-up after a reply.
        scope.launch {
            combine(app.speaker.speaking, MicArbiter.busy) { a, b -> a to b }.collect { updateEarPause() }
        }
        scope.launch {
            app.speaker.speaking.drop(1).collect { speaking ->
                if (!speaking) handler.postDelayed({ ear?.replyFinished() }, 250)
            }
        }
        scope.launch { app.chat.replies.collect { spoken -> if (!spoken) ear?.replyFinished() } }
        // A dangerous action asked for confirmation, but no chat screen is open (e.g. voice command):
        // open the quick chat so the dialog is visible. Overlay permission allows this from the background.
        scope.launch {
            app.confirm.pending.collect { req ->
                if (req != null && app.chatScreensResumed.get() == 0) openQuickChat()
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
        tv.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val cx = (boxX + sizePx / 2f).coerceIn(0f, screenW.toFloat())
        val nx = (cx - tv.measuredWidth / 2f).toInt().coerceIn(0, (screenW - tv.measuredWidth).coerceAtLeast(0))
        val top = boxY.coerceAtLeast(0f)
        val ny = (top - tv.measuredHeight - 8).toInt().let { if (it < 0) (top + sizePx * 0.9f).toInt() else it }
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx; lp.y = ny
            try { wm.updateViewLayout(tv, lp) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ AvatarView.Host

    /**
     * The window covers only the visible part of her box: at a screen edge she is clipped (peeking),
     * and when fully off-screen the window is hidden and lets touches through.
     */
    override fun onPose(pose: AvatarPose) {
        val v = avatar ?: return
        val lp = params ?: return
        boxX = pose.x; boxY = pose.y
        val left = floor(pose.x).toInt(); val top = floor(pose.y).toInt()
        val x0 = maxOf(0, left); val y0 = maxOf(0, top)
        val x1 = minOf(screenW, left + sizePx); val y1 = minOf(screenH, top + sizePx)
        val hidden = x1 - x0 < 2 || y1 - y0 < 2
        var changed = false
        if (hidden != windowHidden) {
            windowHidden = hidden
            v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            lp.flags = if (hidden) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            changed = true
        }
        if (!hidden) {
            val w = x1 - x0; val h = y1 - y0
            if (lp.x != x0 || lp.y != y0 || lp.width != w || lp.height != h) {
                lp.x = x0; lp.y = y0; lp.width = w; lp.height = h
                changed = true
            }
            v.offsetX = (left - x0).toFloat(); v.offsetY = (top - y0).toFloat()
        }
        if (changed) try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
        if (bubble?.visibility == View.VISIBLE) positionBubble()
    }

    private fun openQuickChat() {
        startActivity(Intent(this, QuickChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isScreenLocked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Stage 3: a line of her own, within [ProactivePolicy]'s limits (frequency, quiet hours, screen on). */
    private fun checkProactive() {
        val s = settings
        if (s.proactive == ProactiveFrequency.OFF || avatar == null || !screenOn) return
        val app = IskraApp.instance
        if (app.speaker.speaking.value || app.chat.busy.value) return
        val prefs = getSharedPreferences("proactive", MODE_PRIVATE)
        val st = ProactiveState(
            prefs.getString("day", "") ?: "", prefs.getInt("count", 0), prefs.getLong("last", 0),
            prefs.getString("morning", "") ?: "", prefs.getString("evening", "") ?: "",
        )
        val policy = ProactivePolicy(s.proactive, s.quietFrom, s.quietTo)
        val now = java.time.LocalDateTime.now()
        val ms = System.currentTimeMillis()
        val trigger = policy.decide(
            now, ms, st, app.lastContactMs, screenOnSince, kotlin.random.Random.Default,
            unlockedSinceMs = unlockedSince, screenLocked = isScreenLocked(),
            listening = MicArbiter.busy.value || ear?.isAttentive == true || avatar?.brain?.isListening == true,
        ) ?: return
        val next = policy.record(trigger, now, ms, st)
        prefs.edit().putString("day", next.day).putInt("count", next.countToday).putLong("last", next.lastAtMs)
            .putString("morning", next.morningDay).putString("evening", next.eveningDay).apply()
        val line = app.phrases.proactive(trigger)
        val emotion = if (trigger == ProactiveTrigger.EVENING) Emotion.SLEEPY else Emotion.HAPPY
        CompanionBus.emit(AvatarCue.Feel(emotion))
        CompanionBus.emit(AvatarCue.Say(line))
        avatar?.brain?.wave()
        val music = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
        val voice = s.proactiveVoice && s.ttsEnabled && !music
        if (voice) app.speaker.speak(line, emotion)
        Diag.i("proactive", "Сама заговорила: ${trigger.name}${if (voice) ", голосом" else ", облачком"} (${next.countToday} за день)")
    }

    override fun onTap() {
        val app = IskraApp.instance
        // Tap while she speaks = "shh".
        if (app.speaker.speaking.value || avatar?.brain?.isTalking == true) { app.chat.stopSpeaking(); return }
        openQuickChat()
    }

    override fun onDoubleTap() = IskraApp.instance.chat.react(MoodEvent.Petted)

    override fun onLongPress() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDragFinished(flung: Boolean) =
        IskraApp.instance.chat.react(if (flung) MoodEvent.Flung else MoodEvent.Dragged)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readScreen()
        avatar?.brain?.resize(screenW.toFloat(), (screenH - navBarGuess()).toFloat())
    }

    override fun onDestroy() {
        Diag.i("overlay", "Служба остановлена")
        PowerWatch.stop()
        if (ear != null) Diag.i("ear", "Слух остановлен вместе со службой")
        if (Diag.overlayState == "на экране") Diag.overlayState = "не запущен"
        ear?.stop(); ear = null
        Diag.earState = if (settings.alwaysListen) "не работает: Искра не на экране" else "выключен в настройках"
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
        private const val ACTION_TOGGLE_EAR = "ru.iskra.app.TOGGLE_EAR"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }
}
