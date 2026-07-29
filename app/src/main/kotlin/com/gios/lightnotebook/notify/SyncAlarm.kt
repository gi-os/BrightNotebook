package com.gios.lightnotebook.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightnotebook.data.Sync
import kotlinx.coroutines.runBlocking

/**
 * Refreshes imported calendars roughly every hour.
 *
 * `setAndAllowWhileIdle` rather than the exact alarm reminders use: this one has nowhere to
 * be on time. It is the only alarm that fires at all during Doze, the system throttles it to
 * about one firing every nine minutes, and an hour is comfortably above that — no
 * `SCHEDULE_EXACT_ALARM` needed either. There is no repeating form of it, so each firing
 * arms the next.
 *
 * Re-armed from [ReminderBootReceiver] and on app launch, because alarms do not survive a
 * reboot and a force-stop silently clears them.
 */
class SyncAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Armed first, so a failure below cannot end the chain.
        schedule(app)
        val pending = goAsync()
        Thread {
            try {
                runBlocking { Sync.run(app) }
            } catch (t: Throwable) {
                Log.w(TAG, "hourly sync failed: $t")
            } finally {
                // Releases the broadcast's wakelock; without it the process is killed
                // mid-work after about ten seconds.
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "SyncAlarm"
        private const val INTERVAL_MS = 60 * 60 * 1000L

        /** Idempotent: one alarm, moved rather than duplicated. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val manager = app.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    intent(app),
                )
            }.onFailure { Log.w(TAG, "couldn't schedule: $it") }
        }

        fun cancel(context: Context) {
            val app = context.applicationContext
            app.getSystemService(AlarmManager::class.java)?.cancel(intent(app))
        }

        private fun intent(app: Context): PendingIntent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, SyncAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
