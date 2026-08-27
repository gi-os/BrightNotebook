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

    /** One cutout on the day: enough to draw it without decoding it first. */
    data class CaughtSticker(
        val id: String,
        val name: String,
        val uri: String,
        val width: Int,
        val height: Int,
    )

    sealed interface Item {
        /** Null means all day — no time was ever given to it. */
        val minutes: Int?

        /** Whether this has already happened, and so belongs to the diary half of the day. */
        val behind: Boolean

        /**
         * A calendar entry, placed on the same axis as everything else.
         *
         * [minutes] is **journal minutes**, converted from the row's clock time, because that is
         * the unit this whole file is in — a photograph at half past two is 630, and an entry at
         * half past two has to be 630 as well or the two sort four hours apart. The row keeps its
         * own clock time for display; nothing here reads it.
         *
         * This was the bug behind a day that read "started at 11:17, then 8am, then 1pm, then
         * 2pm, then 6pm, then 2:30pm": entries were being sorted against instants in the wrong
         * unit, so each one landed four hours late among things that were themselves in order.
         */
        data class Entry(
            val row: AgendaRow,
            override val behind: Boolean,
            override val minutes: Int? = row.minutes?.let { JournalDay.fromClockMinutes(it) },
        ) : Item

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
         * Arriving somewhere you had named — home, work.
         *
         * A moment rather than a stay, because that is all there is: the fixes inside a named zone
         * are never recorded, so there is no duration to know. "Went home at 19:40" is the whole
         * fact, and it is enough — the next thing on the day says when you left.
         */
        data class Arrived(
            override val minutes: Int,
            /** `home`, `work` — lower case as the recorder stores it. */
            val zone: String,
        ) : Item {
            override val behind: Boolean get() = true

            /** "Went home", "Went to work" — the two read differently and both should read right. */
            val phrase: String get() = when (zone.lowercase()) {
                "home" -> "Went home"
                "work" -> "Went to work"
                else -> "Went to " + zone
            }
        }

        /**
         * Somebody you talked to, from LightChat.
         *
         * Names only, by design at the other end: the journal knows you spoke to Alex and does not
         * know what either of you said.
         */
        data class Talked(
            override val minutes: Int,
            val untilMinutes: Int,
            val name: String,
            val isGroup: Boolean,
            val messages: Int,
            val theyReplied: Boolean,
        ) : Item {
            override val behind: Boolean get() = true
        }

        /**
         * Picking the phone up.
         *
         * Grouped into runs the way listening is, because a day has dozens and a row each would be
         * the only thing on the screen. "Seven times, 14:10 to 15:40" is the shape of an afternoon
         * spent on your phone; thirty identical rows are not.
         */
        data class Pickups(
            override val minutes: Int,
            val untilMinutes: Int,
            val times: Int,
        ) : Item {
            override val behind: Boolean get() = true
        }

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
            /** Most played first. Only the few worth naming; the rest are a count. */
            val artists: List<String>,
            /** How many distinct artists there were altogether, named or not. */
            val distinctArtists: Int,
            val tracks: Int,
        ) : Item {
            override val behind: Boolean get() = true

            /** How many were left over after the named ones. */
            val moreArtists: Int get() = (distinctArtists - artists.size).coerceAtLeast(0)
        }

        /**
         * A phone call.
         *
         * A row each rather than one row per person: a call is a thing that happened at a time,
         * and "Alex · 3 calls" would be a summary of a day rather than part of its story. The
         * phrasing lives in [Calls.Call] because "Called Alex" and "Alex called" are different
         * facts and only one of them is something you did.
         */
        data class Called(
            override val minutes: Int,
            val call: Calls.Call,
        ) : Item {
            // You cannot be called in the future. A call log entry is by definition a thing that
            // has already happened, whatever the clock says.
            override val behind: Boolean get() = true
        }

        /**
         * A stretch on the charger.
         *
         * Kept because it is the closest thing this phone has to saying when you went to bed —
         * plugged in at 23:40, unplugged at 7:10 is a night, and nothing else here can see one.
         * [startedEarlier] is what stops last night's charge claiming to have begun at 4am
         * because that is where this day's window starts.
         */
        data class Charged(
            override val minutes: Int,
            val untilMinutes: Int,
            val startedEarlier: Boolean,
            val stillGoing: Boolean,
        ) : Item {
            override val behind: Boolean get() = true

            val lengthMinutes: Int get() = (untilMinutes - minutes).coerceAtLeast(0)
        }

        /**
         * Something you recorded, from BrightRecorder.
         *
         * A row each, not a summary. Grouping is right for music — "an hour of Talk Talk" is what
         * was true of an afternoon, and a day of individual tracks would drown everything else on
         * it — and wrong for this: you made three recordings today, deliberately, and each one is
         * a thing you did rather than a background the day had.
         */
        data class Recorded(
            override val minutes: Int,
            val title: String,
            val place: String,
            val seconds: Float,
            /** Enough to play it: the recorder serves the audio itself. */
            val tapeDir: String,
            val file: String,
        ) : Item {
            // You cannot have recorded something in the future.
            override val behind: Boolean get() = true

            /** "1:42", or "0:08". Seconds matter here — most recordings are short. */
            val length: String
                get() {
                    val whole = seconds.toInt().coerceAtLeast(0)
                    return "${whole / 60}:" + (whole % 60).toString().padStart(2, '0')
                }
        }

        /**
         * Somewhere you went, from BrightWay.
         *
         * A row rather than a span, even though it has two ends. A walk is a thing you did — the
         * span treatment is for what was going on *while* you did things, and being on the way
         * somewhere is not the background of an afternoon, it is most of one.
         */
        data class Went(
            override val minutes: Int,
            val place: String,
            val walking: Boolean,
            val tookMinutes: Int,
            /** False when navigation ended before the last step: you set off, and that is all. */
            val arrived: Boolean,
        ) : Item {
            override val behind: Boolean get() = true
        }

        /**
         * A sitting with a book, from LightBooks.
         *
         * Already coalesced at the other end: the reader writes progress several times a second and
         * none of that is a diary. What arrives here is "you read this, from here to here, for this
         * long", which is the shape a day wants.
         */
        data class Read(
            override val minutes: Int,
            val title: String,
            val author: String,
            val advanced: Int,
            val pages: Boolean,
            val tookMinutes: Int,
        ) : Item {
            override val behind: Boolean get() = true

            /** "32 pages", "1,400 words", or null when nothing moved. */
            val progress: String?
                get() = when {
                    advanced <= 0 -> null
                    pages -> if (advanced == 1) "1 page" else "$advanced pages"
                    else -> "%,d words".format(advanced)
                }
        }

        /**
         * A note or a voice note taken in Light's own app.
         *
         * Read out of `Documents/` rather than owned here — see [com.gios.lightnotebook.data
         * .LightDocs]. A row each, like a recording, because each one is a thing somebody sat down
         * and did. Nothing about its contents is known and none is shown: the row says a note
         * happened at a time, and tapping it hands the file to whatever opens it.
         */
        data class LightNote(
            override val minutes: Int,
            val name: String,
            val voice: Boolean,
            /** The document URI, carried so the row can open it. */
            val uri: String,
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
        /**
         * The things you caught, from BrightCollect.
         *
         * All of a day's catches in one item rather than a row each, and that is a claim about
         * what they are: a day's collecting is one activity with several results, the way a roll
         * of film is. Two or three a day is the usual number, so a row each would spread one
         * afternoon's rummaging down the whole page.
         *
         * Drawn as a little tray of cutouts — no card, no frame, no white plate behind them. A
         * sticker in a box is a photograph of a sticker.
         */
        data class Caught(
            override val minutes: Int,
            val stickers: List<CaughtSticker>,
        ) : Item {
            override val behind: Boolean get() = true
        }

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
     * The identity of one row, as a string.
     *
     * **A `LazyColumn` given the same key twice throws**, and the day is built out of eight
     * independent sources that each know only about themselves — so "unique" cannot be checked at
     * any one of them. It is checked here, in the one place that can see the whole list: the key is
     * defined once, and [build] drops anything whose key it has already emitted.
     *
     * That is the general form of a bug this file has now had twice. Arrivals were the first —
     * a GPS flap inside a named zone produced two of them a second apart, which is one journal
     * minute — and conversations were the second: two threads with the same name starting in the
     * same minute, which the bridge's own dedupe by *millisecond* did not catch. Both crashed the
     * day rather than drawing it, which is the worst shape a duplicate can take.
     *
     * Keyed on what the row *is*, never on where it sits: the list reorders as the clock passes an
     * entry, and a positional key would recycle a photograph's loaded bitmap into whatever row took
     * its place.
     */
    fun key(item: Item): String = when (item) {
        is Item.Entry -> "row-" + item.row.id
        is Item.Photos -> "photos-" + item.photos.first().id
        is Item.Note -> "note-" + item.noteId
        is Item.Place -> "place-" + item.startMinutes
        is Item.Listening -> "heard-" + item.minutes
        is Item.Pickups -> "picked-" + item.minutes
        is Item.Talked -> "talked-" + item.name + "-" + item.minutes
        is Item.Arrived -> "arrived-" + item.zone + "-" + item.minutes
        is Item.Called -> "call-" + item.minutes + "-" + item.call.who
        is Item.Charged -> "charge-" + item.minutes
        is Item.Recorded -> "clip-" + item.tapeDir + "-" + item.file
        is Item.LightNote -> "lightdoc-" + item.uri
        is Item.Went -> "went-" + item.minutes + "-" + item.place
        is Item.Read -> "read-" + item.minutes + "-" + item.title
        is Item.Caught -> "caught-" + item.minutes
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
        pickups: List<Item.Pickups> = emptyList(),
        talked: List<Item.Talked> = emptyList(),
        arrivals: List<Item.Arrived> = emptyList(),
        calls: List<Item.Called> = emptyList(),
        charges: List<Item.Charged> = emptyList(),
        recordings: List<Item.Recorded> = emptyList(),
        lightNotes: List<Item.LightNote> = emptyList(),
        went: List<Item.Went> = emptyList(),
        read: List<Item.Read> = emptyList(),
        caught: List<Item.Caught> = emptyList(),
        epochDay: Long,
        today: Long,
        nowMinutes: Int,
    ): List<Item> {
        val entries = rows.map { row ->
            // `behind` is compared against the now line, which is also journal minutes, so the
            // conversion has to happen before that question is asked and not only for sorting.
            val journal = row.minutes?.let { JournalDay.fromClockMinutes(it) }
            Item.Entry(row, behind(epochDay, journal, today, nowMinutes), journal)
        }
        val clustered = cluster(photos)

        // One arrival per zone per minute. The recorder dedupes its own arrivals by millisecond,
        // which is not the granularity anything downstream uses: the timeline places an arrival by
        // journal minute and the list keys it by journal minute, so two fixes seconds apart inside
        // the same named zone — a GPS flap on the edge of it — are two items claiming one key, and
        // a LazyColumn given the same key twice throws rather than drawing the day.
        val arrived = arrivals.distinctBy { it.minutes to it.zone }

        // Sorted with a stable secondary key, because a LazyColumn keyed on position and a list
        // that reorders on every recomposition is how a photograph ends up under the wrong time.
        return (entries + clustered + notes + places + listening + pickups + talked +
            arrived + calls + charges + recordings + lightNotes + went + read + caught)
            // One row per key, whatever the sources handed over. See [key]: this is the only place
            // that can enforce it, and a duplicate reaching the list is a crash rather than a
            // cosmetic fault.
            .distinctBy { key(it) }
            .sortedWith(
            compareBy(
                { it.minutes ?: -1 },
                // At the same minute: what you planned, then what you wrote, then what you
                // photographed. Any fixed order would do; having one is what matters, because a
                // list that reorders between recompositions puts a photograph under the wrong
                // time and recycles the wrong bitmap into it.
                {
                    when (it) {
                        is Item.Entry -> 0
                        // Arriving somewhere and being somewhere sort together: they are the same
                        // kind of fact, and a named arrival at the same minute as a stay is the same
                        // event seen from two sides.
                        is Item.Arrived -> 1
                        is Item.Place -> 1
                        is Item.Talked -> 2
                        // A call is the same kind of fact as a conversation, so they sort
                        // together rather than in two separate bands.
                        is Item.Called -> 2
                        is Item.Note -> 3
                        // A recording sorts with a photograph: both are something you deliberately
                        // captured at that minute, and on a day with one of each they belong
                        // next to each other rather than in separate bands.
                        // With the recordings and the photographs: all three are something
                        // captured at that minute rather than something the day did.
                        // Going somewhere sorts with arriving and being somewhere: same kind of
                        // fact, seen from a third side.
                        is Item.Went -> 1
                        // Reading sorts with the things you sat down and did on purpose.
                        is Item.Read -> 4
                        is Item.LightNote -> 4
                        is Item.Recorded -> 4
                        is Item.Photos -> 4
                        // With the photographs and the recordings: catching something is the same
                        // kind of fact as photographing it, because it starts by photographing it.
                        is Item.Caught -> 4
                        is Item.Listening -> 5
                        is Item.Pickups -> 6
                        // Last: being on the charger is the background of a day, not an event
                        // competing with the things that happened during it.
                        is Item.Charged -> 7
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
    fun listening(
        plays: List<Pair<Int, String>>,
        gapMinutes: Int = LISTENING_GAP_MINUTES,
        name: Int = NAMED_ARTISTS,
    ): List<Item.Listening> {
        if (plays.isEmpty()) return emptyList()
        val sorted = plays.sortedBy { it.first }
        val out = ArrayList<Item.Listening>()

        var start = sorted.first().first
        var last = start
        var counts = LinkedHashMap<String, Int>()

        fun flush() {
            if (counts.isEmpty()) return
            // Most played first, and ties broken by the order they were first heard — a stable
            // rule, so the same afternoon always reads the same way. `sortedByDescending` is
            // stable, and `counts` is insertion-ordered, which together give exactly that.
            val ranked = counts.entries.sortedByDescending { it.value }.map { it.key }
            out.add(
                Item.Listening(
                    minutes = start,
                    untilMinutes = last,
                    artists = ranked.take(name),
                    distinctArtists = ranked.size,
                    tracks = counts.values.sum(),
                ),
            )
        }

        sorted.forEachIndexed { index, (at, who) ->
            if (index == 0) {
                counts[who] = 1
                return@forEachIndexed
            }
            if (at - last <= gapMinutes) {
                // **Grouped by time, not by artist.** A run is a stretch of the day you had music
                // on, and what makes it one stretch is that it did not stop — not that you played
                // one person the whole way through. Splitting on every change of artist turned a
                // shuffled afternoon into thirty rows, each of them true and none of them useful.
                last = at
                counts[who] = (counts[who] ?: 0) + 1
            } else {
                flush()
                start = at
                last = at
                counts = LinkedHashMap()
                counts[who] = 1
            }
        }
        flush()
        return out
    }

    /** Three is enough to recognise an afternoon; past that it is a list rather than a sentence. */
    const val NAMED_ARTISTS = 3

    /** Longer than this between tracks and you stopped listening and started again. */
    const val LISTENING_GAP_MINUTES = 25

    /**
     * Runs of picking the phone up.
     *
     * Same reasoning as [listening]: a day has dozens of these and one row each would bury
     * everything that actually happened. A run breaks after a quarter of an hour untouched, which
     * separates "kept checking it through lunch" from "looked at it once in the evening".
     */
    fun pickups(minutes: List<Int>, gapMinutes: Int = PICKUP_GAP_MINUTES): List<Item.Pickups> {
        if (minutes.isEmpty()) return emptyList()
        val sorted = minutes.sorted()
        val out = ArrayList<Item.Pickups>()
        var start = sorted.first()
        var last = start
        var count = 1

        sorted.drop(1).forEach { at ->
            if (at - last <= gapMinutes) {
                last = at
                count++
            } else {
                out.add(Item.Pickups(start, last, count))
                start = at
                last = at
                count = 1
            }
        }
        out.add(Item.Pickups(start, last, count))
        return out
    }

    const val PICKUP_GAP_MINUTES = 15

    const val MINUTES_IN_DAY = 24 * 60
}
