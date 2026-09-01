package com.gios.lightnotebook.data

import android.content.Context
import android.net.Uri
import com.gios.lightnotebook.util.JournalDay
import java.time.LocalDate
import java.time.ZoneId

/** Somewhere you stopped, from LightFog. */
data class Stay(
    val startMs: Long,
    val endMs: Long,
    val latitude: Double,
    val longitude: Double,
) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
}

/** Something you listened to, from LightPhono. */
data class Play(val atMs: Long, val title: String, val artist: String)

/**
 * A session in front of the television, from BrightRemote.
 *
 * Already a sitting at the other end — the remote watches the Apple TV's now-playing state and
 * writes one row per session, so nothing here has to guess where an episode ended and the next
 * began. [subtitle] is the episode under the show's name, and may be empty: a film has no episode.
 */
data class Watched(
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val subtitle: String,
    val durationMin: Int,
)

/**
 * Something you recorded, from BrightRecorder.
 *
 * [tapeDir] and [file] are carried so the clip can be played without a copy of it — the recorder
 * serves the audio itself at `content://com.gios.brightrecorder.clips/clip/<tapeDir>/<file>`.
 */
data class Recorded(
    val startedAt: Long,
    val seconds: Float,
    val place: String,
    val tape: String,
    val title: String,
    val tapeDir: String,
    val file: String,
)

/**
 * Somewhere you went, from BrightWay.
 *
 * [arrived] is the difference between "walked to Union Square" and "set off towards Union Square",
 * which are different days — BrightWay records it rather than inferring it here, because the only
 * thing that knows is the screen that was counting the steps.
 */
data class Trip(
    val startedMs: Long,
    val endedMs: Long,
    val mode: String,
    val name: String,
    val plannedS: Long,
    val distanceM: Double,
    val arrived: Boolean,
) {
    /** How long it took, or what it was predicted to take while it is still running. */
    val minutes: Int
        get() = if (endedMs > startedMs) {
            ((endedMs - startedMs) / 60_000L).toInt()
        } else {
            (plannedS / 60L).toInt()
        }

    val walking: Boolean get() = !mode.equals("TRANSIT", ignoreCase = true)
}

/** A sitting with a book, from LightBooks. */
data class Reading(
    val startedMs: Long,
    val lastMs: Long,
    val title: String,
    val author: String,
    /** Words, or pages for a comic — see [pages]. */
    val advanced: Int,
    val pages: Boolean,
) {
    val minutes: Int get() = (((lastMs - startedMs) / 60_000L).toInt()).coerceAtLeast(0)
}

/**
 * Arriving somewhere you had named, from LightFog. Home and work.
 *
 * No coordinates, by construction at the other end: a zone's fixes never reach the track, so the
 * only thing there is to serve is that you got there and when.
 */
data class Arrival(val atMs: Long, val name: String)

/**
 * Something you caught, from BrightCollect.
 *
 * An object photographed and cut out of its background — a sticker, not a photograph, which is
 * the whole reason this is a bridge at all. BrightCollect used to publish a flattened copy into
 * MediaStore, and the day drew it in the photo strip on a white card, because that is what
 * anything in the photo library is. The cutout arrives with its alpha intact now and is drawn as
 * the shape of the thing.
 *
 * [width] and [height] are the trimmed silhouette, so the day can lay several out at their own
 * proportions without decoding them first.
 */
data class Caught(
    val atMs: Long,
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
) {
    /** Where the PNG itself lives. BrightCollect serves it; nothing is copied. */
    val uri: String get() = "content://com.gios.brightcollect.caught/sticker/" + id
}

/** Someone you talked to, from LightChat. Names only — no message ever crosses the boundary. */
data class Talked(
    val firstMs: Long,
    val lastMs: Long,
    val name: String,
    val isGroup: Boolean,
    val messages: Int,
    val theyReplied: Boolean,
)

