package com.gios.lightnotebook.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.gios.lightnotebook.util.NoteDates
import java.time.ZoneId

/**
 * Mirrors parsed events into the phone's own calendar, so a photographed wall planner
 * ends up somewhere the rest of LightOS can see it.
 *
 * Every function here is defensive on purpose. The Light Phone III ships without Play
 * Services and a fresh phone may have no writable calendar account at all, so the
 * provider legitimately returns nothing. The notebook's own day entries are the source
 * of truth; the system calendar is a bonus, and failing to write it is never an error
 * worth interrupting the user for.
 */
object SystemCalendar {

    private const val DEFAULT_DURATION_MINUTES = 60

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    /** The account the events will land in, for the confirmation line in the UI. */
    fun writableCalendarName(context: Context): String? =
        if (hasPermission(context)) pickCalendar(context)?.second else null

    /**
     * Inserts one event. Returns the provider's event id, or null when there is nowhere
     * to put it — caller keeps the notebook entry either way.
     */
    fun insert(
        context: Context,
        title: String,
        epochDay: Long,
        startMinutes: Int?,
        endMinutes: Int?,
        location: String? = null,
    ): Long? {
        if (!hasPermission(context)) return null
        val calendarId = pickCalendar(context)?.first ?: return null

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title.take(200))
            // So a location typed here survives leaving: an entry mirrored into the phone's
            // calendar and then read on a laptop should say where it is, like any other event.
            location?.trim()?.takeIf { it.isNotBlank() }?.let {
                put(CalendarContract.Events.EVENT_LOCATION, it.take(200))
            }
            if (startMinutes == null) {
                // All-day events are stored as midnight UTC by contract, not local time.
                val startUtc = epochDay * 86_400_000L
                put(CalendarContract.Events.DTSTART, startUtc)
                put(CalendarContract.Events.DTEND, startUtc + 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val zone = ZoneId.systemDefault()
                val midnight = NoteDates.of(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
                val start = midnight + startMinutes * 60_000L
                val endMin = endMinutes?.takeIf { it > startMinutes }
                    ?: (startMinutes + DEFAULT_DURATION_MINUTES)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, midnight + endMin * 60_000L)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
        }

        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?.let { ContentUris.parseId(it) }
        }.getOrNull()
    }

    fun delete(context: Context, eventId: Long): Boolean {
        if (!hasPermission(context)) return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    /**
     * First visible calendar the app is allowed to add to, preferring one whose owner
     * matches the account — that is the "my calendar" a person expects to write into.
     */
    private fun pickCalendar(context: Context): Pair<Long, String>? = runCatching {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { cursor ->
            var fallback: Pair<Long, String>? = null
            while (cursor.moveToNext()) {
                val access = cursor.getInt(4)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: cursor.getString(2) ?: "Calendar"
                val owner = cursor.getString(3)
                val account = cursor.getString(2)
                if (owner != null && owner == account) return@use id to name
                if (fallback == null) fallback = id to name
            }
            fallback
        }
    }.getOrNull()
}
