package com.dandooger.tzaad

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

enum class HabitType { CHECK, COUNT, QUIT }

data class Habit(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String,
    val type: HabitType,
    val target: Int = 1,
    // java.time DayOfWeek values (Mon=1 … Sun=7). Saturday is always a rest day.
    val days: Set<Int> = setOf(7, 1, 2, 3, 4, 5),
    // Minutes after midnight, or null for no reminder.
    val reminder: Int? = null,
    val created: String = LocalDate.now().toString(),
    // 0 = on the chosen days. 1–6 = that many times a week, any day (then `days` is unused).
    val perWeek: Int = 0,
)

/** All data lives on the phone, in SharedPreferences as JSON. */
object Store {
    /** Bumped on every change so the screens redraw. */
    val version = mutableStateOf(0)

    val habits = mutableListOf<Habit>()
    // date -> (habit id -> count). For QUIT habits, 1 means "slipped".
    private val log = mutableMapOf<String, MutableMap<String, Int>>()
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tzaad", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        val arr = JSONArray(p.getString("habits", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val days = o.getJSONArray("days")
            habits += Habit(
                id = o.getString("id"),
                name = o.getString("name"),
                emoji = o.getString("emoji"),
                type = HabitType.valueOf(o.getString("type")),
                target = o.getInt("target"),
                days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                reminder = if (o.has("reminder")) o.getInt("reminder") else null,
                created = o.getString("created"),
                perWeek = o.optInt("perWeek", 0),
            )
        }
        val lo = JSONObject(p.getString("log", "{}"))
        for (date in lo.keys()) {
            val d = lo.getJSONObject(date)
            log[date] = d.keys().asSequence().associateWith { d.getInt(it) }.toMutableMap()
        }
        loaded = true
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        for (h in habits) {
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("name", h.name)
                put("emoji", h.emoji)
                put("type", h.type.name)
                put("target", h.target)
                put("days", JSONArray(h.days.toList()))
                if (h.reminder != null) put("reminder", h.reminder)
                put("created", h.created)
                put("perWeek", h.perWeek)
            })
        }
        val lo = JSONObject()
        for ((date, m) in log) lo.put(date, JSONObject(m as Map<*, *>))
        prefs(ctx).edit().putString("habits", arr.toString()).putString("log", lo.toString()).apply()
    }

    private fun changed(ctx: Context) {
        save(ctx)
        version.value++
        TzaadWidget.refresh(ctx)
        Reminders.scheduleAll(ctx)
    }

    fun habit(id: String) = habits.find { it.id == id }

    fun count(h: Habit, date: LocalDate) = log[date.toString()]?.get(h.id) ?: 0

    fun setCount(ctx: Context, h: Habit, date: LocalDate, value: Int) {
        val key = date.toString()
        val m = log.getOrPut(key) { mutableMapOf() }
        if (value <= 0) m.remove(h.id) else m[h.id] = value
        if (m.isEmpty()) log.remove(key)
        changed(ctx)
    }

    /** One tap: toggle a V, or add one to a counted habit. */
    fun tap(ctx: Context, h: Habit, date: LocalDate) {
        val c = count(h, date)
        when (h.type) {
            HabitType.CHECK, HabitType.QUIT -> setCount(ctx, h, date, if (c > 0) 0 else 1)
            HabitType.COUNT -> if (c < h.target) setCount(ctx, h, date, c + 1)
        }
    }

    /** Add (or, with a negative amount, remove) several at once – from the number wheel. */
    fun add(ctx: Context, h: Habit, date: LocalDate, amount: Int) {
        prefs(ctx).edit().putInt("last_${h.id}", kotlin.math.abs(amount)).apply()
        setCount(ctx, h, date, (count(h, date) + amount).coerceIn(0, 9999))
    }

    /** The amount last added with the wheel, so the wheel starts there next time. */
    fun lastAdd(ctx: Context, h: Habit) = prefs(ctx).getInt("last_${h.id}", 1)

    fun saveHabit(ctx: Context, h: Habit) {
        val i = habits.indexOfFirst { it.id == h.id }
        if (i >= 0) habits[i] = h else habits += h
        changed(ctx)
    }

    /** New order from the drag screen – the app list and the widget follow it. */
    fun reorder(ctx: Context, ids: List<String>) {
        val byId = habits.associateBy { it.id }
        val sorted = ids.mapNotNull { byId[it] }
        habits.clear()
        habits += sorted
        changed(ctx)
    }

    fun deleteHabit(ctx: Context, id: String) {
        habits.removeAll { it.id == id }
        log.values.forEach { it.remove(id) }
        log.values.removeAll { it.isEmpty() }
        changed(ctx)
    }
}

