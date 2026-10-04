package com.dandooger.tzaad

import android.icu.text.DateFormat
import android.icu.util.Calendar
import android.icu.util.HebrewCalendar
import android.icu.util.ULocale
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

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

    // ── Exact Shabbat / chag times for Givot Eden (Beit Shemesh – Emek HaElah area) ──
    // Same rules as Hebcal's Beit Shemesh calendar: candle lighting 20 minutes before
    // sunset, Shabbat ends when the sun is 8.5° below the horizon.

    private const val LAT = 31.66
    private const val LON = 35.016
    val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
    private const val CANDLE_MINUTES = 20

    /** Minutes after UTC midnight when the setting sun reaches [zenith] degrees (NOAA formulas). */
    private fun sunDownUtcMinutes(date: LocalDate, zenith: Double): Double {
        var y = date.year
        var m = date.monthValue
        if (m <= 2) { y--; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        val jd = floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + date.dayOfMonth + b - 1524.5
        var utc = 720 - 4 * LON
        repeat(3) {
            val t = (jd + utc / 1440 - 2451545) / 36525
            val l0 = (280.46646 + t * (36000.76983 + 0.0003032 * t)) % 360
            val mean = 357.52911 + t * (35999.05029 - 0.0001537 * t)
            val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
            val c = sin(rad(mean)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(rad(2 * mean)) * (0.019993 - 0.000101 * t) + sin(rad(3 * mean)) * 0.000289
            val omega = 125.04 - 1934.136 * t
            val lambda = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
            val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
            val eps = eps0 + 0.00256 * cos(rad(omega))
            val decl = deg(asin(sin(rad(eps)) * sin(rad(lambda))))
            val yy = tan(rad(eps / 2)).let { it * it }
            val eqTime = 4 * deg(
                yy * sin(2 * rad(l0)) - 2 * e * sin(rad(mean)) +
                    4 * e * yy * sin(rad(mean)) * cos(2 * rad(l0)) -
                    0.5 * yy * yy * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(mean))
            )
            val ha = deg(
                acos(cos(rad(zenith)) / (cos(rad(LAT)) * cos(rad(decl))) - tan(rad(LAT)) * tan(rad(decl)))
            )
            utc = 720 - 4 * (LON - ha) - eqTime
        }
        return utc
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun utcMidnight(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Candle lighting on [date] (the erev). */
    fun candleLighting(date: LocalDate): Instant =
        utcMidnight(date).plusSeconds(60 * floor(sunDownUtcMinutes(date, 90.833) - CANDLE_MINUTES).toLong())

    /** When Shabbat / chag that falls on [date] goes out. */
    fun havdalah(date: LocalDate): Instant =
        utcMidnight(date).plusSeconds(60 * Math.round(sunDownUtcMinutes(date, 98.5)))

    /** One Shabbat/chag stretch – several days in a row count as one (e.g. chag + Shabbat). */
    data class RestWindow(val start: Instant, val end: Instant, val name: String, val lastName: String) {
        val greeting get() = if (name == "שבת") "שבת שלום 🕯️" else "$name – חג שמח 🕯️"
        val opensAt get() = (if (lastName == "שבת") "במוצאי שבת" else "במוצאי החג") + " ב־" + hhmm(end)
    }

    private fun blockFrom(first: LocalDate): RestWindow {
        var last = first
        while (isRestDay(last.plusDays(1))) last = last.plusDays(1)
        return RestWindow(candleLighting(first.minusDays(1)), havdalah(last), restName(first)!!, restName(last)!!)
    }

    /** The Shabbat/chag we're inside right now, or null. */
    fun currentRest(now: Instant = Instant.now()): RestWindow? {
        val today = now.atZone(ZONE).toLocalDate()
        if (isRestDay(today)) {
            var first = today
            while (isRestDay(first.minusDays(1))) first = first.minusDays(1)
            val w = blockFrom(first)
            return if (now.isBefore(w.end)) w else null
        }
        if (isRestDay(today.plusDays(1))) {
            val w = blockFrom(today.plusDays(1))
            return if (!now.isBefore(w.start)) w else null
        }
        return null
    }

    fun restNow() = currentRest() != null

    /** The next time the app should close or open again. */
    fun nextChange(now: Instant = Instant.now()): Instant? {
        currentRest(now)?.let { return it.end }
        var d = now.atZone(ZONE).toLocalDate()
        repeat(15) {
            if (isErev(d)) {
                val start = candleLighting(d)
                if (start.isAfter(now)) return start
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** Candle lighting today if today is erev Shabbat/chag, else null. */
    fun candleToday(today: LocalDate): Instant? = if (isErev(today)) candleLighting(today) else null

    fun hhmm(i: Instant): String = i.atZone(ZONE).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}
