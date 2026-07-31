package com.gios.lightnotebook.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnThisDayTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test
    fun `the same date in each previous year, nearest first`() {
        val past = OnThisDay.priorYears(day(2026, 7, 30), yearsBack = 3)
        assertEquals(listOf(2025, 2024, 2023), past.map { it.year })
        assertEquals(listOf(1, 2, 3), past.map { it.yearsAgo })
        past.forEach { p ->
            val date = LocalDate.ofEpochDay(p.epochDay)
            assertEquals(7, date.monthValue)
            assertEquals(30, date.dayOfMonth)
        }
    }

    @Test
    fun `a date after a leap day does not drift`() {
        // The reason this is not epochDay minus 365 times n. Across a leap year that subtraction
        // lands on the 29th of July instead of the 30th, and the "same date" is quietly wrong.
        val past = OnThisDay.priorYears(day(2026, 7, 30), yearsBack = 6)
        past.forEach { p ->
            assertEquals(30, LocalDate.ofEpochDay(p.epochDay).dayOfMonth)
            assertEquals(7, LocalDate.ofEpochDay(p.epochDay).monthValue)
        }
    }

    @Test
    fun `a leap day has no anniversary in a year without one`() {
        // minusYears would return 28 February and present it as the same date. Showing the 28th's
        // photographs on the 29th is worse than showing none.
        val past = OnThisDay.priorYears(day(2028, 2, 29), yearsBack = 8)
        past.forEach { p ->
            val date = LocalDate.ofEpochDay(p.epochDay)
            assertEquals(29, date.dayOfMonth)
            assertEquals(2, date.monthValue)
            assertTrue(date.isLeapYear)
        }
        // 2024, 2020: the only leap years in the eight before 2028.
        assertEquals(listOf(2024, 2020), past.map { it.year })
    }

    @Test
    fun `the first of March is unaffected by any of that`() {
        val past = OnThisDay.priorYears(day(2028, 3, 1), yearsBack = 4)
        assertEquals(4, past.size)
        past.forEach { assertEquals(1, LocalDate.ofEpochDay(it.epochDay).dayOfMonth) }
    }

    @Test
    fun `no years back is no years`() {
        assertTrue(OnThisDay.priorYears(day(2026, 7, 30), yearsBack = 0).isEmpty())
    }

    @Test
    fun `the label reads properly for one year`() {
        assertEquals("1 year ago", OnThisDay.label(1))
        assertEquals("4 years ago", OnThisDay.label(4))
    }
}
