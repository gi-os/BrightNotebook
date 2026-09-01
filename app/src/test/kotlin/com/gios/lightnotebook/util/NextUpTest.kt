package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(nyc).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    // A Monday morning, ten o'clock.
    private val now = ms(2026, 8, 31, 10, 0)

    private fun timed(d: Long, minutes: Int, title: String, kind: String = "event") =
        NextUp.Candidate(d, minutes, title, kind)

    private fun allDay(d: Long, title: String, kind: String = "event") =
        NextUp.Candidate(d, null, title, kind)

    @Test
    fun `the earliest timed thing wins`() {
        val pick = NextUp.pick(
            listOf(
                timed(day(2026, 8, 31), 15 * 60, "Dentist", "reminder"),
                timed(day(2026, 8, 31), 19 * 60, "Dinner"),
                timed(day(2026, 9, 1), 9 * 60, "Standup"),
            ),
            now, nyc,
        )!!
        assertEquals("Dentist", pick.title)
        assertEquals("reminder", pick.kind)
        assertEquals(ms(2026, 8, 31, 15, 0), pick.startAt)
        assertFalse(pick.allDay)
    }

    @Test
    fun `something already started is not next`() {
        val pick = NextUp.pick(
            listOf(
                timed(day(2026, 8, 31), 9 * 60, "Missed it"),
                timed(day(2026, 8, 31), 11 * 60, "Coffee"),
            ),
            now, nyc,
        )!!
        assertEquals("Coffee", pick.title)
    }

    @Test
    fun `the horizon is 48 hours, not the calendar week`() {
        // Wednesday 10:01 is past the horizon from Monday 10:00; Wednesday 09:00 is inside.
        assertNull(
            NextUp.pick(listOf(timed(day(2026, 9, 2), 10 * 60 + 1, "Too far")), now, nyc),
        )
        val pick = NextUp.pick(listOf(timed(day(2026, 9, 2), 9 * 60, "Just inside")), now, nyc)!!
        assertEquals("Just inside", pick.title)
    }

    @Test
    fun `a timed thing beats an earlier all-day thing`() {
        val pick = NextUp.pick(
            listOf(
                allDay(day(2026, 8, 31), "Labor Day"),
                timed(day(2026, 9, 1), 15 * 60, "Dentist"),
            ),
            now, nyc,
        )!!
        assertEquals("Dentist", pick.title)
    }

    @Test
    fun `with nothing timed the nearest all-day thing stands in`() {
        val pick = NextUp.pick(
            listOf(
                allDay(day(2026, 9, 1), "Tomorrow's thing"),
                allDay(day(2026, 8, 31), "Today's thing"),
            ),
            now, nyc,
        )!!
        assertEquals("Today's thing", pick.title)
        assertTrue(pick.allDay)
        assertEquals(LocalDate.of(2026, 8, 31).atStartOfDay(nyc).toInstant().toEpochMilli(), pick.startAt)
    }

    @Test
    fun `yesterday's all-day thing is gone`() {
        assertNull(NextUp.pick(listOf(allDay(day(2026, 8, 30), "Sunday")), now, nyc))
    }

    @Test
    fun `a ticket is a ticket`() {
        val pick = NextUp.pick(
            listOf(timed(day(2026, 8, 31), 19 * 60 + 30, "Dune Part Two", "ticket")),
            now, nyc,
        )!!
        assertEquals("ticket", pick.kind)
    }

    @Test
    fun `ties break by title, so the answer is stable`() {
        val pick = NextUp.pick(
            listOf(
                timed(day(2026, 8, 31), 15 * 60, "Zebra feeding"),
                timed(day(2026, 8, 31), 15 * 60, "Aquarium"),
            ),
            now, nyc,
        )!!
        assertEquals("Aquarium", pick.title)
    }

    @Test
    fun `nothing at all is null, not a blank row`() {
        assertNull(NextUp.pick(emptyList(), now, nyc))
        assertNull(NextUp.pick(listOf(timed(day(2026, 8, 31), 15 * 60, "  ")), now, nyc))
    }

    @Test
    fun `a start inside the spring-forward gap shifts with the wall clock`() {
        // 2:30am on 8 March 2026 does not exist in New York; ZonedDateTime resolves it to
        // 3:30 EDT, and the pick carries that instant rather than an hour-off guess.
        val beforeDawn = ms(2026, 3, 8, 0, 30)
        val pick = NextUp.pick(
            listOf(timed(day(2026, 3, 8), 2 * 60 + 30, "Impossible half past two")),
            beforeDawn, nyc,
        )!!
        assertEquals(ms(2026, 3, 8, 3, 30), pick.startAt)
    }
}
