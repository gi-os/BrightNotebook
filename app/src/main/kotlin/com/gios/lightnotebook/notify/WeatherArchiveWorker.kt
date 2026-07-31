package com.gios.lightnotebook.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Fills the weather archive overnight, so no screen ever waits on it.
 *
 * **Nothing in this app fetches weather while you are looking at it.** Opening a day or panning the
 * planner reads a few tiny files and stops. Everything is prepared here, in advance, once a day:
 * the fortnight ahead as a forecast, and every recent day that is still holding a forecast gets
 * replaced with what actually happened. That last part is the reason this is a job and not a lazy
 * load — a prediction cached for tomorrow has to be *corrected* the day after, and only something
 * running on its own can do that.
 *
 * **While charging, and unmetered.** `setRequiresCharging` is the whole reason for WorkManager here
 * rather than the `setAndAllowWhileIdle` alarms this app uses elsewhere: it is a constraint the
 * system enforces through Doze, where an alarm would mean checking the charger by hand and
 * rescheduling every time the phone was unplugged. Unmetered because this is a nicety, and a nicety
 * should never spend anyone's data.
 */
class WeatherArchiveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = NotebookRepository(applicationContext)
        val everything = inputData.getBoolean(KEY_REFETCH, false)
        val reachBack = inputData.getBoolean(KEY_ALL_DATA, false)

        val result = Weather(applicationContext).archive(
            latitude = repo.homeLatitude(),
            longitude = repo.homeLongitude(),
            // Asked by hand: go back as far as the journal has anything at all. The nightly run
            // stays with its own modest reach, because it happens every night anyway.
            earliestDay = if (reachBack) repo.earliestRecordedDay() else null,
            refetch = everything,
        )
        // The count goes back out so a button can say what it did. A job that finishes silently is
        // indistinguishable from one that never started, which is exactly how this looked.
        val output = Data.Builder().putInt(KEY_DAYS_ADDED, result.daysAdded).build()
        // Retry rather than fail: a phone on a charger overnight with no usable network is a normal
        // evening, and the archive is no worse off for waiting. WorkManager backs off on its own.
        if (result.ok) Result.success(output) else Result.retry()
    }

    companion object {
        private const val NAME = "weather-archive"
        private const val NOW = "weather-archive-now"
        private const val KEY_REFETCH = "refetch"
        private const val KEY_ALL_DATA = "all_data"
        const val KEY_DAYS_ADDED = "days_added"

        /** The unique name of the by-hand run, so its progress can be watched. */
        const val NOW_NAME = NOW

        /**
         * Ask for it once a day.
         *
         * `KEEP`, not `UPDATE`: this runs on every launch, and replacing the request each time would
         * reset the period and mean a phone opened often never reaches the end of an interval. The
         * only thing that should replace it is a change to the work itself.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WeatherArchiveWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        /**
         * Run it now, because someone asked.
         *
         * **No charging constraint.** The nightly job waits for a charger because it is nobody's
         * priority; a person tapping a button in Settings has made it theirs, and a job that
         * silently waits for a cable is a button that appears to do nothing. Still requires a
         * network, since without one there is nothing to do but fail.
         *
         * `REPLACE`, so tapping twice does not queue two of them — the second ask is the one that
         * matters, and two overlapping archive runs would fetch everything twice.
         */
        fun runNow(context: Context, refetchEverything: Boolean) {
            val request = OneTimeWorkRequestBuilder<WeatherArchiveWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(
                    Data.Builder()
                        .putBoolean(KEY_REFETCH, refetchEverything)
                        .putBoolean(KEY_ALL_DATA, true)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
