package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {

    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toEpochDay()

    private fun dateOf(year: Int, id: String, observed: Boolean = false): LocalDate =
        Holidays.ofYear(year).first { it.id == id && it.observed == observed }
            .let { LocalDate.ofEpochDay(it.epochDay) }

    @Test
    fun `the fixed dates are where the calendar says`() {
        assertEquals(LocalDate.of(2026, 1, 1), dateOf(2026, Holidays.NEW_YEAR))
        assertEquals(LocalDate.of(2026, 6, 19), dateOf(2026, Holidays.JUNETEENTH))
        assertEquals(LocalDate.of(2026, 7, 4), dateOf(2026, Holidays.INDEPENDENCE))
        assertEquals(LocalDate.of(2026, 11, 11), dateOf(2026, Holidays.VETERANS))
        assertEquals(LocalDate.of(2026, 12, 25), dateOf(2026, Holidays.CHRISTMAS))
    }

    @Test
    fun `the nth-weekday rules land on the right monday`() {
        // Checked against 2026: MLK 19 Jan, Presidents' 16 Feb, Memorial 25 May,
        // Labor 7 Sep, Columbus 12 Oct, Thanksgiving 26 Nov.
        assertEquals(LocalDate.of(2026, 1, 19), dateOf(2026, Holidays.MLK))
        assertEquals(LocalDate.of(2026, 2, 16), dateOf(2026, Holidays.PRESIDENTS))
        assertEquals(LocalDate.of(2026, 5, 25), dateOf(2026, Holidays.MEMORIAL))
        assertEquals(LocalDate.of(2026, 9, 7), dateOf(2026, Holidays.LABOR))
        assertEquals(LocalDate.of(2026, 10, 12), dateOf(2026, Holidays.COLUMBUS))
        assertEquals(LocalDate.of(2026, 11, 26), dateOf(2026, Holidays.THANKSGIVING))
    }

    @Test
    fun `and on the right monday in a year that starts differently`() {
        // 2027: MLK 18 Jan, Memorial 31 May (a fifth-Monday May), Thanksgiving 25 Nov.
        assertEquals(LocalDate.of(2027, 1, 18), dateOf(2027, Holidays.MLK))
        assertEquals(LocalDate.of(2027, 5, 31), dateOf(2027, Holidays.MEMORIAL))
        assertEquals(LocalDate.of(2027, 11, 25), dateOf(2027, Holidays.THANKSGIVING))
    }

    @Test
    fun `a saturday holiday is observed on the friday before`() {
        // 4 July 2026 is a Saturday, so the day off is Friday the 3rd.
        assertEquals(LocalDate.of(2026, 7, 3), dateOf(2026, Holidays.INDEPENDENCE, observed = true))
    }

    @Test
    fun `a sunday holiday is observed on the monday after`() {
        // Christmas 2027 falls on a Saturday; New Year's Day 2028 lands mid-weekend too.
        // 25 December 2022 was a Sunday — observed Monday the 26th.
        assertEquals(LocalDate.of(2022, 12, 26), dateOf(2022, Holidays.CHRISTMAS, observed = true))
    }

    @Test
    fun `a weekday holiday gets no second entry`() {
        // 25 December 2026 is a Friday, so there is nothing to move.
        val christmases = Holidays.ofYear(2026).filter { it.id == Holidays.CHRISTMAS }
        assertEquals(1, christmases.size)
        assertTrue(christmases.none { it.observed })
    }

    @Test
    fun `juneteenth does not exist before it was a holiday`() {
        assertTrue(Holidays.ofYear(2019).none { it.id == Holidays.JUNETEENTH })
        assertTrue(Holidays.ofYear(2021).any { it.id == Holidays.JUNETEENTH })
    }

    @Test
    fun `a range returns only what falls inside it, in order`() {
        val range = Holidays.inRange(day(2026, 11, 1), day(2026, 12, 31))
        assertEquals(
            listOf(Holidays.VETERANS, Holidays.THANKSGIVING, Holidays.CHRISTMAS),
            range.map { it.id },
        )
        assertTrue(range.zipWithNext().all { (a, b) -> a.epochDay <= b.epochDay })
    }

    @Test
    fun `a range that straddles new year sees both years`() {
        // The window the month grid asks for routinely crosses December into January, and
        // computing only the years the endpoints fall in used to drop one of these.
        val ids = Holidays.inRange(day(2026, 12, 20), day(2027, 1, 20)).map { it.id }
        assertTrue(ids.contains(Holidays.CHRISTMAS))
        assertTrue(ids.contains(Holidays.NEW_YEAR))
        assertTrue(ids.contains(Holidays.MLK))
    }

    @Test
    fun `an observed date in the neighbouring year is still found`() {
        // 1 January 2028 is a Saturday, so its day off is Friday 31 December 2027 — which
        // belongs to a different year than the holiday that produced it.
        val ids = Holidays.inRange(day(2027, 12, 31), day(2027, 12, 31))
        assertEquals(listOf(Holidays.NEW_YEAR), ids.map { it.id })
        assertTrue(ids.single().observed)
        assertEquals("New Year's Day (observed)", ids.single().label)
    }

    @Test
    fun `one day asks for one holiday and prefers the real date`() {
        assertEquals(Holidays.INDEPENDENCE, Holidays.on(day(2026, 7, 4))?.id)
        assertEquals(false, Holidays.on(day(2026, 7, 4))?.observed)
        assertEquals(true, Holidays.on(day(2026, 7, 3))?.observed)
        assertNull(Holidays.on(day(2026, 7, 6)))
    }

    @Test
    fun `an inverted range is empty rather than a mistake`() {
        assertEquals(emptyList<Holidays.Holiday>(), Holidays.inRange(day(2026, 7, 4), day(2026, 7, 1)))
    }

    @Test
    fun `every holiday has a name that is not its id`() {
        Holidays.ofYear(2026).forEach { assertTrue(it.name != it.id && it.name.isNotBlank()) }
    }
}
