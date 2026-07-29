package com.gios.lightnotebook.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.gios.lightnotebook.util.ImportedEvent
import java.time.ZoneId

/** A calendar the phone already knows about, offered for import. */
data class DeviceCalendar(val id: Long, val label: String, val account: String?)

/**
 * Reads the phone's own calendars, so an account that syncs elsewhere can be pulled in
 * once rather than retyped.
 *
 * A snapshot, not a subscription: [events] copies a window of occurrences into the
 * notebook, and re-importing replaces them. Watching the provider for changes would mean a
 * sync adapter and a service, and on a phone with no Play Services there is usually
 * nothing syncing in the background to watch.
 */
object DeviceCalendars {

    /** How much of the calendar to take: enough past to look back at, a year ahead. */
    private const val DAYS_BACK = 30L
    private const val DAYS_FORWARD = 365L

    fun available(context: Context): List<DeviceCalendar> = runCatching {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val out = mutableListOf<DeviceCalendar>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                val account = cursor.getString(2)
                out.add(
                    DeviceCalendar(
                        id = id,
                        label = name?.takeIf { it.isNotBlank() } ?: account ?: "Calendar",
                        account = account,
                    ),
                )
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Occurrences in the import window, flattened to day-and-minutes.
     *
     * `Instances` rather than `Events` on purpose: it is the provider's own expansion of
     * recurrence, so a weekly standup arrives as the weeks it actually falls on instead of
     * one row this app would have to expand itself.
     */
    fun events(
        context: Context,
        calendarId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ImportedEvent> = runCatching {
        val today = java.time.LocalDate.now(zone)
        val from = today.minusDays(DAYS_BACK).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(DAYS_FORWARD).atStartOfDay(zone).toInstant().toEpochMilli()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val out = mutableListOf<ImportedEvent>()
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Event"
                val begin = cursor.getLong(2)
                val end = cursor.getLong(3)
                val allDay = cursor.getInt(4) == 1

                if (allDay) {
                    // All-day rows are stored as midnight UTC by contract; reading them in
                    // the local zone is what puts a whole-day event on the day before.
                    val day = begin / 86_400_000L
                    out.add(
                        ImportedEvent(
                            uid = "device:$eventId:$begin",
                            title = title,
                            epochDay = day,
                        ),
                    )
                } else {
                    val start = java.time.Instant.ofEpochMilli(begin).atZone(zone)
                    val finish = java.time.Instant.ofEpochMilli(end).atZone(zone)
                    val startMinutes = start.hour * 60 + start.minute
                    val endMinutes = (finish.hour * 60 + finish.minute)
                        .takeIf { finish.toLocalDate() == start.toLocalDate() && it > startMinutes }
                    out.add(
                        ImportedEvent(
                            uid = "device:$eventId:$begin",
                            title = title,
                            epochDay = start.toLocalDate().toEpochDay(),
                            startMinutes = startMinutes,
                            endMinutes = endMinutes,
                        ),
                    )
                }
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
