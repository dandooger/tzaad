package com.dandooger.tzaad

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

// ONCE and HOMEWORK are one-time tasks: marking them done deletes them.
// SUCCEED is the opposite of QUIT: marked deliberately with "הצלחתי" (with a confirm in the widget).
enum class HabitType { CHECK, COUNT, QUIT, ONCE, HOMEWORK, SUCCEED }

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
    // Homework: what needs to be done ("עד עמוד 25"). Shown in the app, not in the widget.
    val note: String = "",
    // Category id, or "" for none.
    val category: String = "",
)

/** A group of habits, chosen and ordered by Daniel (in the app and the widget). */
data class Category(val id: String = UUID.randomUUID().toString(), val name: String)

private val DEFAULT_CATEGORIES = listOf("📚 שיעורי בית", "💪 אימונים", "🙏 אמונה בה'")

/** All data lives on the phone, in SharedPreferences as JSON. */
object Store {
    /** Bumped on every change so the screens redraw. */
    val version = mutableStateOf(0)

    val habits = mutableListOf<Habit>()
    val categories = mutableListOf<Category>()
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
                note = o.optString("note", ""),
                category = o.optString("category", ""),
            )
        }
        if (p.contains("categories")) {
            val ca = JSONArray(p.getString("categories", "[]"))
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                categories += Category(o.getString("id"), o.getString("name"))
            }
        } else {
            categories += DEFAULT_CATEGORIES.map { Category(name = it) }
        }
        val lo = JSONObject(p.getString("log", "{}"))
        for (date in lo.keys()) {
            val d = lo.getJSONObject(date)
            log[date] = d.keys().asSequence().associateWith { d.getInt(it) }.toMutableMap()
        }
        loaded = true
        if (!p.contains("categories")) save(ctx)
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
                put("note", h.note)
                put("category", h.category)
            })
        }
        val lo = JSONObject()
        for ((date, m) in log) lo.put(date, JSONObject(m as Map<*, *>))
        val ca = JSONArray()
        for (c in categories) ca.put(JSONObject().put("id", c.id).put("name", c.name))
        prefs(ctx).edit()
            .putString("habits", arr.toString())
            .putString("log", lo.toString())
            .putString("categories", ca.toString())
            .apply()
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
        // Closed on Shabbat and chag – nothing can be marked.
        if (JewishDays.restNow()) return
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
            HabitType.CHECK, HabitType.QUIT, HabitType.SUCCEED -> setCount(ctx, h, date, if (c > 0) 0 else 1)
            HabitType.COUNT -> if (c < h.target) setCount(ctx, h, date, c + 1)
            // One-time task done → it disappears.
            HabitType.ONCE, HabitType.HOMEWORK -> if (!JewishDays.restNow()) deleteHabit(ctx, h.id)
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

    /** Undo for a one-time task that was just marked done: put it back where it was. */
    fun restore(ctx: Context, h: Habit, index: Int) {
        if (habits.any { it.id == h.id }) return
        habits.add(index.coerceIn(0, habits.size), h)
        changed(ctx)
    }

    /** New order for some habits (one category) – they swap among their own places. */
    fun reorderWithin(ctx: Context, ids: List<String>) {
        val set = ids.toSet()
        val slots = habits.indices.filter { habits[it].id in set }
        val byId = habits.associateBy { it.id }
        val sorted = ids.mapNotNull { byId[it] }
        if (sorted.size != slots.size) return
        slots.forEachIndexed { i, slot -> habits[slot] = sorted[i] }
        changed(ctx)
    }

    /** Save the sorting screen: category order, habit order, and which category each habit is in. */
    fun applyLayout(ctx: Context, catOrder: List<String>, habitOrder: List<Pair<String, String>>) {
        val cById = categories.associateBy { it.id }
        val newCats = catOrder.mapNotNull { cById[it] }
        val hById = habits.associateBy { it.id }
        val newHabits = habitOrder.mapNotNull { (id, cat) -> hById[id]?.copy(category = cat) }
        if (newCats.size != categories.size || newHabits.size != habits.size) return
        categories.clear()
        categories += newCats
        habits.clear()
        habits += newHabits
        changed(ctx)
    }

    // ── Categories ──

    fun category(id: String) = categories.find { it.id == id }

    fun addCategory(ctx: Context, name: String): Category {
        val c = Category(name = name.trim())
        categories += c
        changed(ctx)
        return c
    }

    fun renameCategory(ctx: Context, id: String, name: String) {
        val i = categories.indexOfFirst { it.id == id }
        if (i < 0) return
        categories[i] = categories[i].copy(name = name.trim())
        changed(ctx)
    }

    /** Deleting a category keeps its habits – they just become "בלי קטגוריה". */
    fun deleteCategory(ctx: Context, id: String) {
        categories.removeAll { it.id == id }
        habits.replaceAll { if (it.category == id) it.copy(category = "") else it }
        changed(ctx)
    }

    fun reorderCategories(ctx: Context, ids: List<String>) {
        val byId = categories.associateBy { it.id }
        val sorted = ids.mapNotNull { byId[it] }
        if (sorted.size != categories.size) return
        categories.clear()
        categories += sorted
        changed(ctx)
    }

    /** Habits grouped by category, in Daniel's order; habits without one come last (null). */
    fun grouped(list: List<Habit>): List<Pair<Category?, List<Habit>>> {
        val known = categories.map { it.id }.toSet()
        val groups = categories.map { c -> c to list.filter { it.category == c.id } }
            .filter { it.second.isNotEmpty() }
        val rest = list.filter { it.category !in known }
        return if (rest.isEmpty()) groups else groups + (null to rest)
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

/** One-time task (ONCE / HOMEWORK): no days, no streak, deleted when done. */
val Habit.isTask get() = type == HabitType.ONCE || type == HabitType.HOMEWORK

val Habit.isWeekly get() = perWeek > 0 && type != HabitType.QUIT && !isTask

/** A day the habit can be done on at all: from its start, never Shabbat or a holiday. */
fun Habit.available(date: LocalDate) = !date.isBefore(createdDate()) && !JewishDays.isRestDay(date)

/** On the list for that day (picked day, not Shabbat/chag). Weekly habits have no fixed days. */
fun Habit.scheduled(date: LocalDate) = !isTask && !isWeekly && available(date) && date.dayOfWeek.value in days

/**
 * Must be done or the streak breaks. Erev Shabbat/chag is a short day, so there it's
 * only a bonus – except for "quit" habits, where a slip still counts.
 */
fun Habit.required(date: LocalDate) =
    scheduled(date) && (type == HabitType.QUIT || !JewishDays.isErev(date))

/** Done on a day it wasn't required (erev, or motzaei Shabbat) – adds to the streak, never breaks it. */
fun Habit.bonus(date: LocalDate) =
    type != HabitType.QUIT && !isTask && !isWeekly && !date.isBefore(createdDate()) && !required(date) &&
        (scheduled(date) || JewishDays.isRestDay(date)) && done(date)

/** Can it be marked on that day (in the app list and the widget)? */
fun Habit.markableOn(date: LocalDate) =
    activeOn(date) ||
        (JewishDays.isRestDay(date) && type != HabitType.QUIT && !isWeekly && !date.isBefore(createdDate()))

// ── Weekly habits: the week runs Sunday–Shabbat ──

fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value % 7).toLong())

