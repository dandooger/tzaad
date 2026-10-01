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

    fun saveHabit(ctx: Context, h: Habit) {
        val i = habits.indexOfFirst { it.id == h.id }
        if (i >= 0) habits[i] = h else habits += h
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

/** Does this habit count on that day? Never on Shabbat or a holiday. */
fun Habit.required(date: LocalDate) =
    !date.isBefore(createdDate()) && !JewishDays.isRestDay(date) && date.dayOfWeek.value in days

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

fun Habit.bestStreak(today: LocalDate = LocalDate.now()): Int {
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
