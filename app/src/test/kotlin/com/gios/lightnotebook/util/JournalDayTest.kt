package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalDayTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(nyc).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    /* ---- the whole point ---- */

    @Test
    fun `one in the morning belongs to the night before`() {
        assertEquals(day(2026, 7, 30), JournalDay.dayOf(ms(2026, 7, 31, 1, 0), nyc))
    }

    @Test
    fun `so does three fifty-nine`() {
        assertEquals(day(2026, 7, 30), JournalDay.dayOf(ms(2026, 7, 31, 3, 59), nyc))
    }

    @Test
    fun `four in the morning starts the new day`() {
        assertEquals(day(2026, 7, 31), JournalDay.dayOf(ms(2026, 7, 31, 4, 0), nyc))
    }

    @Test
    fun `the middle of the afternoon is unsurprising`() {
        assertEquals(day(2026, 7, 31), JournalDay.dayOf(ms(2026, 7, 31, 14, 0), nyc))
    }

    @Test
    fun `late evening still belongs to its own date`() {
        assertEquals(day(2026, 7, 31), JournalDay.dayOf(ms(2026, 7, 31, 23, 30), nyc))
    }

    /* ---- the window ---- */

    @Test
    fun `a day runs from four to four`() {
        val window = JournalDay.windowMs(day(2026, 7, 30), nyc)
        assertEquals(ms(2026, 7, 30, 4, 0), window.first)
        assertEquals(ms(2026, 7, 31, 4, 0) - 1, window.last)
    }

    @Test
    fun `the cutover instant belongs to exactly one day`() {
        val boundary = ms(2026, 7, 31, 4, 0)
        assertEquals(day(2026, 7, 31), JournalDay.dayOf(boundary, nyc))
        assertEquals(boundary - 1, JournalDay.windowMs(day(2026, 7, 30), nyc).last)
        assertEquals(boundary, JournalDay.windowMs(day(2026, 7, 31), nyc).first)
    }

    @Test
    fun `consecutive days tile with no gap and no overlap`() {
        var d = day(2026, 1, 1)
        val end = day(2026, 12, 31)
        while (d < end) {
            assertEquals(
                JournalDay.windowMs(d, nyc).last + 1,
                JournalDay.windowMs(d + 1, nyc).first,
            )
            d++
        }
    }

    /* ---- clocks that change ---- */

    @Test
    fun `the cutover is a wall-clock hour, not midnight plus four`() {
        // 2026-03-08 loses an hour at 2am. Adding four real hours to midnight lands at five in the
        // morning; four o'clock as the wall clock reads it is what a person means.
        val start = JournalDay.startMs(day(2026, 3, 8), nyc)
        assertEquals(ms(2026, 3, 8, 4, 0), start)
    }

    @Test
    fun `the clocks change inside a journal day, not on its boundary`() {
        // Worth stating because it is off by one from the midnight version and looks like a bug.
        // The clocks go forward at 2am on the 8th, which is *before* that day's 4am cutover — so it
        // falls inside the day that began at 4am on the 7th, and that is the day that is short.
        assertEquals(23L, JournalDay.lengthMs(day(2026, 3, 7), nyc) / 3_600_000L)
        assertEquals(24L, JournalDay.lengthMs(day(2026, 3, 8), nyc) / 3_600_000L)
    }

    @Test
    fun `and the same the other way in autumn`() {
        assertEquals(25L, JournalDay.lengthMs(day(2026, 10, 31), nyc) / 3_600_000L)
        assertEquals(24L, JournalDay.lengthMs(day(2026, 11, 1), nyc) / 3_600_000L)
    }

    /* ---- position within the day ---- */

    @Test
    fun `the top of a day is the cutover`() {
        assertEquals(0, JournalDay.minutesInto(ms(2026, 7, 30, 4, 0), day(2026, 7, 30), nyc))
    }

    @Test
    fun `a late night runs down the day rather than off the bottom of it`() {
        // 1am on the 31st is twenty-one hours into the 30th — near the bottom of the 30th's column,
        // which is where a night owl's activity should be, not at the top of the next cell.
        val minutes = JournalDay.minutesInto(ms(2026, 7, 31, 1, 0), day(2026, 7, 30), nyc)
        assertEquals(21 * 60, minutes)
    }

    @Test
    fun `midday sits in the middle-ish`() {
        assertEquals(8 * 60, JournalDay.minutesInto(ms(2026, 7, 30, 12, 0), day(2026, 7, 30), nyc))
    }

    @Test
    fun `an offset never escapes its day`() {
        val early = JournalDay.minutesInto(ms(2026, 7, 20, 0, 0), day(2026, 7, 30), nyc)
        val late = JournalDay.minutesInto(ms(2026, 8, 20, 0, 0), day(2026, 7, 30), nyc)
        assertEquals(0, early)
        assertEquals(24 * 60 - 1, late)
    }

    @Test
    fun `an offset reads back as a clock time`() {
        assertEquals(4 * 60, JournalDay.clockMinutes(0))
        assertEquals(12 * 60, JournalDay.clockMinutes(8 * 60))
        // Twenty-one hours in is one in the morning, not twenty-five o'clock.
        assertEquals(60, JournalDay.clockMinutes(21 * 60))
    }

    /* ---- a different cutover ---- */

    @Test
    fun `midnight cutover is the old behaviour exactly`() {
        assertEquals(day(2026, 7, 31), JournalDay.dayOf(ms(2026, 7, 31, 1, 0), nyc, cutoverHour = 0))
        assertEquals(0, JournalDay.minutesInto(ms(2026, 7, 30, 0, 0), day(2026, 7, 30), nyc, 0))
    }
}
