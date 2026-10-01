package com.dandooger.tzaad

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

object Reminders {
    const val ACTION_REMIND = "com.dandooger.tzaad.REMIND"
    const val ACTION_MIDNIGHT = "com.dandooger.tzaad.MIDNIGHT"
    private const val CHANNEL = "reminders"
    // On Friday / erev chag, reminders come no later than 13:00 so nothing rings on Shabbat.
    private const val EREV_LATEST = 13 * 60

    private fun reminderIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("tzaad://remind/$id"))
            .putExtra("habit", id)
        return PendingIntent.getBroadcast(
            ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun midnightIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_MIDNIGHT)
        return PendingIntent.getBroadcast(
            ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next reminder time – never on Shabbat or a holiday. */
    private fun nextTime(h: Habit): Long? {
        val rem = h.reminder ?: return null
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var d = LocalDate.now()
        repeat(30) {
            if (h.required(d)) {
                val erev = d.dayOfWeek == DayOfWeek.FRIDAY || JewishDays.isErev(d)
                val minutes = if (erev) minOf(rem, EREV_LATEST) else rem
                val t = d.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                if (t > now + 30_000) return t
            }
            d = d.plusDays(1)
        }
        return null
    }

    fun scheduleAll(ctx: Context) {
        Store.load(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (h in Store.habits) {
            val pi = reminderIntent(ctx, h.id)
            am.cancel(pi)
            val t = nextTime(h) ?: continue
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
        // Wake up just after midnight so the widget shows the new day.
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() + 60_000
        am.setAndAllowWhileIdle(AlarmManager.RTC, midnight, midnightIntent(ctx))
    }

    fun show(ctx: Context, h: Habit) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "תזכורות", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val today = LocalDate.now()
        val notifId = h.id.hashCode().let { if (it == 0) 1 else it }
        val text = when (h.type) {
            HabitType.CHECK -> "עוד לא סימנת היום"
            HabitType.COUNT -> "${Store.count(h, today)} מתוך ${h.target} – אל תשכח!"
            HabitType.QUIT -> "מחזיק מעמד? 💪 המשך כך"
        }
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${h.emoji} ${h.name}")
            .setContentText(text)
            .setContentIntent(TzaadWidget.openApp(ctx))
            .setAutoCancel(true)
        if (h.type != HabitType.QUIT) {
            val label = when {
                h.usesWheel() -> "הוסף"
                h.type == HabitType.COUNT -> "+1"
                else -> "✓ עשיתי"
            }
            val action = if (h.usesWheel()) AddActivity.pending(ctx, h.id, notifId)
            else TzaadWidget.tapIntent(ctx, h.id, notifId)
            b.addAction(Notification.Action.Builder(null as Icon?, label, action).build())
        }
        nm.notify(notifId, b.build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.load(ctx)
        when (intent.action) {
            Reminders.ACTION_REMIND -> {
                val h = Store.habit(intent.getStringExtra("habit") ?: return)
                val today = LocalDate.now()
                if (h != null && h.required(today) && (h.type == HabitType.QUIT || !h.done(today))) {
                    Reminders.show(ctx, h)
                }
                Reminders.scheduleAll(ctx)
            }
            Reminders.ACTION_MIDNIGHT -> {
                Store.version.value++
                TzaadWidget.refresh(ctx)
                Reminders.scheduleAll(ctx)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                TzaadWidget.refresh(ctx)
                Reminders.scheduleAll(ctx)
            }
        }
    }
}