/** How many times it was done in the week starting on [start]. */
fun Habit.weekCount(start: LocalDate) = (0L..6L).count { done(start.plusDays(it)) }

/** The goal for that week – smaller if a holiday leaves fewer days. */
fun Habit.weekGoal(start: LocalDate) = minOf(perWeek, (0L..6L).count { available(start.plusDays(it)) })

fun Habit.weekComplete(date: LocalDate) = weekCount(weekStart(date)) >= weekGoal(weekStart(date))

/** Show it on today's list (and in the widget)? */
fun Habit.activeOn(date: LocalDate) =
    if (isTask) true
    else if (isWeekly) available(date) && (!weekComplete(date) || done(date)) else scheduled(date)

fun Habit.done(date: LocalDate): Boolean {
    val c = Store.count(this, date)
    return when (type) {
        HabitType.CHECK, HabitType.SUCCEED -> c >= 1
        HabitType.COUNT -> c >= target
        HabitType.QUIT -> c == 0
        HabitType.ONCE, HabitType.HOMEWORK -> c >= 1
    }
}

/**
 * Days in a row. Days the habit isn't required (Shabbat, holidays, days not picked)
 * are skipped, so they never break the streak. Today only counts once it's done.
 */
fun Habit.streak(today: LocalDate = LocalDate.now()): Int {
    if (isTask) return 0
    if (isWeekly) return weekStreak(today)
    var s = 0
    var d = today
    val start = createdDate()
    while (!d.isBefore(start)) {
        if (required(d)) {
            if (done(d)) s++
            else if (d != today || type == HabitType.QUIT) return s
        } else if (bonus(d)) s++
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
    if (isTask) return 0
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
        } else if (bonus(d)) {
            run++
            if (run > best) best = run
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
