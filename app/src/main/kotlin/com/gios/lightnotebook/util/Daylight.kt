package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * When it got light and when it got dark, computed rather than fetched.
 *
 * The whole point: this needs **no network and no model**, only a date and a place. Sunrise is
 * arithmetic that has been settled since the 1970s, so a day in a journal can say "dark by 16:32"
 * for any date in either direction, offline, on a phone in a drawer.
 *
 * This is the NOAA sunrise/sunset algorithm. It is accurate to about a minute at temperate
 * latitudes, which is far better than a diary needs and is the reason not to reach for a library.
 * Free of Android imports, and every value below is checked against an independent implementation
 * rather than against itself.
 */
object Daylight {

    /**
     * The official zenith for sunrise: 90°50', not 90°.
     *
     * The extra 50 minutes of arc is the sun's own radius plus atmospheric refraction — the disc's
     * upper edge clears the horizon while its centre is still below it, and the air bends the
     * light over besides. Using a flat 90° puts every sunrise several minutes late, which is
     * exactly the kind of quietly wrong that never gets noticed.
     */
    private const val ZENITH = 90.833

    sealed interface Result {
        /** Minutes from local midnight. */
        data class Times(val sunriseMinutes: Int, val sunsetMinutes: Int) : Result {
            val daylightMinutes: Int get() = sunsetMinutes - sunriseMinutes
        }

        /** Above the Arctic circle in summer: the sun does not set. */
        data object AlwaysDay : Result

        /** And in winter it does not rise. */
        data object AlwaysNight : Result
    }

    /**
     * Sunrise and sunset for a local day, in minutes from that day's midnight.
     *
     * The zone matters twice and it is easy to only notice once: it converts the algorithm's UTC
     * answer to a local clock time, **and** it decides how long the day is. On a spring-forward
     * morning local midnight is an hour closer to noon than usual, so the offset has to be taken
     * for that day rather than assumed.
     */
    fun of(epochDay: Long, latitude: Double, longitude: Double, zone: ZoneId): Result {
        val date = LocalDate.ofEpochDay(epochDay)
        val rise = event(date, latitude, longitude, rising = true)
        val set = event(date, latitude, longitude, rising = false)

        // Both fail together: whichever way cosH went out of range, there is no crossing today.
        if (rise == null || set == null) {
            return if (polarDay(date, latitude)) Result.AlwaysDay else Result.AlwaysNight
        }

        return Result.Times(
            sunriseMinutes = localMinutes(date, rise, zone),
            sunsetMinutes = localMinutes(date, set, zone),
        )
    }

    /** Hours UTC of one crossing, or null when the sun does not cross the horizon that day. */
    private fun event(date: LocalDate, latitude: Double, longitude: Double, rising: Boolean): Double? {
        val dayOfYear = date.dayOfYear
        val lngHour = longitude / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0

        // The sun's mean anomaly, then its true longitude.
        val m = (0.9856 * t) - 3.289
        var l = m + (1.916 * sin(rad(m))) + (0.020 * sin(rad(2 * m))) + 282.634
        l = wrap(l, 360.0)

        // Right ascension, put in the same quadrant as L — the arctangent loses that, and a
        // quadrant error moves sunrise by six hours rather than by a few minutes.
        var ra = wrap(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra = (ra + (floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0)) / 15.0

        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - (sinDec * sin(rad(latitude)))) / (cosDec * cos(rad(latitude)))
        if (cosH > 1.0 || cosH < -1.0) return null

        val h = (if (rising) 360.0 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        return wrap(h + ra - (0.06571 * t) - 6.622 - lngHour, 24.0)
    }

    /**
     * Whether a horizon-less day is a lit one.
     *
     * Decided from the declination against the latitude rather than from which way `cosH` went,
     * because the rise and set calculations use slightly different times of day and can disagree
     * at the exact boundary. Summer in the hemisphere you are in means the sun is up.
     */
    private fun polarDay(date: LocalDate, latitude: Double): Boolean {
        val declination = 23.44 * sin(rad(360.0 / 365.0 * (date.dayOfYear - 81)))
        return (latitude >= 0) == (declination >= 0)
    }

    /**
     * A UTC hour on [date] as minutes from local midnight.
     *
     * **The UTC hour alone does not say which UTC day the event is on**, and that is the trap
     * here. The algorithm returns a value wrapped into 0..24, so a New York sunset at 20:14 EDT
     * comes back as 00:14 — which belongs to *tomorrow* in UTC. Placing it on today's UTC date
     * converts it to 20:14 the previous evening, i.e. a negative offset from this day's midnight.
     * That was a real bug, and the clamp below hid it as a sunset at 00:00 until a test walked
     * every day of a year.
     *
     * So all three candidate UTC dates are tried and the one that lands on the wanted local date
     * wins. Built by subtracting local midnight rather than by adding a fixed offset, so the zone's
     * own rules decide — including the hour a DST day loses.
     */
    private fun localMinutes(date: LocalDate, utcHours: Double, zone: ZoneId): Int {
        val midnight = date.atStartOfDay(zone).toEpochSecond()
        val utcMinutes = (utcHours * 60.0).toLong()

        var fallback = 0
        for (dayShift in longArrayOf(0L, -1L, 1L)) {
            val local = date.plusDays(dayShift)
                .atStartOfDay(UTC)
                .plusMinutes(utcMinutes)
                .withZoneSameInstant(zone)
            val minutes = ((local.toEpochSecond() - midnight) / 60L).toInt()
            if (local.toLocalDate() == date) return minutes.coerceIn(0, MINUTES_IN_DAY - 1)
            // Kept in case none of the three lands on the day — near the date line the local date
            // can differ while the time of day is still the one to show.
            if (dayShift == 0L) fallback = minutes
        }
        return fallback.coerceIn(0, MINUTES_IN_DAY - 1)
    }

    private val UTC: ZoneId = ZoneId.of("UTC")
    private const val MINUTES_IN_DAY = 24 * 60

    private fun rad(degrees: Double) = degrees * Math.PI / 180.0
    private fun deg(radians: Double) = radians * 180.0 / Math.PI

    /** Positive modulo — Kotlin's `%` keeps the sign of the dividend, which breaks both wraps. */
    private fun wrap(value: Double, span: Double): Double {
        val m = value % span
        return if (m < 0) m + span else m
    }

    /** Somewhere to start before a location is known. Manhattan. */
    const val DEFAULT_LATITUDE = 40.7128
    const val DEFAULT_LONGITUDE = -74.0060

    fun validLatitude(value: Double) = abs(value) <= 90.0
    fun validLongitude(value: Double) = abs(value) <= 180.0
}
