package com.gios.lightnotebook.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.util.IcsParser
import com.gios.lightnotebook.util.NoteDates

/** What a sync pass did, for the one line the settings screen shows afterwards. */
data class SyncResult(val calendars: Int, val events: Int, val failed: Int) {
    val nothingToDo: Boolean get() = calendars == 0 && failed == 0
}

/**
 * Re-reads every imported calendar from wherever it came from.
 *
 * Imports are snapshots, so without this a calendar is only ever as fresh as the day it was
 * added. Each source is re-read and its events replaced wholesale — the same path a manual
 * re-import takes — so something moved or cancelled at the source moves or disappears here
 * rather than accumulating.
 *
 * Failures are counted, not thrown: a file that has since been deleted, or a phone calendar
 * whose permission was revoked, should not stop the other calendars from refreshing.
 */
object Sync {

    private const val TAG = "Sync"

    suspend fun run(context: Context): SyncResult {
        val app = context.applicationContext
        val repo = NotebookRepository(app)
        // The zone imported instants are read in — the phone's unless it has been overridden,
        // which it has to be on a device that reports the wrong one. Read once per pass, so a
        // change cannot land halfway through and split a calendar across two zones.
        val zone = repo.calendarZone()
        var calendars = 0
        var events = 0
        var failed = 0

        repo.calendars().forEach { calendar ->
            val sourceRef = calendar.sourceRef
            if (sourceRef == null) return@forEach
            val found = when (calendar.kind) {
                CalendarEntity.KIND_DEVICE -> sourceRef.toLongOrNull()
                    ?.let { DeviceCalendars.events(app, it) }

                CalendarEntity.KIND_ICS -> runCatching {
                    repo.readText(Uri.parse(sourceRef))?.takeIf { IcsParser.looksLikeIcs(it) }
                        ?.let { IcsParser.parse(it, zone) }
                }.getOrNull()

                CalendarEntity.KIND_URL -> runCatching {
                    CalendarFeed.fetch(sourceRef)?.takeIf { IcsParser.looksLikeIcs(it) }
                        ?.let { IcsParser.parse(it, zone) }
                }.getOrNull()

                else -> null
            }
            if (found.isNullOrEmpty()) {
                // An empty read is treated as a failure rather than as "the calendar is now
                // empty": a revoked permission and a genuinely cleared calendar look
                // identical from here, and wiping somebody's events on a guess is worse.
                failed++
                Log.w(TAG, "could not refresh ${calendar.label}")
                return@forEach
            }
            val result = repo.importEvents(
                label = calendar.label,
                kind = calendar.kind,
                sourceRef = sourceRef,
                events = found,
                reminderMinutes = repo.defaultReminderMinutes(),
            )
            calendars++
            events += result.entries.size
            Reminders.rearmAll(
                app,
                result.entries.filter { it.epochDay >= NoteDates.today() },
            )
        }
        Log.i(TAG, "refreshed $calendars calendar(s), $events event(s), $failed failed")
        return SyncResult(calendars = calendars, events = events, failed = failed)
    }
}
