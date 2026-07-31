package com.gios.lightnotebook.util

/**
 * Steps, attributed to days.
 *
 * **The step counter has no history, and that shapes everything here.**
 * `TYPE_STEP_COUNTER` reports one number: paces since the phone last booted. There is no query for
 * "steps on the 12th", no Health Connect on this phone and no Play Services to ask, so a past day
 * cannot be reconstructed — it can only have been *watched*. This app therefore samples the counter
 * and keeps the differences, and a day before the day you installed it will always be blank. That
 * is a limit of the hardware interface, not something a better implementation would fix.
 *
 * Android-free: the sensor read is two lines and the arithmetic below is where it goes wrong.
 */
object Steps {

    /**
     * One hour of one day — the finest bucket the sampling can honestly support.
     *
     * [hour] is **hours since local midnight**, not a wall-clock hour, and the difference only shows
     * up twice a year: a spring-forward day has 23 of them and a fall-back day 25. Counting slices
     * from the day's own start means the buckets always tile the day exactly, which a wall-clock
     * hour cannot do on a day that has no 2am.
     */
    data class Hour(val epochDay: Long, val hour: Int)

    /** Steps to add, worked out from one pair of samples. */
    data class Attribution(val perHour: Map<Hour, Int>) {
        /** Days derive from hours rather than being counted separately, so they cannot disagree. */
        val perDay: Map<Long, Int>
            get() = perHour.entries.groupBy { it.key.epochDay }
                .mapValues { (_, entries) -> entries.sumOf { it.value } }

        companion object {
            val NONE = Attribution(emptyMap())
        }
    }

    /**
     * The steps between two samples, spread over the hours they span.
     *
     * **Hours, not days, because a day total cannot answer the interesting question.** "Eight
     * thousand steps" says nothing about the day; "two thousand of them between two and three"
     * says you went for a walk. Days are then derived by summing, so the two can never disagree.
     *
     * The spread is proportional to elapsed time, which is a guess and a defensible one: the
     * alternatives are worse. Putting an overnight difference entirely on the morning invents a
     * thousand steps at 7am, and putting it all on the previous evening credits you for walking
     * after you went to bed. Sampling more often makes the guess smaller, which is why the app
     * samples on every open as well as from the daily alarm.
     *
     * Three things it has to get right:
     *
     * 1. **A reboot resets the counter to zero.** The value going *down* is the only signal there
     *    is — a sensor reading carries no boot id — so a decrease means the difference is the new
     *    reading itself, not a negative number. Unhandled, this subtracts a day's walking and the
     *    total goes backwards an hour after you looked at it.
     * 2. **A very old previous sample means nothing.** After days away from the phone the
     *    difference cannot be spread with any honesty, so it is dropped. A missing number is better
     *    than a fabricated one.
     * 3. **A plainly impossible number is a fault, not a marathon.**
     */
    fun attribute(
        previousCounter: Long,
        previousAtMs: Long,
        counter: Long,
        atMs: Long,
        /** Start of an hour in milliseconds. Built from a real zone by the caller. */
        hourStartMs: (Hour) -> Long,
        /** The hour an instant falls in, locally. */
        hourOf: (Long) -> Hour,
        /** The hour after this one, so a DST day's missing or repeated hour is the zone's problem. */
        nextHour: (Hour) -> Hour,
        maxGapMs: Long = MAX_GAP_MS,
    ): Attribution {
        if (atMs <= previousAtMs) return Attribution.NONE
        if (atMs - previousAtMs > maxGapMs) return Attribution.NONE

        val delta = if (counter >= previousCounter) counter - previousCounter else counter
        if (delta <= 0L) return Attribution.NONE
        if (delta > MAX_PLAUSIBLE_STEPS) return Attribution.NONE

        val startHour = hourOf(previousAtMs)
        val endHour = hourOf(atMs)
        if (startHour == endHour) return Attribution(mapOf(startHour to delta.toInt()))

        val elapsed = (atMs - previousAtMs).toDouble()
        val out = LinkedHashMap<Hour, Int>()
        var assigned = 0
        var hour = startHour
        // Walked forward rather than computed, so the zone decides what "the next hour" is on the
        // mornings that have twenty-three or twenty-five of them. Bounded in case a callback lies.
        var guard = 0
        while (guard++ < MAX_HOURS_SPANNED) {
            val from = maxOf(hourStartMs(hour), previousAtMs)
            val to = minOf(hourStartMs(nextHour(hour)), atMs)
            if (to > from) {
                val share = ((to - from) / elapsed * delta).toInt()
                if (share > 0) {
                    out[hour] = share
                    assigned += share
                }
            }
            if (hour == endHour) break
            hour = nextHour(hour)
        }

        // Rounding loses a step or two; the remainder goes to the hour the walking ended in.
        val remainder = delta.toInt() - assigned
        if (remainder > 0) out[endHour] = (out[endHour] ?: 0) + remainder
        return Attribution(out)
    }

    /** A sample pair cannot span more hours than [MAX_GAP_MS] allows, plus slack for DST. */
    private const val MAX_HOURS_SPANNED = 40

    /**
     * Longer than this between samples and the difference is not attributable.
     *
     * Thirty-six hours covers a normal night plus a day the app was never opened, and stops short
     * of a week's worth of walking being smeared across days that may have had none of it.
     */
    const val MAX_GAP_MS = 36L * 60L * 60L * 1000L

    /** Two hundred thousand paces between two samples is a fault, whoever you are. */
    const val MAX_PLAUSIBLE_STEPS = 200_000L

    /** "8,412" — a five-digit number with no separator is unreadable at this size. */
    fun format(steps: Int): String = steps.toString().reversed().chunked(3).joinToString(",").reversed()
}
