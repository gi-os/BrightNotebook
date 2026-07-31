package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoDaysTest {

    private val nyc = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun ms(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).toEpochDay()

    /* ---- the bug this file exists for ---- */

    @Test
    fun `an evening photograph stays on the day it was taken`() {
        // 9pm in New York is 01:00 the next day in UTC. Dividing by 86_400_000 files this
        // photograph under the 31st, and it disappears out of the 30th's filmstrip.
        val evening = ms(nyc, 2026, 7, 30, 21, 0)
        assertEquals(day(2026, 7, 30), PhotoDays.localEpochDay(evening, nyc))
    }

    @Test
    fun `a photograph after midnight belongs to the night before`() {
        // A day ends when you go to bed. See JournalDay — this file delegates to it so the strip,
        // the step graph and the bookends cannot disagree about which day a moment was part of.
        val lateNight = ms(nyc, 2026, 7, 31, 1, 30)
        assertEquals(day(2026, 7, 30), PhotoDays.localEpochDay(lateNight, nyc))
    }

    @Test
    fun `an early morning photograph east of UTC stays put too`() {
        // The same error with the sign reversed: 7am in Tokyo is the previous day in UTC. Seven is
        // after the cutover, so it is its own day's morning.
        val morning = ms(tokyo, 2026, 7, 30, 7, 0)
        assertEquals(day(2026, 7, 30), PhotoDays.localEpochDay(morning, tokyo))
    }

    /* ---- units ---- */

    @Test
    fun `DATE_TAKEN is read as milliseconds`() {
        val taken = ms(nyc, 2026, 7, 30, 12, 0)
        assertEquals(taken, PhotoDays.instantMs(taken, 0L))
    }

    @Test
    fun `DATE_ADDED is read as seconds`() {
        val instant = ms(nyc, 2026, 7, 30, 12, 0)
        assertEquals(instant, PhotoDays.instantMs(0L, instant / 1000L))
    }

    @Test
    fun `a DATE_TAKEN written in seconds is not read as 1970`() {
        // The corruption seen in the wild. Taken naively this is 1970-01-19 and the photo
        // lands 56 years in the past, at the far end of a calendar nobody will scroll to.
        val instant = ms(nyc, 2026, 7, 30, 12, 0)
        val secondsInAMillisColumn = instant / 1000L
        assertEquals(instant, PhotoDays.instantMs(secondsInAMillisColumn, 0L))
    }

    @Test
    fun `DATE_TAKEN is preferred over DATE_ADDED`() {
        val taken = ms(nyc, 2020, 1, 1, 12, 0)
        val added = ms(nyc, 2026, 7, 30, 12, 0) / 1000L
        // A photograph copied onto the phone today was still taken in 2020.
        assertEquals(taken, PhotoDays.instantMs(taken, added))
    }

    @Test
    fun `a row with no usable timestamp is dropped rather than filed under 1970`() {
        assertNull(PhotoDays.instantMs(0L, 0L))
        assertNull(PhotoDays.instantMs(null, null))
        assertNull(PhotoDays.instantMs(-1L, -1L))
    }

    @Test
    fun `a timestamp in the far future is not believed`() {
        assertNull(PhotoDays.instantMs(PhotoDays.IMPLAUSIBLE_MS + 1, 0L))
    }

    /* ---- the query window ---- */

    @Test
    fun `the window runs from one cutover to the next, in local time`() {
        val d = day(2026, 7, 30)
        val window = PhotoDays.windowMs(d, d, nyc)
        assertEquals(ms(nyc, 2026, 7, 30, 4, 0), window.first)
        // Half-open: the last value is one millisecond before the next day begins.
        assertEquals(ms(nyc, 2026, 7, 31, 4, 0) - 1, window.last)
    }

    @Test
    fun `a window still spans the hour the clocks changed`() {
        // The clocks go forward at 2am on the 8th, which is before that day's cutover — so the
        // short day is the one that began at 4am on the 7th. A window built as days times
        // 86_400_000 would be an hour wrong and pull in a neighbour's photographs.
        val d = day(2026, 3, 7)
        val window = PhotoDays.windowMs(d, d, nyc)
        assertEquals(23L, (window.last + 1 - window.first) / 3_600_000L)
    }

    @Test
    fun `and the hour it gained`() {
        val d = day(2026, 10, 31)
        val window = PhotoDays.windowMs(d, d, nyc)
        assertEquals(25L, (window.last + 1 - window.first) / 3_600_000L)
    }

    @Test
    fun `a reversed range is read the way round it was meant`() {
        val a = day(2026, 7, 1)
        val b = day(2026, 7, 31)
        assertEquals(PhotoDays.windowMs(a, b, nyc), PhotoDays.windowMs(b, a, nyc))
    }

    /* ---- filing a row ---- */

    @Test
    fun `a photograph outside the window is not filed`() {
        val taken = ms(nyc, 2026, 7, 30, 12, 0)
        assertNull(
            PhotoDays.dayIfWithin(taken, 0L, day(2026, 8, 1), day(2026, 8, 31), nyc),
        )
    }

    @Test
    fun `a photograph on the last evening of the window is filed`() {
        // The end of the range is where the timezone error would bite: an 11pm photograph on
        // the last day of a month has a UTC timestamp in the next month.
        val taken = ms(nyc, 2026, 7, 31, 23, 30)
        assertEquals(
            day(2026, 7, 31),
            PhotoDays.dayIfWithin(taken, 0L, day(2026, 7, 1), day(2026, 7, 31), nyc),
        )
    }

    @Test
    fun `a photograph at 2am on the first of the month is the previous month's`() {
        val taken = ms(nyc, 2026, 8, 1, 2, 0)
        assertEquals(
            day(2026, 7, 31),
            PhotoDays.dayIfWithin(taken, 0L, day(2026, 7, 1), day(2026, 7, 31), nyc),
        )
    }

    @Test
    fun `a row with only DATE_ADDED still gets filed`() {
        val instant = ms(nyc, 2026, 7, 30, 12, 0)
        assertEquals(
            day(2026, 7, 30),
            PhotoDays.dayIfWithin(0L, instant / 1000L, day(2026, 7, 1), day(2026, 7, 31), nyc),
        )
    }
}
