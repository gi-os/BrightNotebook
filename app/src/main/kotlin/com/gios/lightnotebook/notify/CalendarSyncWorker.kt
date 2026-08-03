package com.gios.lightnotebook.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.Sync
import java.util.concurrent.TimeUnit

/**
 * The hourly calendar refresh — as work with a network constraint, not as an alarm.
 *
 * **This replaced an hourly `setAndAllowWhileIdle` alarm, and the reason is battery.** That alarm
 * woke the device every hour, started the process and attempted a fetch *regardless of whether
 * there was a network to fetch over, or any imported calendar to fetch* — so a fresh install with
 * no calendars woke the phone twenty-four times a day, forever, to do nothing. An alarm cannot
 * express "when there is a connection"; work can, and the system then folds the wakeup in with
 * everything else it was already going to do.
 *
 * Three further things come free with it: the schedule survives a reboot without a receiver to
 * re-arm it, `KEEP` makes re-scheduling on every launch a no-op instead of a moved alarm, and
 * WorkManager needs no Play Services, which is the only reason it is available on this phone at
 * all (see LightNews, which polls Gmail the same way).
 *
 * Punctuality was never wanted here. A calendar that is up to an hour and a half stale is a
 * calendar; the one thing in this app that genuinely has to be on time is a reminder, and that is
 * still an exact alarm in [Reminders].
 */
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // Nothing imported means nothing to do. Cheaper to ask than to run, and it also means an
        // install that never imports a calendar stops being woken at all.
        val calendars = runCatching { NotebookRepository(app).calendars() }.getOrDefault(emptyList())
        if (calendars.isEmpty()) {
            Log.i(TAG, "no imported calendars; nothing to refresh")
            return Result.success()
        }
        return runCatching {
            val result = Sync.run(app)
            Log.i(TAG, "refreshed ${result.calendars}, failed ${result.failed}")
            // Deliberately success even when a calendar failed: a retry would run the whole pass
            // again for one unreachable feed, and the next hour is soon enough.
            Result.success()
        }.getOrElse {
            Log.w(TAG, "sync failed: $it")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CalendarSyncWorker"
        private const val NAME = "calendar-sync"

        /**
         * Idempotent. [ExistingPeriodicWorkPolicy.KEEP] means calling this on every launch does
         * not reset the period — which the alarm version did, so an app opened often never
         * actually reached its next sync.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        // Connected, not unmetered: this is a few kilobytes of iCalendar, and a
                        // work calendar that only updates at home is not a work calendar.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { Log.w(TAG, "couldn't schedule: $it") }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}
