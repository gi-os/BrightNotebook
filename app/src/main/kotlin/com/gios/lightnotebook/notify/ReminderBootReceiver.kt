package com.gios.lightnotebook.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightnotebook.data.NotebookDatabase
import com.gios.lightnotebook.util.NoteDates

/**
 * Re-arms every future reminder after a reboot.
 *
 * Alarms do not survive one, and there is nothing to tell the user that they were lost —
 * the app simply goes quiet until it is next opened. The same routine runs on app launch,
 * because a force-stop also clears every alarm an app owns.
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val entries = NotebookDatabase.get(app).dao()
                    .entriesWithRemindersBlocking(NoteDates.today())
                Reminders.rearmAll(app, entries)
                Log.i(TAG, "re-armed ${entries.size} reminder(s) after boot")
            } catch (t: Throwable) {
                Log.w(TAG, "could not re-arm reminders: $t")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "ReminderBoot"
    }
}
