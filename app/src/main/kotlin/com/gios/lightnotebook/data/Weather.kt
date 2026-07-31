package com.gios.lightnotebook.data

import android.content.Context
import com.gios.lightnotebook.util.WeatherCodes
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** One day's weather, as much as a diary wants. */
data class DayWeather(
    val epochDay: Long,
    val code: Int,
    val maxC: Double?,
    val minC: Double?,
    /**
     * Whether this is what happened or what was expected.
     *
     * The distinction is load-bearing rather than pedantic. A forecast cached for tomorrow becomes,
     * once tomorrow has been and gone, a record of *what was predicted* — which is not what a diary
     * is for. So a forecast is marked, and the nightly job replaces it with the observation once the
     * day is over. Without the flag there is no way to tell the two apart afterwards, and a day
     * would quietly remember the wrong weather forever.
     */
    val observed: Boolean,
) {
    val kind: WeatherCodes.Kind get() = WeatherCodes.kindOf(code)
}

/**
 * The weather, for days behind and days ahead.
 *
 * **Open-Meteo, and no API key.** That is why it is this and not one of the others: a journal that
 * needs an account to say it rained is a journal with a dependency on someone's billing. It also
 * has both halves — a forecast endpoint for days to come and an archive for days gone — which is
 * exactly the shape the calendar needs, since a past day and a future day are asking different
 * questions of the same field.
 *
 * **Cached on disk per day, permanently.** A past day's weather is a historical fact and can never
 * change, so it is fetched once ever. A forecast can, so days from today onwards are re-fetched when
 * the cached copy is more than a few hours old. Between those two rules a month of calendar costs
 * one request the first time it is panned over and nothing afterwards.
 *
 * This is a *lookup*, not a model, and it is the only network call the journal makes. It is also the
 * only part that cannot be done offline — the sky is not on the phone.
 */
class Weather(private val context: Context) {

    /**
     * What is already known. **The only thing a screen ever calls, and it never touches the network.**
     *
     * Opening a day or panning the planner does no work beyond reading a few tiny files. Everything
     * is fetched and archived ahead of time by [WeatherArchiveWorker], overnight and on a charger, so
     * a view is never waiting on the sky and a scroll never costs a request. A day with nothing
     * cached simply says nothing about the weather.
     */
    fun cached(fromDay: Long, toDay: Long): Map<Long, DayWeather> {
        val out = HashMap<Long, DayWeather>()
        for (day in fromDay..toDay) readCache(day)?.let { out[day] = it }
        return out
    }

    /**
     * Fill the archive. Called by the nightly worker and by nothing else.
     *
     * Two halves, because they are two endpoints and two different questions:
     *
     * - **Ahead**: the forecast for the next fortnight, always re-fetched, because that is what a
     *   forecast is. Marked as unobserved.
     * - **Behind**: the archive for any past day that is either missing *or* still holding a
     *   forecast. That second case is the one worth having: yesterday's cached forecast is replaced
     *   by what actually happened, so the day remembers the weather rather than the prediction.
     *
     * Blocking, and the caller is a worker on IO.
     */
    fun archive(latitude: Double, longitude: Double): Boolean {
        val today = LocalDate.now().toEpochDay()

        val ahead = load(FORECAST, today, today + FORECAST_DAYS, latitude, longitude, observed = false)

        // Only as far back as there is any point: a day nobody will scroll to is a day not worth a
        // request, and the archive endpoint lags real time by a day or two anyway.
        val stale = ((today - BACKFILL_DAYS) until today).filter { day ->
            val cached = readCache(day)
            cached == null || !cached.observed
        }
        val behind = if (stale.isEmpty()) {
            true
        } else {
            load(ARCHIVE, stale.min(), stale.max(), latitude, longitude, observed = true)
        }
        return ahead && behind
    }

    private fun load(
        host: String,
        fromDay: Long,
        toDay: Long,
        latitude: Double,
        longitude: Double,
        observed: Boolean,
    ): Boolean {
        val url = "$host?latitude=$latitude&longitude=$longitude" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto" +
            "&start_date=${LocalDate.ofEpochDay(fromDay)}&end_date=${LocalDate.ofEpochDay(toDay)}"

        return runCatching {
            val body = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Named, because Open-Meteo's terms ask for it and an anonymous flood is how a free
                // service stops being free for everyone.
                setRequestProperty("User-Agent", USER_AGENT)
                inputStream.bufferedReader().use { it.readText() }
            }
            val daily = JSONObject(body).optJSONObject("daily") ?: return@runCatching false
            val dates = daily.optJSONArray("time") ?: return@runCatching false
            val codes = daily.optJSONArray("weather_code")
            val maxes = daily.optJSONArray("temperature_2m_max")
            val mins = daily.optJSONArray("temperature_2m_min")

            for (i in 0 until dates.length()) {
                val day = runCatching { LocalDate.parse(dates.getString(i)).toEpochDay() }.getOrNull()
                    ?: continue
                val code = codes?.optInt(i, -1) ?: -1
                if (code < 0) continue
                writeCache(
                    DayWeather(
                        epochDay = day,
                        code = code,
                        maxC = maxes?.optDouble(i)?.takeIf { !it.isNaN() },
                        minC = mins?.optDouble(i)?.takeIf { !it.isNaN() },
                        observed = observed,
                    ),
                )
            }
            true
        }.getOrDefault(false)
        // No network, no service, a changed response shape: all of them are "we do not know what the
        // weather was", which is a thing a day is allowed not to say. The worker retries later.
    }

    /* ---- the cache is one tiny file per day, which is also its index ---- */

    private fun dir() = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(day: Long) = File(dir(), "$day.txt")

    private fun ageOf(day: Long) = System.currentTimeMillis() - fileFor(day).lastModified()

    private fun readCache(day: Long): DayWeather? = runCatching {
        val parts = fileFor(day).takeIf { it.isFile }?.readText()?.trim()?.split(',') ?: return null
        DayWeather(
            epochDay = day,
            code = parts[0].toInt(),
            maxC = parts.getOrNull(1)?.toDoubleOrNull(),
            minC = parts.getOrNull(2)?.toDoubleOrNull(),
            // Files written before the flag existed are forecasts as far as anyone knows, so the
            // nightly job will replace them with observations. Guessing "observed" would freeze a
            // prediction in place permanently.
            observed = parts.getOrNull(3) == OBSERVED,
        )
    }.getOrNull()

    private fun writeCache(weather: DayWeather) {
        runCatching {
            fileFor(weather.epochDay).writeText(
                listOf(
                    weather.code,
                    weather.maxC ?: "",
                    weather.minC ?: "",
                    if (weather.observed) OBSERVED else FORECAST_MARK,
                ).joinToString(","),
            )
        }
    }

    private companion object {
        const val DIR = "weather"
        const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        const val ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"
        const val TIMEOUT_MS = 10_000

        /** Named, as the service asks. */
        const val USER_AGENT = "LightNotebook/1 (github.com/gi-os/LightNotebook)"

        const val OBSERVED = "A"
        const val FORECAST_MARK = "F"

        /** Two weeks ahead. Past that a forecast is a guess about a guess. */
        const val FORECAST_DAYS = 14L

        /**
         * How far back the nightly job will fill in.
         *
         * Sixty days: enough that scrolling back through a couple of months of the journal finds
         * weather, and few enough that the first run is one request rather than a year of them.
         */
        const val BACKFILL_DAYS = 60L
    }
}
