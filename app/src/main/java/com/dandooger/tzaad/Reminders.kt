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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Reminders {
    const val ACTION_REMIND = "com.dandooger.tzaad.REMIND"
    const val ACTION_MIDNIGHT = "com.dandooger.tzaad.MIDNIGHT"
    private const val CHANNEL = "reminders"
    // On erev Shabbat/chag, a reminder comes at the latest an hour before candle lighting.
    private const val EREV_GAP_MINUTES = 60L

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

    private fun shabbatIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ACTION_MIDNIGHT)
            .setData(Uri.parse("tzaad://shabbat"))
        return PendingIntent.getBroadcast(
            ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Next reminder time – never during Shabbat or chag. */
    private fun nextTime(h: Habit): Long? {
        val rem = h.reminder ?: return null
        val now = Instant.now()
        var d = LocalDate.now(JewishDays.ZONE)
        repeat(30) {
            val due = if (h.isWeekly) h.available(d) && !h.weekComplete(d) else h.scheduled(d)
            if (due) {
                var t = d.atStartOfDay(JewishDays.ZONE).plusMinutes(rem.toLong()).toInstant()
                JewishDays.candleToday(d)?.let { candle ->
                    val latest = candle.minusSeconds(EREV_GAP_MINUTES * 60)
                    if (t.isAfter(latest)) t = latest
                }
                if (t.isAfter(now.plusSeconds(30)) && JewishDays.currentRest(t) == null) return t.toEpochMilli()
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
        // Close the widget when Shabbat/chag comes in, and open it again when it goes out.
        JewishDays.nextChange()?.let {
            am.setAndAllowWhileIdle(AlarmManager.RTC, it.toEpochMilli() + 20_000, shabbatIntent(ctx))
        }
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
            HabitType.ONCE -> "עוד לא סימנת שסיימת"
            HabitType.SUCCEED -> "הצלחת היום? 🏆"
            HabitType.HOMEWORK -> h.note.ifBlank { "יש לך שיעורי בית" }
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
                h.isTask -> "✓ סיימתי"
                h.type == HabitType.SUCCEED -> "🏆 הצלחתי"
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
                if (h != null && !JewishDays.restNow() && h.activeOn(today) && (h.type == HabitType.QUIT || !h.done(today))) {
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
