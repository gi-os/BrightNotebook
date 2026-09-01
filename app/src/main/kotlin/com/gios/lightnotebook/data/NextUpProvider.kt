package com.gios.lightnotebook.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.lightnotebook.util.NextUp
import com.gios.lightnotebook.util.Recurrence
import java.time.Instant
import java.time.ZoneId

/**
 * The single next thing, served to BrightControl's lock face.
 *
 * `content://com.gios.lightnotebook.nextup/next` answers with **at most one row** —
 * `startAt` (epoch ms), `title`, `kind` (`event` | `reminder` | `ticket`), `allDay` (0/1) —
 * the next upcoming item inside 48 hours, computed from the same merged sources the NEXT UP
 * screen draws: this app's entries (imported calendars included, series expanded), and
 * LightPass tickets. Nothing upcoming means an empty cursor, and so does every failure;
 * a lock face is the last place that should ever see an exception.
 *
 * Read-only by construction — insert, update and delete answer "no" — and exported, because
 * the caller is another app. Nothing sensitive crosses: one title the user typed on their
 * own calendar, going to their own lock screen.
 *
 * The choosing lives in [NextUp], Android-free, where the boundaries (the horizon, the
 * timed-beats-all-day rule, ties) are unit-tested. This class only gathers candidates.
 */
class NextUpProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("startAt", "title", "kind", "allDay"))
        if (uri.pathSegments.firstOrNull() != PATH) return cursor
        val context = context ?: return cursor
        // Every failure is an empty cursor: a half-broken database or a bridge mid-upgrade
        // must read as "nothing coming up", never as a crash in somebody else's process.
        runCatching {
            pick(context)?.let {
                cursor.addRow(arrayOf(it.startAt, it.title, it.kind, if (it.allDay) 1 else 0))
            }
        }
        return cursor
    }

    private fun pick(context: Context): NextUp.Pick? {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        // Calendar dates, because entries store clock times on dates. 48 hours from now can
        // touch at most three of them; one more is margin against the maths ever being off by
        // a boundary, and costs a handful of rows.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val to = today + 3

        val dao = NotebookDatabase.get(context).dao()
        val candidates = ArrayList<NextUp.Candidate>()

        // Plain entries, spans fanned out: only the first day carries the time — a trip that
        // started at 09:40 on Monday did not also start at 09:40 on Wednesday — and the later
        // days ride as all-day, exactly as the planner draws them.
        for (entry in dao.rangeBlocking(today, to)) {
            if (entry.repeats) continue
            val kind = if (entry.reminderMinutes != null) KIND_REMINDER else KIND_EVENT
            if (entry.epochDay >= today) {
                candidates.add(NextUp.Candidate(entry.epochDay, entry.startMinutes, entry.text, kind))
            }
            for (day in maxOf(entry.epochDay + 1, today)..minOf(entry.lastDay, to)) {
                candidates.add(NextUp.Candidate(day, null, entry.text, kind))
            }
        }

        // Series, expanded by the rule inside the same window. A repeating entry's stored day
        // is usually long past, so the plain query above cannot see its next occurrence.
        for (master in dao.recurringBlocking()) {
            val kind = if (master.reminderMinutes != null) KIND_REMINDER else KIND_EVENT
            Recurrence.expand(
                rrule = master.rrule,
                startDay = master.epochDay,
                from = today,
                to = to,
                exDays = Recurrence.parseExDays(master.exDays),
            ).forEach { occurrence ->
                candidates.add(NextUp.Candidate(occurrence, master.startMinutes, master.text, kind))
            }
        }

        // Tickets, straight off LightPass's shelf — the same read the agenda does.
        LightPassBridge.showings(context)
            .filter { it.epochDay in today..to }
            .forEach { candidates.add(NextUp.Candidate(it.epochDay, it.startMinutes, it.title, KIND_TICKET)) }

        return NextUp.pick(candidates, now, zone)
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.$PATH"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.gios.lightnotebook.nextup"
        private const val PATH = "next"
        private const val KIND_EVENT = "event"
        private const val KIND_REMINDER = "reminder"
        private const val KIND_TICKET = "ticket"

        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH")

        /**
         * Tell whoever is watching that the answer may have changed. Best-effort and cheap —
         * the row is the contract, this is only a nudge — so it swallows everything: a
         * notify that fails must never take the write it was riding on down with it.
         */
        fun poke(context: Context) {
            runCatching { context.contentResolver.notifyChange(URI, null) }
        }
    }
}
