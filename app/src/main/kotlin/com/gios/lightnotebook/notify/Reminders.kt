package com.gios.lightnotebook.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.calendarZoneOf
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.Recurrence
import java.time.ZoneId

/**
 * Arming and cancelling reminders.
 *
 * `setExactAndAllowWhileIdle` is the only thing that will do here. A plain `set` is
 * batched to the nearest maintenance window, and `setAndAllowWhileIdle` — which is what
 * LightChat's poller uses — is throttled to roughly once every nine minutes while the
 * phone is idle. That is fine for "check for messages soon" and useless for "tell me at
 * 08:50", where being nine minutes late is the same as not firing.
 *
 * Exactness is paid for with a permission: `USE_EXACT_ALARM` on 33+ (granted at install,
 * and this is exactly the alarm-clock/calendar case it exists for) with
 * `SCHEDULE_EXACT_ALARM` declared for older builds. If it is somehow refused anyway the
 * call falls back to an inexact alarm rather than throwing — a late reminder beats none.
 *
 * Alarms do not survive a reboot, and a force-stop cancels every alarm an app owns, so
 * everything is re-armed from [ReminderBootReceiver] and again whenever the app starts.
 */
object Reminders {

    private const val TAG = "Reminders"
    const val EXTRA_ENTRY_ID = "entryId"

    /** The lead time offered in the UI, and the default for a new timed entry. */
    val LEAD_CHOICES = listOf(0, 5, 10, 30, 60)
    const val DEFAULT_LEAD_MINUTES = 10

    /**
     * When the reminder should fire, or null when it cannot: an entry with no time, one whose
     * moment has already passed, or a series that has run out. Pure arithmetic, so the awkward
     * part is testable.
     *
     * A repeating entry is armed for its **next** occurrence rather than its first, which is
     * usually in the past — the stored day is where the series began. Only one alarm is held at
     * a time, and the receiver arms the one after it as each fires.
     */
    fun triggerAtMillis(
        entry: DayEntryEntity,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val startMinutes = entry.startMinutes ?: return null
        val lead = entry.reminderMinutes ?: return null
        return candidateDays(entry, zone, now)
            .asSequence()
            .map { day ->
                NoteDates.of(day)
                    .atStartOfDay(zone)
                    .plusMinutes(startMinutes.toLong())
                    .toInstant()
                    .toEpochMilli() - lead * 60_000L
            }
            .firstOrNull { it > now }
    }

    /**
     * The days worth considering: the one day a plain entry happens on, or the next few
     * occurrences of a series.
     *
     * More than one, because today's occurrence may already have gone by — a daily standup
     * looked at in the afternoon should arm tomorrow's, not report that there is nothing left.
     * The window is a year and change, so a rule whose next occurrence is further away than that
     * simply has no alarm until the app is next opened, which is when everything is re-armed.
     */
    private fun candidateDays(entry: DayEntryEntity, zone: ZoneId, now: Long): List<Long> {
        if (!entry.repeats) return listOf(entry.epochDay)
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        return Recurrence.expand(
            rrule = entry.rrule,
            startDay = entry.epochDay,
            from = maxOf(today, entry.epochDay),
            to = today + REPEAT_LOOKAHEAD_DAYS,
            exDays = Recurrence.parseExDays(entry.exDays),
            cap = 4,
        )
    }

    /** How far ahead a repeating reminder will look for its next occurrence. */
    private const val REPEAT_LOOKAHEAD_DAYS = 400L

    /**
     * The zone an entry's clock time is written in, which is not always the phone's.
     *
     * A typed entry is local by construction: "9:30" means 9:30 on the clock the person was
     * looking at, which is the phone's. An **imported** one is not — an `.ics` carries instants,
     * and they were turned into a day and a clock time using the calendar zone, which on this
     * phone may have been overridden precisely because the device reports the wrong one. Arming
     * that row against the phone's zone converts it back with the wrong offset, and a reminder
     * hours early is one that has already passed by the time it is armed: [triggerAtMillis] finds
     * no candidate ahead of now and the event gets no alarm at all.
     *
     * `calendarId` is the whole test, and it is the same line drawn in
     * [com.gios.lightnotebook.data.NotebookRepository.calendarZoneId]. With no override set both
     * branches are the phone's zone, so this changes nothing on a phone that is telling the truth.
     */
    fun zoneFor(context: Context, entry: DayEntryEntity): ZoneId =
        if (entry.calendarId != null) calendarZoneOf(context) else ZoneId.systemDefault()

    fun schedule(context: Context, entry: DayEntryEntity) {
        val app = context.applicationContext
        val at = triggerAtMillis(entry, zoneFor(app, entry)) ?: run {
            dropAlarm(app, entry.id)
            return
        }
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app, entry.id)
        val armed = runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        if (armed.isFailure) {
            // Refused exact alarms: still better to be told a little late than not at all.
            Log.w(TAG, "exact alarm refused, falling back to inexact: ${armed.exceptionOrNull()}")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        }
    }

    /**
     * The entry is gone: take down its alarm *and* anything it has already put in the shade.
     */
    fun cancel(context: Context, entryId: String) {
        dropAlarm(context, entryId)
        Notifier.cancel(context.applicationContext, entryId)
    }

    /**
     * Take down the alarm and leave the shade alone.
     *
     * For the row a re-import is about to replace. The event still exists — it is being rewritten
     * under a new id — so a reminder that already fired for it half an hour ago is still a true
     * thing to have in the list, and clearing it would delete a notification the person has not
     * read yet.
     */
    fun dropAlarm(context: Context, entryId: String) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app, entryId))
    }

    /**
     * Re-arms everything still ahead of us. Idempotent: each entry owns one alarm.
     *
     * Hand it whatever [needsAlarm] keeps and nothing narrower. An entry with nothing left to fire
     * costs one cancelled alarm here, which is cheap; an entry wrongly left out costs a reminder.
     */
    fun rearmAll(context: Context, entries: List<DayEntryEntity>) {
        entries.forEach { schedule(context, it) }
    }

    /**
     * Whether a row is still worth arming — the in-memory pair of the `entriesWithReminders`
     * query, and it exists because the two disagreed.
     *
     * **A series' `epochDay` is the day it began**, which for a weekly meeting is months ago, so
     * `epochDay >= today` throws away exactly the entries most likely to have a reminder on them.
     * The DAO query knows that and carries an `OR rrule IS NOT NULL` clause; the hourly sync
     * filtered its freshly written rows by the day alone, so every repeating imported event lost
     * its alarm on the first sync after launch and only got one back the next time the app was
     * opened or the phone rebooted. Since the sync runs hourly, that is very nearly never.
     */
    fun needsAlarm(entry: DayEntryEntity, today: Long = NoteDates.today()): Boolean =
        entry.reminderMinutes != null && (entry.repeats || entry.lastDay >= today)

    private fun pendingIntent(app: Context, entryId: String): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            // The entry id, so each reminder has its own alarm slot rather than replacing
            // the previous one.
            Notifier.notificationId(entryId),
            Intent(app, ReminderReceiver::class.java)
                .setData(android.net.Uri.parse("lightnotebook://reminder/$entryId"))
                .putExtra(EXTRA_ENTRY_ID, entryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
