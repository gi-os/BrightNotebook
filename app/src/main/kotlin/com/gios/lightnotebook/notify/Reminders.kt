package com.gios.lightnotebook.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.util.NoteDates
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
     * When the reminder should fire, or null when it cannot: an entry with no time, or one
     * whose moment has already passed. Pure arithmetic, so the awkward part is testable.
     */
    fun triggerAtMillis(
        entry: DayEntryEntity,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val startMinutes = entry.startMinutes ?: return null
        val lead = entry.reminderMinutes ?: return null
        val start = NoteDates.of(entry.epochDay)
            .atStartOfDay(zone)
            .plusMinutes(startMinutes.toLong())
            .toInstant()
            .toEpochMilli()
        val at = start - lead * 60_000L
        return at.takeIf { it > now }
    }

    fun schedule(context: Context, entry: DayEntryEntity) {
        val app = context.applicationContext
        val at = triggerAtMillis(entry) ?: run {
            cancel(app, entry.id)
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

    fun cancel(context: Context, entryId: String) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app, entryId))
        Notifier.cancel(app, entryId)
    }

    /** Re-arms everything still ahead of us. Idempotent: each entry owns one alarm. */
    fun rearmAll(context: Context, entries: List<DayEntryEntity>) {
        entries.forEach { schedule(context, it) }
    }

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
