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
import android.widget.RemoteViewsService
import java.time.LocalDate

/** Home-screen widget: a scrollable list of today's habits, one tap to mark. */
class TzaadWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
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

        /** What the widget lists. "Quit" habits stay out so a stray tap can't mark a slip. */
        fun widgetHabits(today: LocalDate): List<Habit> =
            Store.habits.toList().filter { it.type != HabitType.QUIT && it.activeOn(today) }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TzaadWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        /** Used by reminder notifications ("✓ עשיתי" / "+1"). */
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
            val list = widgetHabits(today)

            v.setOnClickPendingIntent(R.id.header, openApp(ctx))
            // Today's score counts the daily habits; weekly ones have their own weekly goal.
            val daily = list.filter { !it.isWeekly }
            val doneCount = daily.count { it.done(today) }
            v.setTextViewText(
                R.id.progress,
                when {
                    daily.isEmpty() -> ""
                    doneCount == daily.size -> "הכול בוצע ✓"
                    else -> "$doneCount מתוך ${daily.size}"
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

            v.setViewVisibility(R.id.list, if (msg == null) View.VISIBLE else View.GONE)
            v.setRemoteAdapter(
                R.id.list,
                Intent(ctx, WidgetListService::class.java).setData(Uri.parse("tzaad://widget-list")),
            )
            // A list row can only open one kind of thing, so every tap goes to AddActivity:
            // it marks small habits instantly (no window) and shows the wheel for big ones.
            val template = Intent(ctx, AddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            v.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 2, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                ),
            )
            return v
        }
    }
}

class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var items = listOf<Habit>()
        private var today = LocalDate.now()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            Store.load(ctx)
            today = LocalDate.now()
            items = try {
                if (JewishDays.isRestDay(today)) emptyList() else TzaadWidget.widgetHabits(today)
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun getCount() = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_row)
            val h = items.getOrNull(position) ?: return v
            val c = Store.count(h, today)
            val done = h.done(today)
            val week = weekStart(today)
            v.setTextViewText(R.id.name, "${h.emoji} ${h.name}")
            v.setTextViewText(
                R.id.prog,
                when {
                    h.type == HabitType.COUNT -> "$c/${h.target}"
                    h.isWeekly -> "השבוע ${h.weekCount(week)}/${h.weekGoal(week)}"
                    else -> ""
                },
            )
            v.setTextViewText(R.id.btn, if (done) "✓" else if (h.type == HabitType.COUNT) "+" else "")
            v.setTextColor(R.id.btn, if (done) BLUE else WHITE)
            v.setInt(R.id.btn, "setBackgroundResource", if (done) R.drawable.btn_done else R.drawable.btn_todo)
            v.setOnClickFillInIntent(
                R.id.row,
                Intent().setData(Uri.parse("tzaad://row/${h.id}")).putExtra("habit", h.id).putExtra("fromWidget", true),
            )
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true

        companion object {
            private const val BLUE = 0xFF1E6FD9.toInt()
            private const val WHITE = 0xFFFFFFFF.toInt()
        }
    }
}