/**
 * The two things about a day that live in other apps.
 *
 * Both follow the shape [LightPassBridge] set and [RollStars] repeated: ask a provider, take what
 * comes, treat every failure as nothing. The app may not be installed, may be an older build with
 * no provider, or may refuse — and none of those is worth a message on a day.
 *
 * **Both are asked by calendar date, and this app's days do not start at midnight.** A journal day
 * runs from four in the morning, so it spans two calendar dates and both are fetched and filtered
 * to the real window. Getting that wrong would put a one-in-the-morning song on the wrong day,
 * exactly the error [JournalDay] exists to prevent — and it is the kind that hides, because it only
 * shows up in the small hours.
 */
object DayBridges {

    private const val STAYS = "content://com.gios.lightfog.stays/stays/"
    private const val PLAYS = "content://com.lightphone.spotify.plays/plays/"
    private const val TALKED = "content://com.gios.lightchat.talked/talked/"
    private const val ZONES = "content://com.gios.lightfog.stays/zones/"
    private const val CLIPS = "content://com.gios.brightrecorder.clips/clips/"
    private const val TRIPS = "content://com.gios.brightway.trips/trips/"
    private const val READING = "content://com.lightfastread.reading/reading/"
    private const val CAUGHT = "content://com.gios.brightcollect.caught/caught/"
    private const val WATCHED = "content://com.gios.lightremote.watched/sessions/"

