package com.gios.lightnotebook.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.gios.lightnotebook.data.NotebookDatabase
import com.gios.lightnotebook.ui.ReminderAlertActivity
import com.gios.lightnotebook.util.NoteDates

/**
 * A reminder came due.
 *
 * Three things happen, in decreasing order of how likely they are to work:
 *
 *  1. **A notification**, always. It is the record — it stays in LightOS's list, and it is
 *     what LightGlance's dots read.
 *  2. **A buzz**, once the entry has been read back and turns out to still exist. It used to be
 *     the first line of `onReceive`, before the database was touched, on the reasoning that a buzz
 *     should not depend on a read that might be slow. That was wrong in one case that turned out
 *     to be common: an alarm whose row has since been rewritten by a calendar re-import buzzed the
 *     phone and then had nothing to post.
 *  3. **A box that lights the panel** — BrightControl's, if it has claimed the on-screen box
 *     for every app (see [AlertOwner]); otherwise [ReminderAlertActivity], if the phone will allow a
 *     background activity start. On Android 14 that needs the `SYSTEM_ALERT_WINDOW`
 *     appop, which LightOS has no settings screen for, so it is adb-only and one-time:
 *
 *         adb shell appops set com.gios.lightnotebook SYSTEM_ALERT_WINDOW allow
 *
 *     Without it the first two still happen. This is the same arrangement as LightChat's
 *     heads-up box, for the same reason: an overlay window sits *below* the keyguard and
 *     cannot wake the screen, so only an activity with `turnScreenOn` will do.
 *
 * The database read uses [goAsync]: the broadcast's wakelock is released the moment
 * `onReceive` returns, which is not long enough to open Room.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val entryId = intent.getStringExtra(Reminders.EXTRA_ENTRY_ID) ?: return

        val pending = goAsync()
        Thread {
            try {
                val entry = NotebookDatabase.get(app).dao().let { dao ->
                    runCatching { dao.getDayEntryBlocking(entryId) }.getOrNull()
                }
                if (entry == null) {
                    // Not a buzz. An alarm can outlive its row -- a re-import rewrites every event
                    // under a new id -- and a phone that buzzes with nothing to show for it is
                    // worse than one that stays quiet. This is why the buzz moved below the read.
                    Log.d(TAG, "reminder for an entry that no longer exists")
                    return@Thread
                }
                val time = NoteDates.clock(entry.startMinutes)
                val lead = entry.reminderMinutes ?: 0
                val subtitle = when {
                    time == null -> NoteDates.dayTitle(entry.epochDay)
                    lead <= 0 -> "Now · $time"
                    else -> "In $lead min · $time"
                }
                Notifier.buzz(app)
                Notifier.post(app, entry.id, entry.text, subtitle, entry.epochDay)
                showBox(app, entry.text, subtitle, entry.epochDay)
                // A series holds one alarm at a time, so the next one is armed as this one
                // fires. Without this a weekly meeting would be reminded about exactly once,
                // until something else — a launch, a reboot — re-armed everything.
                if (entry.repeats) Reminders.schedule(app, entry)
            } catch (t: Throwable) {
                Log.w(TAG, "reminder failed: $t")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun showBox(app: Context, title: String, subtitle: String, epochDay: Long) {
        // BrightControl draws this box for every app now, off the notification posted a moment
        // ago — and its tap sends that notification's own intent, which is this app's open-the-day
        // intent. Drawing ours as well is the same reminder twice, one box on top of the other.
        if (AlertOwner.ownedElsewhere(app)) {
            Log.d(TAG, "BrightControl owns the box; notification only")
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            // Expected on a phone that was never plugged into a computer. The notification
            // and the buzz already went out, so this is not an error.
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted; notification only")
            return
        }
        val intent = Intent(app, ReminderAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(ReminderAlertActivity.EXTRA_TITLE, title)
            .putExtra(ReminderAlertActivity.EXTRA_SUBTITLE, subtitle)
            .putExtra(Notifier.EXTRA_EPOCH_DAY, epochDay)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "background activity start refused: $it") }
    }

    private companion object {
        const val TAG = "ReminderReceiver"
    }
}
