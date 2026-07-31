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
         * A note you wrote or came back to on this day.
         *
         * Part of the record of a day for the same reason a photograph is: it is something that
         * happened, the phone already knows when, and it costs nothing to ask. `NoteEntity`
         * carries `createdAt` and `updatedAt`, so this needs no new column, no bridge to another
         * app and no permission.
         */
        data class Note(
            val noteId: String,
            val title: String,
            override val minutes: Int,
            /** Written on this day, as opposed to returned to. */
            val wrote: Boolean,
            override val behind: Boolean,
        ) : Item

        /**
         * Somewhere you stopped, from LightFog.
         *
         * A place rather than a track: a tile says which square of the world you crossed, and
         * crossing is not being somewhere. The name is null until the nightly lookup has found one,
         * because turning a coordinate into "Fasan Cafe" has no offline source on this phone.
         */
        data class Place(
            val startMinutes: Int,
            val endMinutes: Int,
            val latitude: Double,
            val longitude: Double,
            val name: String?,
        ) : Item {
            override val minutes: Int get() = startMinutes
            override val behind: Boolean get() = true
        }

        /**
         * Something you listened to, from LightPhono.
         *
         * Grouped before it gets here — a day of individual tracks would drown everything else on
         * it, and "an hour of Talk Talk" is the thing that was true of the afternoon.
         */
        data class Listening(
            override val minutes: Int,
            val untilMinutes: Int,
            val artist: String,
            val tracks: Int,
        ) : Item {
            override val behind: Boolean get() = true
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
     * everything with a time, earliest first. The day screen and the planner both lift them out and
     * draw them beside the date; they stay in the built list so that anything counting a day's
     * contents still sees them. Photographs are clustered before they are sorted
     * in, so a burst takes one place in the order rather than eleven.
     *
     * A photograph is **always** behind, whatever its timestamp says. You cannot photograph the
     * future, and a camera whose clock has drifted a few minutes forward would otherwise put a
     * picture you are looking at on the wrong side of the line.
     */
    fun build(
        rows: List<AgendaRow>,
        photos: List<PhotoAt>,
        notes: List<Item.Note> = emptyList(),
        places: List<Item.Place> = emptyList(),
        listening: List<Item.Listening> = emptyList(),
        epochDay: Long,
        today: Long,
        nowMinutes: Int,
    ): List<Item> {
        val entries = rows.map { Item.Entry(it, behind(epochDay, it.minutes, today, nowMinutes)) }
        val clustered = cluster(photos)

        // Sorted with a stable secondary key, because a LazyColumn keyed on position and a list
        // that reorders on every recomposition is how a photograph ends up under the wrong time.
        return (entries + clustered + notes + places + listening).sortedWith(
            compareBy(
                { it.minutes ?: -1 },
                // At the same minute: what you planned, then what you wrote, then what you
                // photographed. Any fixed order would do; having one is what matters, because a
                // list that reorders between recompositions puts a photograph under the wrong
                // time and recycles the wrong bitmap into it.
                {
                    when (it) {
                        is Item.Entry -> 0
                        is Item.Place -> 1
                        is Item.Note -> 2
                        is Item.Photos -> 3
                        is Item.Listening -> 4
                    }
                },
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

    /** The first and last thing that happened, in minutes from midnight. */
    data class Bookends(val firstMinutes: Int, val lastMinutes: Int)

    /**
     * When the day started and when it stopped.
     *
     * Read only from things that have **already happened** and that carry a time. A plan for this
     * evening is not when the day ended, and an all-day entry has no time to be an end at — it is
     * the day's heading rather than a moment in it.
     *
     * Null when there is nothing to bookend, and null when there is only one moment: "6:40 to
     * 6:40" is not a day, it is one thing, and the row is already on screen saying so.
     */
    fun bookends(items: List<Item>): Bookends? {
        val minutes = items.filter { it.behind }.mapNotNull { it.minutes }
        if (minutes.isEmpty()) return null
        val first = minutes.min()
        val last = minutes.max()
        return if (first == last) null else Bookends(first, last)
    }

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

    /**
     * Whether a note belongs to a day, and as which kind of thing.
     *
     * One row per note per day, never two. A note written *and* returned to on the same day is
     * "wrote" — that is the thing that happened, and a second row saying you also edited the
     * note you had just written is noise. Written wins on the day it was written; every later
     * day it appears on, it appears as an edit.
     *
     * Only the **last** edit of a day is knowable: `updatedAt` is one column, so a note touched
     * five times shows the last of them. That is a real limit of the schema rather than a
     * choice, and it is the right one to accept — a full edit history would be a table.
     */
    fun noteActivity(
        noteId: String,
        title: String,
        createdAtMs: Long,
        updatedAtMs: Long,
        /** The day's real bounds, from `PhotoDays.windowMs` — 23 or 25 hours where it matters. */
        dayStartMs: Long,
        dayEndExclusiveMs: Long,
    ): Item.Note? {
        val range = dayStartMs until dayEndExclusiveMs
        val created = createdAtMs in range
        val updated = updatedAtMs in range
        val at = when {
            created -> createdAtMs
            updated -> updatedAtMs
            else -> return null
        }
        val minutes = ((at - dayStartMs) / 60_000L).toInt().coerceIn(0, MINUTES_IN_DAY - 1)
        return Item.Note(
            noteId = noteId,
            title = title.ifBlank { "Untitled" },
            minutes = minutes,
            wrote = created,
            // Writing is something that has happened by definition — the note exists.
            behind = true,
        )
    }

    /**
     * Runs of listening, so a day says "an hour of Talk Talk" rather than listing twenty tracks.
     *
     * Grouped by artist while the gap between tracks stays short. The artist is the unit because it
     * is what you would say about an afternoon; a run of one artist broken by a single track from
     * another is two runs, which is right — you changed what you were listening to.
     */
    fun listening(plays: List<Pair<Int, String>>, gapMinutes: Int = LISTENING_GAP_MINUTES): List<Item.Listening> {
        if (plays.isEmpty()) return emptyList()
        val sorted = plays.sortedBy { it.first }
        val out = ArrayList<Item.Listening>()
        var start = sorted.first().first
        var last = start
        var artist = sorted.first().second
        var count = 1

        fun flush() {
            out.add(Item.Listening(minutes = start, untilMinutes = last, artist = artist, tracks = count))
        }

        sorted.drop(1).forEach { (at, who) ->
            if (who == artist && at - last <= gapMinutes) {
                last = at
                count++
            } else {
                flush()
                start = at
                last = at
                artist = who
                count = 1
            }
        }
        flush()
        return out
    }

    /** Longer than this between tracks and you stopped listening and started again. */
    const val LISTENING_GAP_MINUTES = 25

    const val MINUTES_IN_DAY = 24 * 60
}