    fun stays(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Stay> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, STAYS + date) { c ->
                Stay(
                    startMs = c.getLong(c.getColumnIndexOrThrow("start_ms")),
                    endMs = c.getLong(c.getColumnIndexOrThrow("end_ms")),
                    latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
                    longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
                )
            }
        }
            // A stay that began before the day started still belongs to it if it ran into it — you
            // were there. Judged on the start, which is where it appears on the timeline.
            .filter { it.startMs in window }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
    }

    fun plays(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Play> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, PLAYS + date) { c ->
                Play(
                    atMs = c.getLong(c.getColumnIndexOrThrow("at_ms")),
                    title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                    artist = c.getString(c.getColumnIndexOrThrow("artist")).orEmpty(),
                )
            }
        }
            .filter { it.atMs in window }
            .sortedBy { it.atMs }
            .distinctBy { it.atMs }
    }

    /**
     * What you watched, from BrightRemote.
     *
     * Placed by when the session *started*, like everything else on the timeline: an episode that
     * ran past four in the morning still belongs to the evening it began on. An older remote has
     * no provider at all, and that reads as a day with no television on it — absence, not an error.
     */
    fun watched(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Watched> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, WATCHED + date) { c ->
                Watched(
                    startAt = c.getLong(c.getColumnIndexOrThrow("startAt")),
                    endAt = c.getLong(c.getColumnIndexOrThrow("endAt")),
                    title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                    subtitle = c.getString(c.getColumnIndexOrThrow("subtitle")).orEmpty(),
                    durationMin = c.getLong(c.getColumnIndexOrThrow("durationMin")).toInt(),
                )
            }
        }
            .filter { it.startAt in window }
            .sortedBy { it.startAt }
            // Both calendar dates are fetched, so a session inside the overlap arrives twice.
            .distinctBy { it.startAt to it.title }
    }

    fun talked(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Talked> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, TALKED + date) { c ->
                Talked(
                    firstMs = c.getLong(c.getColumnIndexOrThrow("first_ms")),
                    lastMs = c.getLong(c.getColumnIndexOrThrow("last_ms")),
                    name = c.getString(c.getColumnIndexOrThrow("name")).orEmpty(),
                    isGroup = c.getLong(c.getColumnIndexOrThrow("is_group")) == 1L,
                    messages = c.getLong(c.getColumnIndexOrThrow("messages")).toInt(),
                    theyReplied = c.getLong(c.getColumnIndexOrThrow("they_replied")) == 1L,
                )
            }
        }
            // A conversation that spilled over midnight arrives from both dates; keep the one whose
            // first message falls inside this day, and merge nothing — the other day owns its half.
            .filter { it.firstMs in window }
            .sortedBy { it.firstMs }
            .distinctBy { it.name to it.firstMs }
    }

    fun arrivals(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Arrival> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, ZONES + date) { c ->
                Arrival(
                    atMs = c.getLong(c.getColumnIndexOrThrow("at_ms")),
                    name = c.getString(c.getColumnIndexOrThrow("name")).orEmpty(),
                )
            }
        }
            .filter { it.atMs in window && it.name.isNotBlank() }
            .sortedBy { it.atMs }
            .distinctBy { it.atMs to it.name }
    }

    /**
     * What you recorded, from BrightRecorder.
     *
     * A clip is placed by when it *started*, like everything else on the timeline. One that ran
     * past four in the morning still belongs to the day it began on — a recording is a thing you
     * did at a time, and the time it began is the one you would look for it under.
     */
    fun recordings(
        context: Context,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Recorded> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, CLIPS + date) { c ->
                Recorded(
                    startedAt = c.getLong(c.getColumnIndexOrThrow("started_ms")),
                    seconds = c.getFloat(c.getColumnIndexOrThrow("seconds")),
                    place = c.getString(c.getColumnIndexOrThrow("place")).orEmpty(),
                    tape = c.getString(c.getColumnIndexOrThrow("tape")).orEmpty(),
                    title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                    tapeDir = c.getString(c.getColumnIndexOrThrow("tape_dir")).orEmpty(),
                    file = c.getString(c.getColumnIndexOrThrow("file")).orEmpty(),
                )
            }
        }
            .filter { it.startedAt in window }
            .sortedBy { it.startedAt }
            // The provider walks every tape, and the same clip cannot appear twice — but both
            // calendar dates are fetched, so a clip is offered twice whenever the window overlaps.
            .distinctBy { it.tapeDir to it.file }
    }

    /**
     * What you caught, from BrightCollect.
     *
     * Placed by when the shutter fired, which is not when the cutout was finished — a sticker made
     * this evening out of a photograph taken in June belongs to June, and BrightCollect carries the
     * original EXIF time through for exactly that reason.
     */
    fun caught(context: Context, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Caught> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, CAUGHT + date) { c ->
                Caught(
                    atMs = c.getLong(c.getColumnIndexOrThrow("caught_ms")),
                    id = c.getString(c.getColumnIndexOrThrow("id")).orEmpty(),
                    name = c.getString(c.getColumnIndexOrThrow("name")).orEmpty(),
                    width = c.getInt(c.getColumnIndexOrThrow("width")),
                    height = c.getInt(c.getColumnIndexOrThrow("height")),
                )
            }
        }
            .filter { it.atMs in window && it.id.isNotBlank() }
            .sortedBy { it.atMs }
            // Both calendar dates are fetched, so anything inside the overlap arrives twice.
            .distinctBy { it.id }
    }

    /**
     * Where you went, from BrightWay.
     *
     * Placed by when the trip *started*, like everything else on the timeline: a walk that finished
     * after four in the morning still belongs to the night it began on.
     */
    fun trips(
        context: Context,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Trip> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, TRIPS + date) { c ->
                Trip(
                    startedMs = c.getLong(c.getColumnIndexOrThrow("started_ms")),
                    endedMs = c.getLong(c.getColumnIndexOrThrow("ended_ms")),
                    mode = c.getString(c.getColumnIndexOrThrow("mode")).orEmpty(),
                    name = c.getString(c.getColumnIndexOrThrow("name")).orEmpty(),
                    plannedS = c.getLong(c.getColumnIndexOrThrow("planned_s")),
                    distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                    arrived = c.getLong(c.getColumnIndexOrThrow("arrived")) == 1L,
                )
            }
        }
            .filter { it.startedMs in window }
            .sortedBy { it.startedMs }
            .distinctBy { it.startedMs }
    }

    /**
     * What you read, from LightBooks.
     *
     * One row per sitting, already coalesced at the other end — this app never sees the page turns,
     * which happen several times a second and are nobody's diary.
     */
    fun reading(
        context: Context,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Reading> {
        val window = JournalDay.windowMs(epochDay, zone)
        return datesFor(epochDay).flatMap { date ->
            read(context, READING + date) { c ->
                Reading(
                    startedMs = c.getLong(c.getColumnIndexOrThrow("started_ms")),
                    lastMs = c.getLong(c.getColumnIndexOrThrow("last_ms")),
                    title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
                    author = c.getString(c.getColumnIndexOrThrow("author")).orEmpty(),
                    advanced = c.getLong(c.getColumnIndexOrThrow("advanced")).toInt(),
                    pages = c.getLong(c.getColumnIndexOrThrow("pages")) == 1L,
                )
            }
        }
            .filter { it.startedMs in window }
            .sortedBy { it.startedMs }
            .distinctBy { it.startedMs to it.title }
    }

    /**
     * The calendar dates a journal day touches.
     *
     * Two of them, always: a day beginning at four in the morning on the 30th ends at four on the
     * 31st, so anything after midnight is filed by the other app under the 31st.
     */
    private fun datesFor(epochDay: Long): List<String> = listOf(
        LocalDate.ofEpochDay(epochDay).toString(),
        LocalDate.ofEpochDay(epochDay + 1).toString(),
    )

    /**
     * The span of each day in a window that the phone has any evidence for, from both apps.
     *
     * For the planner, which needs to know a day happened without needing to know what happened.
     * One query per day per app is not free, so the results are cached: panning back and forth over
     * the same month asks once. The cache is keyed by day and never invalidated within a session —
     * a past day's stays do not change, and today is re-read because the nudge that drives the
     * window also clears it.
     */
    fun spans(
        context: Context,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Long, IntRange> {
        val out = HashMap<Long, IntRange>()
        // Bounded: a year-wide window would be seven hundred file reads for marks too small to see.
        if (toDay - fromDay > MAX_WINDOW_DAYS) return out
        for (day in fromDay..toDay) {
            val cached = spanCache[day]
            if (cached != null) {
                cached.value?.let { out[day] = it }
                continue
            }
            val minutes = ArrayList<Int>()
            stays(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.startMs, day, zone))
                minutes.add(JournalDay.minutesInto(it.endMs, day, zone))
            }
            plays(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.atMs, day, zone))
            }
            arrivals(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.atMs, day, zone))
            }
            talked(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.firstMs, day, zone))
                minutes.add(JournalDay.minutesInto(it.lastMs, day, zone))
            }
            // A day you only recorded on is still a day something happened, and the activity line
            // is the one place that shows it before you open the day.
            recordings(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.startedAt, day, zone))
            }
            trips(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.startedMs, day, zone))
            }
            // An evening you only watched something on is still an evening the day can see.
            watched(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.startAt, day, zone))
            }
            reading(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.startedMs, day, zone))
                minutes.add(JournalDay.minutesInto(it.lastMs, day, zone))
            }
            // A day you only caught something on is still a day something happened, and the
            // activity line is the one place that shows it before you open the day.
            caught(context, day, zone).forEach {
                minutes.add(JournalDay.minutesInto(it.atMs, day, zone))
            }
            val span = if (minutes.size < 2) null else minutes.min()..minutes.max()
            spanCache[day] = Cached(span)
            span?.let { out[day] = it }
        }
        return out
    }

    fun forget() = spanCache.clear()

    private class Cached(val value: IntRange?)

    private val spanCache = HashMap<Long, Cached>()

    private const val MAX_WINDOW_DAYS = 62L

    private inline fun <T> read(context: Context, uri: String, crossinline row: (android.database.Cursor) -> T): List<T> =
        runCatching {
            context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
                val out = ArrayList<T>()
                while (cursor.moveToNext()) out.add(row(cursor))
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
}
