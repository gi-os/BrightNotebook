package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingTest {

    private val start = 1_000_000_000L
    private val end = start + 24 * 60 * 60_000L

    private fun at(hours: Double) = start + (hours * 60 * 60_000L).toLong()
    private fun plug(hours: Double) = Charging.Event(at(hours), Charging.Kind.Plugged)
    private fun unplug(hours: Double) = Charging.Event(at(hours), Charging.Kind.Unplugged)

    @Test
    fun `a plug and an unplug inside the day is one span`() {
        val spans = Charging.spansIn(listOf(plug(1.0), unplug(9.0)), start, end, nowMs = end)
        assertEquals(1, spans.size)
        assertEquals(8 * 60, spans.single().lengthMinutes)
        assertTrue(!spans.single().startedEarlier)
        assertTrue(!spans.single().stillGoing)
    }

    @Test
    fun `an unplug with no plug means the charge began before this day`() {
        // A night's charge seen from the morning. Without the state at the boundary the whole
        // span is invisible, which is the case that matters most — it is the one that says when
        // you went to bed.
        val spans = Charging.spansIn(
            listOf(Charging.Event(start - 60 * 60_000L, Charging.Kind.Plugged), unplug(3.0)),
            start,
            end,
            nowMs = end,
        )
        assertEquals(1, spans.size)
        assertTrue(spans.single().startedEarlier)
        assertEquals(3 * 60, spans.single().lengthMinutes)
    }

    @Test
    fun `a plug with no unplug is still going`() {
        val now = at(20.0)
        val spans = Charging.spansIn(listOf(plug(18.0)), start, end, nowMs = now)
        assertEquals(1, spans.size)
        assertTrue(spans.single().stillGoing)
        // Up to now, not to the end of the day: "charging until midnight" is a guess.
        assertEquals(2 * 60, spans.single().lengthMinutes)
    }

    @Test
    fun `two charges in a day are two spans`() {
        val spans = Charging.spansIn(
            listOf(plug(1.0), unplug(7.0), plug(13.0), unplug(14.0)),
            start,
            end,
            nowMs = end,
        )
        assertEquals(listOf(6 * 60, 60), spans.map { it.lengthMinutes })
    }

    @Test
    fun `a knocked cable is not a charge`() {
        val spans = Charging.spansIn(
            listOf(plug(1.0), unplug(1.0 + 2.0 / 60.0)),
            start,
            end,
            nowMs = end,
        )
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `a second plug while already plugged in does not restart the span`() {
        // Duplicate broadcasts happen; so does a reboot mid-charge re-announcing the state.
        val spans = Charging.spansIn(
            listOf(plug(1.0), plug(2.0), unplug(9.0)),
            start,
            end,
            nowMs = end,
        )
        assertEquals(1, spans.size)
        assertEquals(8 * 60, spans.single().lengthMinutes)
    }

    @Test
    fun `an unplug while already unplugged is ignored`() {
        val spans = Charging.spansIn(listOf(unplug(2.0), unplug(3.0)), start, end, nowMs = end)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `no events is no spans, and so is an inverted window`() {
        assertTrue(Charging.spansIn(emptyList(), start, end).isEmpty())
        assertTrue(Charging.spansIn(listOf(plug(1.0)), end, start).isEmpty())
    }

    @Test
    fun `lengths read as a person would say them`() {
        assertEquals("45m", Charging.length(45))
        assertEquals("1h", Charging.length(60))
        assertEquals("7h 30m", Charging.length(450))
    }
}
