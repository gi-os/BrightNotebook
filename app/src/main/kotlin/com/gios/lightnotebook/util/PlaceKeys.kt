package com.gios.lightnotebook.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How a coordinate becomes a cache key.
 *
 * The reason the place lookup is affordable at all. You go to the same handful of places over and
 * over, and two visits to the same cafe are two coordinates that differ by a few metres — so
 * rounding them to a grid makes the second visit a cache hit and the whole thing decays to nearly no
 * requests after the first week. Without this, every stay is a fresh lookup of somewhere you already
 * had a name for.
 *
 * Android-free, because the only thing that can go wrong is the arithmetic.
 */
object PlaceKeys {

    /**
     * Decimal places kept: three, which is about 110 metres of latitude.
     *
     * Chosen against the clustering radius rather than picked: a stay is already everything within
     * eighty metres of a point, so a grid finer than that would split one cafe across several keys
     * and ask again for each. Coarser and two genuinely different shops on the same block share an
     * answer.
     */
    const val PLACES = 3

    /** `40.713,-74.006` — the key itself, and stable regardless of platform formatting. */
    fun of(latitude: Double, longitude: Double): String =
        "${round(latitude)}:${round(longitude)}"

    private fun round(value: Double): Long {
        val scale = Math.pow(10.0, PLACES.toDouble())
        return (value * scale).roundToLong()
    }

    /**
     * Whether two coordinates are close enough to be the same place.
     *
     * Used to reuse a name across a session without going back to disk. Degrees rather than metres
     * on purpose: this is a cheap guard in front of a cache, not a distance, and converting to metres
     * would need a cosine for no benefit at this tolerance.
     */
    fun sameSpot(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val tolerance = Math.pow(10.0, -PLACES.toDouble())
        return abs(lat1 - lat2) <= tolerance && abs(lon1 - lon2) <= tolerance
    }
}
