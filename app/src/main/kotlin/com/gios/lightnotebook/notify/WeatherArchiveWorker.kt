package com.gios.lightnotebook.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        val ok = Weather(applicationContext).archive(repo.homeLatitude(), repo.homeLongitude())
        // Retry rather than fail: a phone on a charger overnight with no usable network is a normal
        // evening, and the archive is no worse off for waiting. WorkManager backs off on its own.
        if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "weather-archive"

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
    }
}
