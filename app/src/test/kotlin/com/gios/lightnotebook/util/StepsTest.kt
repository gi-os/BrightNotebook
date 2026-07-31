package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {

    private val day0 = 20_300L
    private val midnight0 = 1_753_848_000_000L
    private val hourMs = 3_600_000L

    /** A plain 24-hour test calendar. The DST cases are covered separately below. */
    private fun dayStart(day: Long) = midnight0 + (day - day0) * 24 * hourMs
    private fun hourStart(h: Steps.Hour) = dayStart(h.epochDay) + h.hour * hourMs
    private fun hourOf(ms: Long): Steps.Hour {
        val day = day0 + Math.floorDiv(ms - midnight0, 24 * hourMs)
        return Steps.Hour(day, ((ms - dayStart(day)) / hourMs).toInt())
    }
    private fun nextHour(h: Steps.Hour) =
        if (h.hour + 1 < 24) Steps.Hour(h.epochDay, h.hour + 1) else Steps.Hour(h.epochDay + 1, 0)

    private fun attribute(pc: Long, pAt: Long, c: Long, at: Long) =
        Steps.attribute(pc, pAt, c, at, ::hourStart, ::hourOf, ::nextHour)

    private fun at(day: Long, hour: Int, minute: Int = 0) =
        dayStart(day) + hour * hourMs + minute * 60_000L

    /* ---- the point of bucketing by hour ---- */

    @Test
    fun `steps inside one hour go to that hour`() {
        val a = attribute(1000, at(day0, 9, 0), 1400, at(day0, 9, 30))
        assertEquals(mapOf(Steps.Hour(day0, 9) to 400), a.perHour)
        assertEquals(mapOf(day0 to 400), a.perDay)
    }

    @Test
    fun `a walk shows up as steps in the hours it happened in`() {
        // The whole reason for hours: "eight thousand steps" says nothing about a day, but two
        // thousand of them between two and four says you went somewhere.
        val a = attribute(0, at(day0, 14, 0), 2000, at(day0, 16, 0))
        assertEquals(setOf(Steps.Hour(day0, 14), Steps.Hour(day0, 15)), a.perHour.keys)
        assertEquals(1000, a.perHour[Steps.Hour(day0, 14)])
        assertEquals(1000, a.perHour[Steps.Hour(day0, 15)])
    }

    @Test
    fun `days derive from hours and cannot disagree with them`() {
        val a = attribute(0, at(day0, 23, 0), 100, at(day0 + 1, 1, 0))
        assertEquals(100, a.perHour.values.sum())
        assertEquals(a.perHour.values.sum(), a.perDay.values.sum())
        assertEquals(50, a.perDay[day0])
        assertEquals(50, a.perDay[day0 + 1])
    }

    @Test
    fun `an uneven span follows the clock, not the buckets`() {
        // 23:45 to 01:45: fifteen minutes in one hour, sixty in the next, forty-five in the last.
        val a = attribute(0, at(day0, 23, 45), 480, at(day0 + 1, 1, 45))
        assertEquals(60, a.perHour[Steps.Hour(day0, 23)])
        assertEquals(240, a.perHour[Steps.Hour(day0 + 1, 0)])
        assertEquals(180, a.perHour[Steps.Hour(day0 + 1, 1)])
        assertEquals(480, a.perHour.values.sum())
    }

    /* ---- the three things that go wrong ---- */

    @Test
    fun `a reboot resets the counter and is read as such`() {
        // The value going down is the only signal available — a sensor reading carries no boot id.
        // Unhandled, this subtracts a day's walking and the total goes backwards.
        val a = attribute(9000, at(day0, 9), 120, at(day0, 10))
        assertEquals(120, a.perDay[day0])
    }

    @Test
    fun `a stale previous sample is dropped rather than smeared`() {
        val a = attribute(0, at(day0, 9), 40_000, at(day0 + 4, 9))
        assertTrue(a.perHour.isEmpty())
    }

    @Test
    fun `an impossible number is a sensor fault, not a marathon`() {
        val a = attribute(0, at(day0, 9), 5_000_000, at(day0, 10))
        assertTrue(a.perHour.isEmpty())
    }

    @Test
    fun `no movement is no rows`() {
        assertTrue(attribute(1000, at(day0, 9), 1000, at(day0, 9, 30)).perHour.isEmpty())
    }

    @Test
    fun `a sample older than the one before it is ignored`() {
        assertTrue(attribute(1000, at(day0, 9), 1200, at(day0, 8)).perHour.isEmpty())
    }

    @Test
    fun `no steps are lost to rounding`() {
        val a = attribute(0, at(day0, 8, 0), 1001, at(day0, 11, 0))
        assertEquals(1001, a.perHour.values.sum())
    }

    @Test
    fun `a span longer than the guard allows still terminates`() {
        // If a callback ever lied about what the next hour was, this must not spin forever.
        val a = attribute(0, at(day0, 0), 1000, at(day0 + 1, 11))
        assertTrue(a.perHour.values.sum() <= 1000)
    }

    /* ---- a day that is not twenty-four hours ---- */

    @Test
    fun `a twenty-three hour day tiles exactly`() {
        // Hours are slices from the day's own start, not wall-clock hours, so a spring-forward day
        // has twenty-three of them and they still cover it with no gap and no overlap.
        val shortDayStart = midnight0
        val shortLength = 23
        fun hStart(h: Steps.Hour) = shortDayStart + h.hour * hourMs
        fun hOf(ms: Long) = Steps.Hour(day0, ((ms - shortDayStart) / hourMs).toInt())
        fun next(h: Steps.Hour) =
            if (h.hour + 1 < shortLength) Steps.Hour(day0, h.hour + 1) else Steps.Hour(day0 + 1, 0)

        val a = Steps.attribute(
            0, shortDayStart, 230, shortDayStart + shortLength * hourMs - 1,
            ::hStart, ::hOf, ::next,
        )
        assertEquals(230, a.perHour.values.sum())
        assertEquals(shortLength, a.perHour.size)
    }

    @Test
    fun `thousands are readable`() {
        assertEquals("8,412", Steps.format(8412))
        assertEquals("412", Steps.format(412))
        assertEquals("12,345,678", Steps.format(12345678))
        assertEquals("0", Steps.format(0))
    }
}
