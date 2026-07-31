package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLayoutTest {

    @Test
    fun `things a few minutes apart are one moment`() {
        // A photograph and the note you wrote about it. Drawing "time passing" between them adds
        // nothing and breaks the pair up.
        assertEquals(0f, DayLayout.gapUnits(0), 0.001f)
        assertEquals(0f, DayLayout.gapUnits(DayLayout.SAME_MOMENT_MINUTES), 0.001f)
    }

    @Test
    fun `a real gap is drawn`() {
        assertTrue(DayLayout.gapUnits(30) >= DayLayout.MIN_UNITS)
    }

    @Test
    fun `a longer wait is always a bigger space`() {
        // The property that makes the compression honest: however hard it is squashed, more time
        // is never less room.
        var previous = -1f
        for (minutes in 0..1440 step 5) {
            val units = DayLayout.gapUnits(minutes)
            assertTrue("at $minutes: $units < $previous", units >= previous)
            previous = units
        }
    }

    @Test
    fun `no gap ever runs off the screen`() {
        for (minutes in 0..3000 step 7) {
            assertTrue(DayLayout.gapUnits(minutes) <= DayLayout.MAX_UNITS)
        }
    }

    @Test
    fun `six hours is not thirty-six times ten minutes`() {
        // Linear would be unusable: a night's sleep would be two hundred screens.
        val short = DayLayout.gapUnits(10)
        val long = DayLayout.gapUnits(360)
        assertTrue("a long gap must still be bigger", long > short)
        assertTrue("but not proportionally so", long < short * 6f)
    }

    @Test
    fun `short gaps are spread out, because those are the ones you can tell apart`() {
        // Ten minutes versus an hour should be a clear difference; four hours versus five, less so.
        val tenToSixty = DayLayout.gapUnits(60) - DayLayout.gapUnits(10)
        val fourToFive = DayLayout.gapUnits(300) - DayLayout.gapUnits(240)
        assertTrue(tenToSixty > fourToFive)
    }

    @Test
    fun `gaps come one per pair, never leading or trailing`() {
        val gaps = DayLayout.gaps(listOf(8 * 60, 14 * 60, 20 * 60))
        assertEquals(2, gaps.size)
    }

    @Test
    fun `the morning-to-afternoon gap in Gio's example is real and visible`() {
        // Photographs at eight, the next thing at two. It must not look adjacent.
        val gaps = DayLayout.gaps(listOf(8 * 60, 14 * 60))
        assertTrue(gaps.single() > DayLayout.MIN_UNITS * 3)
    }

    @Test
    fun `an all-day thing has no duration to draw`() {
        val gaps = DayLayout.gaps(listOf(null, 9 * 60, 18 * 60))
        assertEquals(0f, gaps[0], 0.001f)
        assertTrue(gaps[1] > 0f)
    }

    @Test
    fun `one moment has no gaps and no moments has none either`() {
        assertTrue(DayLayout.gaps(listOf(9 * 60)).isEmpty())
        assertTrue(DayLayout.gaps(emptyList()).isEmpty())
    }

    @Test
    fun `time never runs backwards`() {
        // A defensive case: two items at the same minute in either order must not produce a
        // negative space.
        assertEquals(0f, DayLayout.gaps(listOf(10 * 60, 9 * 60)).single(), 0.001f)
    }

    @Test
    fun `a long stretch says how long it was`() {
        assertNull(DayLayout.labelFor(30))
        assertEquals("1h", DayLayout.labelFor(60))
        assertEquals("4h", DayLayout.labelFor(240))
        assertEquals("2h 30m", DayLayout.labelFor(150))
    }

    @Test
    fun `a longer day does not make every gap bigger`() {
        // On a twenty-five hour day the same hour is a smaller share of it, so it takes slightly
        // less room. The curve is relative to the day, not to a constant.
        val normal = DayLayout.gapUnits(60, 24 * 60)
        val longDay = DayLayout.gapUnits(60, 25 * 60)
        assertTrue(longDay < normal)
    }

    /* ---- hour marks down a gap ---- */

    @Test
    fun `a short gap names no hours`() {
        assertTrue(DayLayout.hoursCrossed(fromMinutes = 100, gapMinutes = 30).isEmpty())
    }

    @Test
    fun `a gap names the hour boundaries it actually crosses`() {
        // Starting 20 minutes into hour 5, running two and a half hours: crosses 6 and 7.
        val hours = DayLayout.hoursCrossed(fromMinutes = 5 * 60 + 20, gapMinutes = 150)
        assertEquals(listOf(6 * 60, 7 * 60), hours)
    }

    @Test
    fun `the boundaries at either end of a gap are left to the moments there`() {
        // Five o'clock exactly to seven o'clock exactly. Neither end is labelled: the moment before
        // the gap and the moment after it both carry their own times, and repeating them in the
        // emptiness between would say the same thing three times.
        assertEquals(listOf(6 * 60), DayLayout.hoursCrossed(fromMinutes = 5 * 60, gapMinutes = 120))
    }

    @Test
    fun `a very long gap is thinned rather than listing every hour`() {
        val hours = DayLayout.hoursCrossed(fromMinutes = 0, gapMinutes = 10 * 60)
        assertTrue("was ${hours.size}", hours.size <= DayLayout.MAX_HOUR_MARKS)
        // The ends of the stretch stay named, so it is still placeable.
        assertEquals(60, hours.first())
        assertEquals(9 * 60, hours.last())
    }

    @Test
    fun `marks are in order and inside the gap`() {
        val from = 3 * 60 + 10
        val gap = 7 * 60
        val hours = DayLayout.hoursCrossed(from, gap)
        assertEquals(hours.sorted(), hours)
        hours.forEach { assertTrue(it in from..(from + gap)) }
    }

    @Test
    fun `hours read as a clock, not as minutes into the day`() {
        // Zero is the cutover, which is four in the morning.
        assertEquals("4AM", DayLayout.hourLabel(0))
        assertEquals("12PM", DayLayout.hourLabel(8 * 60))
        assertEquals("1AM", DayLayout.hourLabel(21 * 60))
        assertEquals("12AM", DayLayout.hourLabel(20 * 60))
    }
}
