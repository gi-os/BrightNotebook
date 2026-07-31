package com.gios.lightnotebook.util

/**
 * What a WMO weather code means, in the two words a day has room for.
 *
 * Open-Meteo reports the WMO 4677 code, which has around thirty values and distinctions a diary does
 * not want — "light drizzle" and "moderate drizzle" are the same afternoon. So they are collapsed to
 * a handful of [Kind]s, and the distinction that *is* kept is the one that matters looking back:
 * whether it rained or snowed. That is the thing you remember about a day.
 *
 * Android-free, so the mapping is checked rather than assumed.
 */
object WeatherCodes {

    enum class Kind { Clear, Cloudy, Fog, Rain, Snow, Storm, Hail }

    /**
     * The code, collapsed.
     *
     * Ranges rather than a table of thirty, because the codes are grouped by design: 5x is drizzle,
     * 6x is rain, 7x is snow, 8x is showers, 9x is thunder. Unknown codes come back cloudy — a wrong
     * "cloudy" is a day that looked ordinary, where a wrong "clear" is a lie about it.
     */
    fun kindOf(code: Int): Kind = when (code) {
        0 -> Kind.Clear
        1, 2, 3 -> Kind.Cloudy
        45, 48 -> Kind.Fog
        51, 53, 55, 56, 57 -> Kind.Rain
        61, 63, 65, 66, 67 -> Kind.Rain
        71, 73, 75, 77 -> Kind.Snow
        80, 81, 82 -> Kind.Rain
        85, 86 -> Kind.Snow
        95 -> Kind.Storm
        96, 99 -> Kind.Hail
        else -> Kind.Cloudy
    }

    /** What a day that has gone says about its weather. */
    fun past(kind: Kind): String = when (kind) {
        Kind.Clear -> "Clear"
        Kind.Cloudy -> "Cloudy"
        Kind.Fog -> "Foggy"
        Kind.Rain -> "It rained"
        Kind.Snow -> "It snowed"
        Kind.Storm -> "There was a storm"
        Kind.Hail -> "It hailed"
    }

    /** And what a day still to come says. */
    fun ahead(kind: Kind): String = when (kind) {
        Kind.Clear -> "Clear"
        Kind.Cloudy -> "Cloudy"
        Kind.Fog -> "Fog"
        Kind.Rain -> "Rain"
        Kind.Snow -> "Snow"
        Kind.Storm -> "Storms"
        Kind.Hail -> "Hail"
    }

    /**
     * Whether a day is worth mentioning at all.
     *
     * Most days are cloudy, and a calendar that writes "Cloudy" on two hundred squares has said
     * nothing on any of them. Rain, snow, storms and hail are what you remember; clear is worth
     * saying in a week of rain and is cheap enough to keep.
     */
    fun notable(kind: Kind): Boolean = kind != Kind.Cloudy

    /** True when it actually fell out of the sky, which is the past's interesting question. */
    fun wet(kind: Kind): Boolean =
        kind == Kind.Rain || kind == Kind.Snow || kind == Kind.Storm || kind == Kind.Hail
}
