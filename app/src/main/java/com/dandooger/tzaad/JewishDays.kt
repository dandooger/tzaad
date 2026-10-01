package com.dandooger.tzaad

import android.icu.text.DateFormat
import android.icu.util.Calendar
import android.icu.util.HebrewCalendar
import android.icu.util.ULocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Shabbat and Yom Tov (Israel schedule) – days when streaks are protected. */
object JewishDays {
    // Concurrent: the widget list reads it from a background thread.
    private val cache = java.util.concurrent.ConcurrentHashMap<LocalDate, String>()

    private fun hebrew(date: LocalDate): HebrewCalendar {
        val c = HebrewCalendar()
        c.timeInMillis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return c
    }

    private fun holiday(date: LocalDate): String? {
        val c = hebrew(date)
        val m = c.get(Calendar.MONTH)
        val d = c.get(Calendar.DAY_OF_MONTH)
        return when {
            m == HebrewCalendar.TISHRI && (d == 1 || d == 2) -> "ראש השנה"
            m == HebrewCalendar.TISHRI && d == 10 -> "יום כיפור"
            m == HebrewCalendar.TISHRI && d == 15 -> "סוכות"
            m == HebrewCalendar.TISHRI && d == 22 -> "שמחת תורה"
            m == HebrewCalendar.NISAN && d == 15 -> "פסח"
            m == HebrewCalendar.NISAN && d == 21 -> "שביעי של פסח"
            m == HebrewCalendar.SIVAN && d == 6 -> "שבועות"
            else -> null
        }
    }

    /** "שבת", a holiday name, or null on a regular day. */
    fun restName(date: LocalDate): String? {
        val cached = cache.getOrPut(date) {
            holiday(date) ?: if (date.dayOfWeek == DayOfWeek.SATURDAY) "שבת" else ""
        }
        return cached.ifEmpty { null }
    }

    fun isRestDay(date: LocalDate) = restName(date) != null

    /** Friday or the day before a holiday. */
    fun isErev(date: LocalDate) = !isRestDay(date) && isRestDay(date.plusDays(1))

    fun hebrewDate(date: LocalDate): String = try {
        val c = hebrew(date)
        DateFormat.getDateInstance(c, DateFormat.LONG, ULocale("he")).format(c.time)
    } catch (e: Exception) {
        ""
    }
}
