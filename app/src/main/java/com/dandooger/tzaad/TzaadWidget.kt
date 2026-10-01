package com.dandooger.tzaad

import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/** Home-screen widget: today's habits, one tap to mark. */
class TzaadWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
        Reminders.scheduleAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TAP) {
            Store.load(ctx)
            val h = Store.habit(intent.getStringExtra("habit") ?: return) ?: return
            Store.tap(ctx, h, LocalDate.now())
            val notif = intent.getIntExtra("notif", 0)
            if (notif != 0) ctx.getSystemService(NotificationManager::class.java).cancel(notif)
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_TAP = "com.dandooger.tzaad.TAP"
        private const val BLUE = 0xFF1E6FD9.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4)
        private val PROGS = intArrayOf(R.id.prog0, R.id.prog1, R.id.prog2, R.id.prog3, R.id.prog4)
        private val BTNS = intArrayOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4)

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TzaadWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        fun tapIntent(ctx: Context, habitId: String, notif: Int = 0): PendingIntent {
            val i = Intent(ctx, TzaadWidget::class.java)
                .setAction(ACTION_TAP)
                .setData(Uri.parse("tzaad://tap/$habitId/$notif"))
                .putExtra("habit", habitId)
                .putExtra("notif", notif)
            return PendingIntent.getBroadcast(
                ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        private fun build(ctx: Context): RemoteViews {
            Store.load(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val today = LocalDate.now()
            val rest = JewishDays.restName(today)
            // "Quit" habits stay out of the widget so a stray tap can't mark a slip.
            val list = Store.habits.filter { it.type != HabitType.QUIT && it.required(today) }

            v.setOnClickPendingIntent(R.id.header, openApp(ctx))
            val doneCount = list.count { it.done(today) }
            v.setTextViewText(
                R.id.progress,
                when {
                    list.isEmpty() -> ""
                    doneCount == list.size -> "הכול בוצע ✓"
                    else -> "$doneCount מתוך ${list.size}"
                }
            )

            val msg = when {
                rest == "שבת" -> "שבת שלום 🕯️"
                rest != null -> "$rest – חג שמח 🕯️"
                Store.habits.isEmpty() -> "פתח את האפליקציה והוסף הרגל"
                list.isEmpty() -> "אין הרגלים להיום 😎"
                else -> null
            }
            v.setViewVisibility(R.id.message, if (msg != null) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.message, msg ?: "")
            v.setOnClickPendingIntent(R.id.message, openApp(ctx))

            for (i in ROWS.indices) {
                val h = if (msg == null) list.getOrNull(i) else null
                if (h == null) {
                    v.setViewVisibility(ROWS[i], View.GONE)
                    continue
                }
                val c = Store.count(h, today)
                val done = h.done(today)
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(NAMES[i], "${h.emoji} ${h.name}")
                v.setTextViewText(PROGS[i], if (h.type == HabitType.COUNT) "$c/${h.target}" else "")
                v.setTextViewText(BTNS[i], if (done) "✓" else if (h.type == HabitType.COUNT) "+" else "")
                v.setTextColor(BTNS[i], if (done) BLUE else WHITE)
                v.setInt(BTNS[i], "setBackgroundResource", if (done) R.drawable.btn_done else R.drawable.btn_todo)
                v.setOnClickPendingIntent(
                    ROWS[i],
                    if (h.usesWheel()) AddActivity.pending(ctx, h.id) else tapIntent(ctx, h.id),
                )
            }
            return v
        }
    }
}