fun Habit.createdDate(): LocalDate = LocalDate.parse(created)

/** Big counted habits use the number wheel; small ones (like 8 cups) stay one tap = +1. */
fun Habit.usesWheel() = type == HabitType.COUNT && target > 10

val Habit.isWeekly get() = perWeek > 0 && type != HabitType.QUIT

/** A day the habit can be done on at all: from its start, never Shabbat or a holiday. */
fun Habit.available(date: LocalDate) = !date.isBefore(createdDate()) && !JewishDays.isRestDay(date)

/** Is this habit due on that day? Weekly habits are never due on a specific day. */
fun Habit.required(date: LocalDate) = !isWeekly && available(date) && date.dayOfWeek.value in days

// ── Weekly habits: the week runs Sunday–Shabbat ──

fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value % 7).toLong())

/** How many times it was done in the week starting on [start]. */
fun Habit.weekCount(start: LocalDate) = (0L..6L).count { done(start.plusDays(it)) }

/** The goal for that week – smaller if a holiday leaves fewer days. */
fun Habit.weekGoal(start: LocalDate) = minOf(perWeek, (0L..6L).count { available(start.plusDays(it)) })

fun Habit.weekComplete(date: LocalDate) = weekCount(weekStart(date)) >= weekGoal(weekStart(date))

/** Show it on today's list (and in the widget)? */
fun Habit.activeOn(date: LocalDate) =
    if (isWeekly) available(date) && (!weekComplete(date) || done(date)) else required(date)

fun Habit.done(date: LocalDate): Boolean {
    val c = Store.count(this, date)
    return when (type) {
        HabitType.CHECK -> c >= 1
        HabitType.COUNT -> c >= target
        HabitType.QUIT -> c == 0
    }
}

/**
 * Days in a row. Days the habit isn't required (Shabbat, holidays, days not picked)
 * are skipped, so they never break the streak. Today only counts once it's done.
 */
fun Habit.streak(today: LocalDate = LocalDate.now()): Int {
    if (isWeekly) return weekStreak(today)
    var s = 0
    var d = today
    val start = createdDate()
    while (!d.isBefore(start)) {
        if (required(d)) {
            if (done(d)) s++
            else if (d != today || type == HabitType.QUIT) return s
        }
        d = d.minusDays(1)
    }
    return s
}

/** Weeks in a row the goal was reached. This week only counts once it's reached. */
private fun Habit.weekStreak(today: LocalDate): Int {
    var s = 0
    val thisWeek = weekStart(today)
    var w = thisWeek
    val first = weekStart(createdDate())
    while (!w.isBefore(first)) {
        val goal = weekGoal(w)
        if (goal > 0) {
            if (weekCount(w) >= goal) s++
            else if (w != thisWeek) return s
        }
        w = w.minusWeeks(1)
    }
    return s
}

private fun Habit.bestWeekStreak(today: LocalDate): Int {
    var best = 0
    var run = 0
    val thisWeek = weekStart(today)
    var w = weekStart(createdDate())
    while (!w.isAfter(thisWeek)) {
        val goal = weekGoal(w)
        if (goal > 0) {
            if (weekCount(w) >= goal) {
                run++
                if (run > best) best = run
            } else if (w != thisWeek) run = 0
        }
        w = w.plusWeeks(1)
    }
    return best
}

fun Habit.bestStreak(today: LocalDate = LocalDate.now()): Int {
    if (isWeekly) return bestWeekStreak(today)
    var best = 0
    var run = 0
    var d = createdDate()
    while (!d.isAfter(today)) {
        if (required(d)) {
            if (done(d)) {
                run++
                if (run > best) best = run
            } else if (d != today || type == HabitType.QUIT) run = 0
        }
        d = d.plusDays(1)
    }
    return best
}

fun Habit.totalDone(today: LocalDate = LocalDate.now()): Int {
    var n = 0
    var d = createdDate()
    while (!d.isAfter(today)) {
        if (if (type == HabitType.QUIT) required(d) && done(d) else done(d)) n++
        d = d.plusDays(1)
    }
    return n
}
