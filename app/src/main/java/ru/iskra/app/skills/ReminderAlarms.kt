package ru.iskra.app.skills

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.iskra.app.AvatarCue
import ru.iskra.app.CompanionBus
import ru.iskra.app.IskraApp
import ru.iskra.app.R
import ru.iskra.app.diag.Diag
import ru.iskra.app.ui.MainActivity
import ru.iskra.core.persona.Emotion
import ru.iskra.core.skills.Reminder
import ru.iskra.core.skills.ReminderScheduler

/**
 * Reminders through AlarmManager: exact when Android allows it («Будильники и напоминания»),
 * otherwise inexact (Android may deliver a few minutes late in deep sleep). Alarms do not survive a
 * reboot, so [ReminderBootReceiver] arms the pending ones again; missed ones are delivered at once.
 */
class AndroidReminderScheduler(private val ctx: Context) : ReminderScheduler {
    private val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override val exact: Boolean get() = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    override fun schedule(r: Reminder) {
        val pi = intent(r.id)
        val at = maxOf(r.atMs, System.currentTimeMillis() + 1000)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    override fun cancel(id: Long) = am.cancel(intent(id))

    private fun intent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, (id % Int.MAX_VALUE).toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** After boot / update: arm every pending reminder again. Returns how many were missed. */
    fun rescheduleAll(): Int {
        val app = IskraApp.instance
        var missed = 0
        val now = System.currentTimeMillis()
        for (r in app.reminders.pending()) {
            if (r.atMs < now - 60_000) missed++
            schedule(r)
        }
        return missed
    }

    companion object {
        const val ACTION = "ru.iskra.app.REMINDER"
        const val EXTRA_ID = "id"
        const val CHANNEL = "reminders"

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Напоминания, которые ты попросил поставить"
                })
            }
        }
    }
}

/** The alarm went off: notification, and — if Iskra is on the screen — she says it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AndroidReminderScheduler.EXTRA_ID, -1)
        val app = IskraApp.instance
        val r = app.reminders.get(id) ?: return
        if (r.done) return
        app.reminders.markDone(id)
        deliver(context, r)
    }

    companion object {
        fun deliver(context: Context, r: Reminder) {
            val app = IskraApp.instance
            val line = app.phrases.reminder(r.text)
            AndroidReminderScheduler.ensureChannel(context)
            val canNotify = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (canNotify) {
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(context, AndroidReminderScheduler.CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Искра напоминает")
                    .setContentText(r.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFY_BASE + (r.id % 10_000).toInt(), n) }
            } else Diag.w("remind", "Напоминание сработало, но уведомления запрещены — показано только на экране")
            Diag.i("remind", "Напоминание сработало (поставлено ${(System.currentTimeMillis() - r.createdAtMs) / 60_000} мин назад)")
            if (ru.iskra.app.diag.Diag.overlayState == "на экране") {
                CompanionBus.emit(AvatarCue.Feel(Emotion.EXCITED))
                CompanionBus.emit(AvatarCue.Say(line))
                if (app.settings.load().ttsEnabled) app.speaker.speak(line, Emotion.EXCITED)
            }
        }

        private const val NOTIFY_BASE = 5000
    }
}

/** Boot and app update: alarms are gone, arm them again. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val missed = runCatching { IskraApp.instance.reminderScheduler.rescheduleAll() }.getOrElse { Diag.e("remind", "Не удалось восстановить напоминания", it); return }
        val pending = IskraApp.instance.reminders.pending().size
        Diag.i("remind", "После ${if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "обновления" else "перезагрузки"}: напоминаний $pending, пропущено $missed (придут сейчас)")
    }
}
