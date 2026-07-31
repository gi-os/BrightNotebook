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
