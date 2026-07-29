package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class NoteDatesTest {

    @Test
    fun weeksAreWholeAndSundayAligned() {
        // 1 July 2026 is a Wednesday, so the first row has three empty cells.
        val weeks = NoteDates.weeks(YearMonth.of(2026, 7))
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(listOf(null, null, null), weeks[0].take(3))
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), weeks[0][3])
    }

    @Test
    fun weeksCoverEveryDayExactlyOnce() {
        for (month in 1..12) {
            val ym = YearMonth.of(2026, month)
            val days = NoteDates.weeks(ym).flatten().filterNotNull()
            assertEquals(ym.lengthOfMonth(), days.size)
            assertEquals(days.sorted(), days)
            assertEquals(days.toSet().size, days.size)
        }
    }

    @Test
    fun februaryInALeapYearStillFits() {
        val weeks = NoteDates.weeks(YearMonth.of(2024, 2))
        assertEquals(29, weeks.flatten().filterNotNull().size)
    }

    @Test
    fun monthStartingOnSundayHasNoPadding() {
        // 1 February 2026 is a Sunday.
        val weeks = NoteDates.weeks(YearMonth.of(2026, 2))
        assertEquals(LocalDate.of(2026, 2, 1).toEpochDay(), weeks[0][0])
    }

    @Test
    fun titles() {
        val day = LocalDate.of(2026, 7, 29).toEpochDay()
        assertEquals("JULY 2026", NoteDates.monthTitle(YearMonth.of(2026, 7)))
        assertEquals("WED 29 JULY", NoteDates.dayTitle(day))
        assertEquals("29 Jul", NoteDates.shortDate(day))
    }

    @Test
    fun clockFormatsTwelveHour() {
        assertEquals("12:00 AM", NoteDates.clock(0))
        assertEquals("9:05 AM", NoteDates.clock(9 * 60 + 5))
        assertEquals("12:30 PM", NoteDates.clock(12 * 60 + 30))
        assertEquals("9:00 PM", NoteDates.clock(21 * 60))
        assertNull(NoteDates.clock(null))
        assertNull(NoteDates.clock(1440))
    }

    @Test
    fun parseClockHandlesWhatTheModelReturns() {
        assertEquals(9 * 60, NoteDates.parseClock("9"))
        assertEquals(9 * 60 + 30, NoteDates.parseClock("9:30"))
        assertEquals(9 * 60 + 30, NoteDates.parseClock("09:30"))
        assertEquals(21 * 60, NoteDates.parseClock("9pm"))
        assertEquals(21 * 60 + 15, NoteDates.parseClock("9:15 PM"))
        assertEquals(21 * 60 + 30, NoteDates.parseClock("21:30"))
        assertEquals(0, NoteDates.parseClock("12am"))
        assertEquals(12 * 60, NoteDates.parseClock("12pm"))
        assertNull(NoteDates.parseClock(null))
        assertNull(NoteDates.parseClock(""))
        assertNull(NoteDates.parseClock("lunchtime"))
        assertNull(NoteDates.parseClock("31:00"))
    }

    @Test
    fun clockRoundTrips() {
        for (m in 0 until 1440) {
            assertEquals(m, NoteDates.parseClock(NoteDates.clock(m)))
        }
    }

    @Test
    fun leadingTimeIsSplitOffTheLine() {
        assertEquals(9 * 60 + 30 to "dentist", NoteDates.splitLeadingTime("9:30 dentist"))
        assertEquals(21 * 60 to "call mum", NoteDates.splitLeadingTime("9pm call mum"))
        assertEquals(null to "dentist", NoteDates.splitLeadingTime("dentist"))
        // A bare number is a note, not a time — "3 loads of laundry" isn't at three in
        // the morning, and the whole line has to survive intact.
        assertEquals(
            null to "3 loads of laundry",
            NoteDates.splitLeadingTime("3 loads of laundry"),
        )
        assertEquals(null to "9 dentist", NoteDates.splitLeadingTime("9 dentist"))
        assertEquals(null to "9:30", NoteDates.splitLeadingTime("9:30"))
        assertEquals(null to "lunch with Alex", NoteDates.splitLeadingTime("lunch with Alex"))
    }

    @Test
    fun isoDatesRoundTrip() {
        val day = LocalDate.of(2026, 12, 31).toEpochDay()
        assertEquals("2026-12-31", NoteDates.isoDate(day))
        assertEquals(day, NoteDates.parseIsoDate("2026-12-31"))
        assertNull(NoteDates.parseIsoDate("31/12/2026"))
        assertNull(NoteDates.parseIsoDate(null))
    }
}
