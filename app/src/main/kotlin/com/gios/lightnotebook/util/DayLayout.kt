package com.gios.lightnotebook.util

import kotlin.math.sqrt

/**
 * How much room the empty parts of a day take up.
 *
 * A day is not a list. If you photographed something at eight in the morning and the next thing
 * happened at two, those two things did not happen next to each other, and a screen that stacks them
 * as adjacent rows is telling a lie about the day. So the space between two moments is *drawn*.
 *
 * **But not to scale.** Six hours at true scale is roughly six screens of nothing to scroll past, and
 * the empty parts of a day are the least interesting parts of it. The rule Gio set is the right one:
 * time when things happened should take far more room than time when nothing did. So a gap is
 * compressed hard — it stays *comparable* (a long gap is visibly longer than a short one, always)
 * without ever becoming a journey.
 *
 * Android-free, so the curve can be reasoned about and tested rather than eyeballed on a phone.
 */
object DayLayout {

    /**
     * Below this, two things are the same moment and get no gap at all.
     *
     * A photograph and the note you wrote about it are ten seconds apart. Drawing five pixels of
     * "time passing" between them adds nothing and breaks the pair up.
     */
    const val SAME_MOMENT_MINUTES = 6

    /** The smallest visible gap, in vertical grid units. Enough to read as a pause. */
    const val MIN_UNITS = 0.8f

    /** The largest, however long the wait. Two thirds of a screen is already a long silence. */
    const val MAX_UNITS = 9f

    /**
     * The gap between two moments, in vertical grid units.
     *
     * **Square root, not linear.** Linear makes a night's sleep two hundred times the height of a
     * ten-minute pause, which is unusable; a logarithm flattens the middle of the day, where most of
     * the interesting differences are. A square root keeps an hour visibly longer than ten minutes
     * *and* keeps six hours from running off the screen: it spreads the short gaps, which are the
     * ones you can tell apart, and compresses the long ones, which all just mean "later".
     */
    fun gapUnits(gapMinutes: Int, dayMinutes: Int = JournalDay.NOMINAL_MINUTES): Float {
        if (gapMinutes <= SAME_MOMENT_MINUTES) return 0f
        val span = dayMinutes.coerceAtLeast(1)
        val fraction = (gapMinutes.toFloat() / span).coerceIn(0f, 1f)
        return (MIN_UNITS + sqrt(fraction) * (MAX_UNITS - MIN_UNITS)).coerceAtMost(MAX_UNITS)
    }

    /**
     * The gaps between a day's moments, one per adjacent pair, in the same order.
     *
     * Returns `size - 1` values: a gap belongs *between* two things, so there is no leading or
     * trailing one. The day's own edges are said in words instead — "started the day at 07:12" —
     * which reads better than eight units of blank above the first row.
     *
     * All-day things carry no time and so cannot be spaced. They sort to the top and are treated as
     * the day's heading; the first *timed* moment is where the clock starts.
     */
    fun gaps(minutes: List<Int?>, dayMinutes: Int = JournalDay.NOMINAL_MINUTES): List<Float> {
        if (minutes.size < 2) return emptyList()
        return (1 until minutes.size).map { index ->
            val previous = minutes[index - 1]
            val current = minutes[index]
            if (previous == null || current == null) {
                // One of them is an all-day thing. No duration between them to draw.
                0f
            } else {
                gapUnits((current - previous).coerceAtLeast(0), dayMinutes)
            }
        }
    }

    /**
     * Whether a stretch is long enough to say how long it was.
     *
     * A gap that is merely drawn says "some time passed". Past about an hour it is worth naming —
     * "4h" in the middle of the space — because at this compression an hour and five hours look
     * more alike than they are, and the number is what stops the compression from lying.
     */
    fun labelFor(gapMinutes: Int): String? {
        if (gapMinutes < LABEL_FROM_MINUTES) return null
        val hours = gapMinutes / 60
        val minutes = gapMinutes % 60
        return when {
            hours == 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    const val LABEL_FROM_MINUTES = 60

    /**
     * The hour boundaries a gap passes through, as minutes into the journal day.
     *
     * So a long stretch of nothing can say which hours went by rather than only "later". They are
     * **not** evenly spaced in real time once drawn — the page is compressed and deliberately so —
     * but they are the real boundaries, in order, and that is enough to place yourself.
     *
     * Capped, because a gap of nine hours would otherwise list nine labels down a space that is a
     * few lines tall. Past the cap it thins them out, keeping the first and last so the ends of the
     * stretch are still named.
     */
    fun hoursCrossed(fromMinutes: Int, gapMinutes: Int, max: Int = MAX_HOUR_MARKS): List<Int> {
        if (gapMinutes < LABEL_FROM_MINUTES || max <= 0) return emptyList()
        val end = fromMinutes + gapMinutes
        val firstHour = (fromMinutes / 60 + 1) * 60
        val all = generateSequence(firstHour) { it + 60 }.takeWhile { it < end }.toList()
        if (all.size <= max) return all
        // Thinned evenly, first and last kept.
        val step = (all.size - 1).toFloat() / (max - 1)
        return (0 until max).map { all[(it * step).toInt().coerceIn(all.indices)] }.distinct()
    }

    /** "5 AM" — an hour of the day, from minutes into the journal day. */
    fun hourLabel(minutesIntoDay: Int): String {
        val clock = JournalDay.clockMinutes(minutesIntoDay)
        val hour24 = clock / 60
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return "$hour12${if (hour24 < 12) "AM" else "PM"}"
    }

    /** Four marks is enough to place yourself; more turns a gap into a ruler. */
    const val MAX_HOUR_MARKS = 4
}
