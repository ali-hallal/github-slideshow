package com.starsteps.app.ui

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.starsteps.app.R
import java.util.Calendar

/**
 * The evening reminder: once a day at the time the parents chose, a notification if nothing has
 * been noted yet today for one of the children. Nothing is shown when every child has something.
 */
object Reminder {
    private const val CHANNEL = "daily"
    private const val ID = 1

    /** Sets (or clears) the daily alarm from the family's settings. Safe to call any time. */
    fun schedule(c: Context) {
        val s = Store.get(c).settings
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 0, Intent(c, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!s.reminder) return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.reminderMinutes / 60)
            set(Calendar.MINUTE, s.reminderMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexact: Android groups it with other wake-ups (within a few minutes); no special permission needed.
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    /** The children with nothing noted today (in the language the app is set to). */
    fun quiet(c: Context): List<String> {
        val f = Store.get(c)
        val t = today()
        return f.children.filter { ch -> f.entriesOf(ch.id).none { it.day == t } }.map { it.name }
    }

    fun show(c: Context) {
        val names = quiet(c)
        if (names.isEmpty()) return
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val ctx = localised(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(ctx.getString(R.string.notify_title))
            .setContentText(ctx.getString(R.string.notify_text, names.joinToString(", ")))
            .setStyle(android.app.Notification.BigTextStyle().bigText(ctx.getString(R.string.notify_text, names.joinToString(", ")) + "\n" + ctx.getString(R.string.notify_text_all)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
    }

    /** The app's chosen language, outside a screen. */
    private fun localised(c: Context): Context {
        val lang = c.getSharedPreferences("starsteps", Context.MODE_PRIVATE).getString("language", "") ?: ""
        if (lang.isEmpty()) return c
        val conf = android.content.res.Configuration(c.resources.configuration).apply { setLocale(java.util.Locale(lang)) }
        return c.createConfigurationContext(conf)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) = Reminder.show(c)
}

/** After the phone restarts, the daily reminder is set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminder.schedule(c)
    }
}
