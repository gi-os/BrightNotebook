package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherCodesTest {

    @Test
    fun `the distinction a diary cares about is rain versus snow`() {
        assertEquals(WeatherCodes.Kind.Rain, WeatherCodes.kindOf(63))
        assertEquals(WeatherCodes.Kind.Snow, WeatherCodes.kindOf(73))
        assertEquals(WeatherCodes.Kind.Snow, WeatherCodes.kindOf(85))
    }

    @Test
    fun `drizzle and rain are the same afternoon`() {
        // Thirty codes' worth of intensity is not a thing a day has room to say.
        listOf(51, 53, 55, 61, 63, 65, 80, 81, 82).forEach {
            assertEquals("code $it", WeatherCodes.Kind.Rain, WeatherCodes.kindOf(it))
        }
    }

    @Test
    fun `clear is clear and everything cloudy is cloudy`() {
        assertEquals(WeatherCodes.Kind.Clear, WeatherCodes.kindOf(0))
        listOf(1, 2, 3).forEach { assertEquals(WeatherCodes.Kind.Cloudy, WeatherCodes.kindOf(it)) }
    }

    @Test
    fun `fog, storms and hail keep their own names`() {
        assertEquals(WeatherCodes.Kind.Fog, WeatherCodes.kindOf(45))
        assertEquals(WeatherCodes.Kind.Storm, WeatherCodes.kindOf(95))
        assertEquals(WeatherCodes.Kind.Hail, WeatherCodes.kindOf(99))
    }

    @Test
    fun `an unknown code is cloudy, never clear`() {
        // A wrong "cloudy" is a day that looked ordinary. A wrong "clear" is a lie about it.
        assertEquals(WeatherCodes.Kind.Cloudy, WeatherCodes.kindOf(-1))
        assertEquals(WeatherCodes.Kind.Cloudy, WeatherCodes.kindOf(4))
        assertEquals(WeatherCodes.Kind.Cloudy, WeatherCodes.kindOf(999))
    }

    @Test
    fun `every code in the WMO range resolves to something`() {
        for (code in 0..99) {
            // No exception, no null: the mapping is total.
            WeatherCodes.kindOf(code)
        }
    }

    @Test
    fun `a past day says it happened and a future day says it might`() {
        assertEquals("It rained", WeatherCodes.past(WeatherCodes.Kind.Rain))
        assertEquals("Rain", WeatherCodes.ahead(WeatherCodes.Kind.Rain))
        assertEquals("It snowed", WeatherCodes.past(WeatherCodes.Kind.Snow))
    }

    @Test
    fun `cloudy is not worth writing on two hundred squares`() {
        assertFalse(WeatherCodes.notable(WeatherCodes.Kind.Cloudy))
        assertTrue(WeatherCodes.notable(WeatherCodes.Kind.Rain))
        assertTrue(WeatherCodes.notable(WeatherCodes.Kind.Clear))
    }

    @Test
    fun `wet means it actually fell out of the sky`() {
        assertTrue(WeatherCodes.wet(WeatherCodes.Kind.Rain))
        assertTrue(WeatherCodes.wet(WeatherCodes.Kind.Snow))
        assertTrue(WeatherCodes.wet(WeatherCodes.Kind.Hail))
        assertFalse(WeatherCodes.wet(WeatherCodes.Kind.Fog))
        assertFalse(WeatherCodes.wet(WeatherCodes.Kind.Clear))
    }
}
