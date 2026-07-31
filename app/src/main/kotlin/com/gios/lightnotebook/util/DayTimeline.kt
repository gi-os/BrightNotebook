package com.gios.lightnotebook.util

/**
 * A day as one column of things, in the order they happened or will.
 *
 * **There is no diary mode and no calendar mode.** There is a *now line*, and everything is
 * decided by which side of it a thing falls on: a day in the past is entirely behind it and
 * reads as a diary, a day ahead is entirely in front and reads as a plan, and today is both
 * with the line sitting wherever the clock is. Written this way because the alternative — an
 * enum with three cases — has to answer "what does today do" three times over, and gets it
 * subtly different in each place.
 *
 * Android-free so the ordering, the clustering and the split are testable off-device, which is
 * where the fiddly parts are.
 */
object DayTimeline {

    /**
     * How close two photographs have to be to count as one moment.
     *
     * Photographs come in bursts — you take eleven of the same thing and keep one. Eleven
     * full-width pictures is eleven screens of scrolling for one moment of a day, and it
     * would push everything written that day out of reach. Twenty minutes is long enough to
     * hold a meal or a walk together and short enough that the morning and the evening stay
     * separate entries in the day.
     */
    const val CLUSTER_GAP_MINUTES = 20

    /** A photograph, reduced to the two things this file needs to know about it. */
    data class PhotoAt(val id: Long, val minutes: Int)

    sealed interface Item {
        /** Null means all day — no time was ever given to it. */
        val minutes: Int?

        /** Whether this has already happened, and so belongs to the diary half of the day. */
        val behind: Boolean

        data class Entry(val row: AgendaRow, override val behind: Boolean) : Item {
            override val minutes: Int? get() = row.minutes
        }

        /**
         * One moment, holding one photograph or a burst of them.
         *
         * A single photograph is drawn full width, the way a picture in a diary is. A burst is
         * drawn as a row of thumbnails, which is what keeps a heavy day bounded: the strip this
         * replaced was bounded by construction, and full-width pictures gave that up.
         */
        data class Photos(
            val photos: List<PhotoAt>,
            override val minutes: Int,
            /** The last photograph's time, when the burst spans one. */
            val untilMinutes: Int,
        ) : Item {
            override val behind: Boolean get() = true
            val single: Boolean get() = photos.size == 1
        }
    }

    /**
     * The line between what has happened and what has not, in minutes from midnight, or null
     * when the whole day is on one side of it.
     *
     * Only today has a line *through* it. A past day is entirely behind and a future day
     * entirely ahead, and in both cases there is nothing to draw.
     */
    fun nowLine(epochDay: Long, today: Long, nowMinutes: Int): Int? =
        if (epochDay == today) nowMinutes.coerceIn(0, MINUTES_IN_DAY) else null

    /**
     * Whether something on [epochDay] at [minutes] has already happened.
     *
     * An all-day thing on today counts as **ahead**: it has no time to have passed, and it is
     * still today's business. On a day that has gone it is behind along with everything else.
     */
    fun behind(epochDay: Long, minutes: Int?, today: Long, nowMinutes: Int): Boolean = when {
        epochDay < today -> true
        epochDay > today -> false
        minutes == null -> false
        else -> minutes <= nowMinutes
    }

    /**
     * The day, in order.
     *
     * All-day things first — they are the day's heading, not an event at midnight — then
     * everything with a time, earliest first. Photographs are clustered before they are sorted
     * in, so a burst takes one place in the order rather than eleven.
     *
     * A photograph is **always** behind, whatever its timestamp says. You cannot photograph the
     * future, and a camera whose clock has drifted a few minutes forward would otherwise put a
     * picture you are looking at on the wrong side of the line.
     */
    fun build(
        rows: List<AgendaRow>,
        photos: List<PhotoAt>,
        epochDay: Long,
        today: Long,
        nowMinutes: Int,
    ): List<Item> {
        val entries = rows.map { Item.Entry(it, behind(epochDay, it.minutes, today, nowMinutes)) }
        val clustered = cluster(photos)

        // Sorted with a stable secondary key, because a LazyColumn keyed on position and a list
        // that reorders on every recomposition is how a photograph ends up under the wrong time.
        return (entries + clustered).sortedWith(
            compareBy(
                { it.minutes ?: -1 },
                { if (it is Item.Photos) 1 else 0 },
            ),
        )
    }

    /**
     * Runs of photographs taken close together, collapsed into one item each.
     *
     * Sorts first: the caller's order is whatever MediaStore and the timestamp reconciliation
     * produced, and clustering an unsorted list produces clusters that overlap in time.
     */
    fun cluster(photos: List<PhotoAt>, gapMinutes: Int = CLUSTER_GAP_MINUTES): List<Item.Photos> {
        if (photos.isEmpty()) return emptyList()
        val sorted = photos.sortedBy { it.minutes }
        val out = mutableListOf<Item.Photos>()
        var run = mutableListOf(sorted.first())

        for (photo in sorted.drop(1)) {
            // Measured against the *previous photograph*, not against the run's start, so a
            // long afternoon of steady shooting stays one moment instead of breaking into
            // arbitrary twenty-minute blocks.
            if (photo.minutes - run.last().minutes <= gapMinutes) {
                run.add(photo)
            } else {
                out.add(run.toItem())
                run = mutableListOf(photo)
            }
        }
        out.add(run.toItem())
        return out
    }

    private fun List<PhotoAt>.toItem() = Item.Photos(
        photos = toList(),
        minutes = first().minutes,
        untilMinutes = last().minutes,
    )

    /**
     * Where the now line goes in a built list: the number of items that are behind it.
     *
     * An index rather than an item in the list, because the line is not a thing on the day —
     * it is the boundary between two halves of one, and a row in the list would have to be
     * given an id, a key and a tap.
     */
    fun nowLineIndex(items: List<Item>, line: Int?): Int? {
        if (line == null) return null
        val index = items.count { it.behind }
        // Suppressed at the ends: a line above everything or below everything is a rule with
        // nothing on one side of it, which reads as a mistake rather than as the time.
        return index.takeIf { it > 0 && it < items.size }
    }

    const val MINUTES_IN_DAY = 24 * 60
}
