package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here came from an **independent** implementation of the NOAA algorithm,
 * written separately and run outside this project, not from this code's own output. A test that
 * asserts a function still does what it did yesterday would pass just as happily if the whole
 * thing were six hours out.
 */
class DaylightTest {

    private val nyc = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val tromso = ZoneId.of("Europe/Oslo")

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private fun times(epochDay: Long, lat: Double, lon: Double, zone: ZoneId) =
        Daylight.of(epochDay, lat, lon, zone) as Daylight.Result.Times

    /** Within a minute is far better than a diary needs; two is the tolerance. */
    private fun assertNear(expected: Int, actual: Int, what: String) {
        assertTrue("$what: expected ~$expected, was $actual", kotlin.math.abs(expected - actual) <= 2)
    }

    @Test
    fun `midsummer in New York`() {
        val t = times(day(2026, 7, 30), 40.7128, -74.0060, nyc)
        assertNear(350, t.sunriseMinutes, "sunrise")   // 05:50 EDT
        assertNear(1214, t.sunsetMinutes, "sunset")    // 20:14 EDT
    }

    @Test
    fun `the shortest day in New York`() {
        val t = times(day(2026, 12, 21), 40.7128, -74.0060, nyc)
        assertNear(436, t.sunriseMinutes, "sunrise")   // 07:16 EST
        assertNear(992, t.sunsetMinutes, "sunset")     // 16:32 EST
        // Nine and a bit hours of light, which is the number a December day in a journal is for.
        assertTrue(t.daylightMinutes in 540..570)
    }

    @Test
    fun `a spring-forward day is read in the offset that day actually had`() {
        // 2026-03-08 loses an hour at 2am. Local midnight is an hour closer to noon than usual,
        // so a fixed offset applied to the UTC answer puts both events an hour out.
        val t = times(day(2026, 3, 8), 40.7128, -74.0060, nyc)
        assertNear(380, t.sunriseMinutes, "sunrise")   // 06:20 EST
        assertNear(1075, t.sunsetMinutes, "sunset")    // 17:55 EDT
    }

    @Test
    fun `east of Greenwich, where the longitude sign is easy to get backwards`() {
        val t = times(day(2026, 7, 30), 35.6762, 139.6503, tokyo)
        assertNear(287, t.sunriseMinutes, "sunrise")   // 04:47 JST
        assertNear(1128, t.sunsetMinutes, "sunset")    // 18:48 JST
    }

    @Test
    fun `the sun does not set in Tromso in June`() {
        val result = Daylight.of(day(2026, 6, 21), 69.6492, 18.9553, tromso)
        assertEquals(Daylight.Result.AlwaysDay, result)
    }

    @Test
    fun `and does not rise there in December`() {
        val result = Daylight.of(day(2026, 12, 21), 69.6492, 18.9553, tromso)
        assertEquals(Daylight.Result.AlwaysNight, result)
    }

    @Test
    fun `the southern hemisphere has its polar seasons the other way round`() {
        // The same test as above with the sign flipped — this is what catches a `polarDay` that
        // hardcoded "June is summer".
        val antarctic = ZoneId.of("Antarctica/McMurdo")
        assertEquals(
            Daylight.Result.AlwaysNight,
            Daylight.of(day(2026, 6, 21), -77.8463, 166.6683, antarctic),
        )
        assertEquals(
            Daylight.Result.AlwaysDay,
            Daylight.of(day(2026, 12, 21), -77.8463, 166.6683, antarctic),
        )
    }

    @Test
    fun `sunrise comes before sunset, every day of a year`() {
        // A quadrant error in the right ascension moves an event by six hours rather than by
        // minutes, and would show up here on some fraction of the year rather than on one date.
        var d = day(2026, 1, 1)
        val end = day(2026, 12, 31)
        while (d <= end) {
            val t = times(d, 40.7128, -74.0060, nyc)
            assertTrue(
                "on ${LocalDate.ofEpochDay(d)}: rise ${t.sunriseMinutes} set ${t.sunsetMinutes}",
                t.sunriseMinutes < t.sunsetMinutes,
            )
            assertTrue(t.daylightMinutes in 480..960)
            d++
        }
    }

    @Test
    fun `days lengthen towards midsummer and shorten after it`() {
        val march = times(day(2026, 3, 20), 40.7128, -74.0060, nyc).daylightMinutes
        val june = times(day(2026, 6, 21), 40.7128, -74.0060, nyc).daylightMinutes
        val september = times(day(2026, 9, 22), 40.7128, -74.0060, nyc).daylightMinutes
        assertTrue(march < june)
        assertTrue(september < june)
        // The equinoxes are twelve hours give or take a few minutes, on either side of the year.
        assertTrue(kotlin.math.abs(march - september) < 20)
    }

    @Test
    fun `coordinates are sanity-checked`() {
        assertTrue(Daylight.validLatitude(Daylight.DEFAULT_LATITUDE))
        assertTrue(Daylight.validLongitude(Daylight.DEFAULT_LONGITUDE))
        assertTrue(!Daylight.validLatitude(91.0))
        assertTrue(!Daylight.validLongitude(-181.0))
    }
}
