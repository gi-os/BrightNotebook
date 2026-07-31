package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTimelineTest {

    private val today = 20_300L
    private val noon = 12 * 60

    private fun row(id: String, minutes: Int?, day: Long = today) =
        AgendaRow(id = id, epochDay = day, minutes = minutes, title = id)

    private fun photo(id: Long, minutes: Int) = DayTimeline.PhotoAt(id, minutes)

    /* ---- the now line ---- */

    @Test
    fun `only today has a line through it`() {
        assertEquals(noon, DayTimeline.nowLine(today, today, noon))
        assertNull(DayTimeline.nowLine(today - 1, today, noon))
        assertNull(DayTimeline.nowLine(today + 1, today, noon))
    }

    @Test
    fun `a past day is entirely behind and a future day entirely ahead`() {
        assertTrue(DayTimeline.behind(today - 1, 23 * 60, today, noon))
        assertTrue(DayTimeline.behind(today - 1, null, today, noon))
        assertFalse(DayTimeline.behind(today + 1, 0, today, noon))
        assertFalse(DayTimeline.behind(today + 1, null, today, noon))
    }

    @Test
    fun `today splits at the clock`() {
        assertTrue(DayTimeline.behind(today, noon - 1, today, noon))
        assertTrue(DayTimeline.behind(today, noon, today, noon))
        assertFalse(DayTimeline.behind(today, noon + 1, today, noon))
    }

    @Test
    fun `an all-day thing on today is still today's business`() {
        // It has no time to have passed, so it cannot be in the diary half.
        assertFalse(DayTimeline.behind(today, null, today, noon))
    }

    /* ---- clustering, which is what keeps a heavy day bounded ---- */

    @Test
    fun `photographs taken together become one moment`() {
        val burst = (0..10).map { photo(it.toLong(), 480 + it) }
        val clustered = DayTimeline.cluster(burst)
        assertEquals(1, clustered.size)
        assertEquals(11, clustered.first().photos.size)
        assertFalse(clustered.first().single)
    }

    @Test
    fun `morning and evening stay separate`() {
        val clustered = DayTimeline.cluster(listOf(photo(1, 8 * 60), photo(2, 20 * 60)))
        assertEquals(2, clustered.size)
        assertTrue(clustered.all { it.single })
    }

    @Test
    fun `the gap is measured from the previous photograph, not the run's start`() {
        // Steady shooting every fifteen minutes for two hours is one afternoon, not eight
        // arbitrary twenty-minute blocks.
        val steady = (0..7).map { photo(it.toLong(), 14 * 60 + it * 15) }
        assertEquals(1, DayTimeline.cluster(steady).size)
    }

    @Test
    fun `a burst carries the time it started and the time it ended`() {
        val clustered = DayTimeline.cluster(listOf(photo(1, 480), photo(2, 495))).single()
        assertEquals(480, clustered.minutes)
        assertEquals(495, clustered.untilMinutes)
    }

    @Test
    fun `an unsorted list still clusters correctly`() {
        // MediaStore's order is whatever the timestamp reconciliation produced.
        val shuffled = listOf(photo(3, 20 * 60), photo(1, 480), photo(2, 485))
        val clustered = DayTimeline.cluster(shuffled)
        assertEquals(2, clustered.size)
        assertEquals(2, clustered.first().photos.size)
    }

    @Test
    fun `no photographs is no moments`() {
        assertTrue(DayTimeline.cluster(emptyList()).isEmpty())
    }

    /* ---- the order of the day ---- */

    @Test
    fun `all-day things come first and timed things follow in order`() {
        val items = DayTimeline.build(
            rows = listOf(row("evening", 20 * 60), row("all day", null), row("morning", 9 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(
            listOf("all day", "morning", "evening"),
            items.map { (it as DayTimeline.Item.Entry).row.title },
        )
    }

    @Test
    fun `a photograph sorts in among the entries by the time it was taken`() {
        val items = DayTimeline.build(
            rows = listOf(row("breakfast", 8 * 60), row("dinner", 19 * 60)),
            photos = listOf(photo(1, 13 * 60)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(3, items.size)
        assertTrue(items[1] is DayTimeline.Item.Photos)
    }

    @Test
    fun `a photograph is behind even when its clock has drifted forward`() {
        // A camera an hour fast would otherwise put a picture you are looking at into the
        // half of today that has not happened yet.
        val items = DayTimeline.build(
            rows = emptyList(),
            photos = listOf(photo(1, noon + 60)),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items.single().behind)
    }

    @Test
    fun `an entry and a photograph at the same minute put the entry first`() {
        val items = DayTimeline.build(
            rows = listOf(row("lunch", noon)),
            photos = listOf(photo(1, noon)),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertTrue(items.first() is DayTimeline.Item.Entry)
        assertTrue(items.last() is DayTimeline.Item.Photos)
    }

    /* ---- where the rule is drawn ---- */

    @Test
    fun `the rule sits between the two halves of today`() {
        val items = DayTimeline.build(
            rows = listOf(row("done", 9 * 60), row("later", 18 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertEquals(1, DayTimeline.nowLineIndex(items, noon))
    }

    @Test
    fun `no rule on a day that is entirely one thing or the other`() {
        val past = DayTimeline.build(
            rows = listOf(row("was", 9 * 60, today - 1)),
            photos = emptyList(),
            epochDay = today - 1,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(past, DayTimeline.nowLine(today - 1, today, noon)))
    }

    @Test
    fun `no rule when everything on today is on one side of it`() {
        // A rule above everything or below everything has nothing on one side and reads as a
        // mistake rather than as the time.
        val allAhead = DayTimeline.build(
            rows = listOf(row("later", 18 * 60), row("also later", 20 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(allAhead, noon))

        val allBehind = DayTimeline.build(
            rows = listOf(row("done", 8 * 60)),
            photos = emptyList(),
            epochDay = today,
            today = today,
            nowMinutes = noon,
        )
        assertNull(DayTimeline.nowLineIndex(allBehind, noon))
    }

    @Test
    fun `an empty day has no rule`() {
        assertNull(DayTimeline.nowLineIndex(emptyList(), noon))
    }
}
