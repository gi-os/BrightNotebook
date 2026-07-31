package com.gios.lightnotebook.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dates are stored as epoch days — a plain Long, no timezone, no clock. A day entry
 * belongs to a calendar square, not to an instant, so anything with a timezone in it
 * would only introduce the chance of a note sliding into the wrong box.
 *
 * Android-free on purpose so it can be tested off-device.
 */
object NoteDates {

    /** Sunday-first, matching the LPIII's own month view. */
    val weekdayInitials: List<String> = listOf("S", "M", "T", "W", "T", "F", "S")

    fun today(): Long = LocalDate.now().toEpochDay()

    /** Minutes from local midnight, for the line between what has happened and what has not. */
    fun nowMinutes(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    fun of(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun monthOf(epochDay: Long): YearMonth = YearMonth.from(of(epochDay))

    /** "JULY 2026" — the month header. */
    fun monthTitle(month: YearMonth): String {
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.US).uppercase(Locale.US)
        return "$name ${month.year}"
    }

    /** "WED 29 JULY" — the day screen header. */
    fun dayTitle(epochDay: Long): String {
        val d = of(epochDay)
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US)
        val month = d.month.getDisplayName(TextStyle.FULL, Locale.US).uppercase(Locale.US)
        return "$dow ${d.dayOfMonth} $month"
    }

    /** "29 Jul" — compact, for note rows and event lists. */
    fun shortDate(epochDay: Long): String {
        val d = of(epochDay)
        val month = d.month.getDisplayName(TextStyle.SHORT, Locale.US)
        return "${d.dayOfMonth} $month"
    }

    /**
     * The month laid out as whole weeks. Padding cells are null rather than dates from
     * the neighbouring months: on a small greyscale screen, dimmed-out spillover days
     * read as tappable and are not.
     */
    fun weeks(month: YearMonth): List<List<Long?>> {
        val first = month.atDay(1)
        val lead = sundayIndex(first.dayOfWeek)
        val cells = ArrayList<Long?>(42)
        repeat(lead) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day).toEpochDay())
        while (cells.size % 7 != 0) cells.add(null)
        return cells.chunked(7)
    }

    private fun sundayIndex(day: DayOfWeek): Int = day.value % 7

    /** Minutes from midnight rendered as "9:00 AM"; null means an all-day entry. */
    fun clock(minutesOfDay: Int?): String? {
        val m = minutesOfDay ?: return null
        if (m !in 0..1439) return null
        val hour24 = m / 60
        val minute = m % 60
        val suffix = if (hour24 < 12) "AM" else "PM"
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, suffix)
    }

    /** Parses "9", "9:30", "09:30", "9pm", "9:30 PM", "21:30" into minutes of day. */
    fun parseClock(raw: String?): Int? {
        val s = raw?.trim()?.lowercase(Locale.US)?.replace(".", "") ?: return null
        if (s.isEmpty()) return null
        val pm = s.endsWith("pm")
        val am = s.endsWith("am")
        val core = s.removeSuffix("pm").removeSuffix("am").trim()
        val parts = core.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.trim()?.take(2)?.toIntOrNull() ?: 0
        if (minute !in 0..59) return null
        val h = when {
            pm && hour in 1..11 -> hour + 12
            am && hour == 12 -> 0
            else -> hour
        }
        if (h !in 0..23) return null
        return h * 60 + minute
    }

    /**
     * Pulls a leading clock time off a typed line, so "9:30 dentist" files itself at half
     * past nine while "dentist" stays an all-day entry. This is the whole time picker.
     */
    fun splitLeadingTime(text: String): Pair<Int?, String> {
        val trimmed = text.trim()
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) return null to trimmed
        // A bare number is not a time: "3 loads of laundry" is not an appointment at
        // three in the morning. Insist on a colon or an am/pm before believing it.
        val token = parts[0].lowercase(Locale.US)
        val looksLikeTime = token.contains(':') || token.endsWith("am") || token.endsWith("pm")
        if (!looksLikeTime) return null to trimmed
        val minutes = parseClock(parts[0]) ?: return null to trimmed
        val rest = parts[1].trim()
        if (rest.isEmpty()) return null to trimmed
        return minutes to rest
    }

    /** ISO "2026-07-29" to epoch day, or null if the model handed back something else. */
    fun parseIsoDate(raw: String?): Long? {
        val s = raw?.trim() ?: return null
        return runCatching { LocalDate.parse(s).toEpochDay() }.getOrNull()
    }

    /** Epoch day back to ISO, for the prompt and for debugging. */
    fun isoDate(epochDay: Long): String = of(epochDay).toString()
}
