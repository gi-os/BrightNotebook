package com.gios.lightnotebook.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.gios.lightnotebook.util.PhotoDays
import com.gios.lightnotebook.util.Steps
import java.time.ZoneId

/**
 * Steps per day, kept because nothing else keeps them.
 *
 * `TYPE_STEP_COUNTER` reports paces since the last boot and nothing else. There is no history to
 * query, no Health Connect on this phone and no Play Services to ask — so a day is only ever
 * knowable if this app *watched* it, and days before you installed it are permanently blank. That
 * is the hardware interface, not a shortcoming of this file, and the UI says so rather than
 * showing a zero that looks like you did not move.
 *
 * Stored in `SharedPreferences`, one key per day, deliberately. A table would mean a Room migration
 * for a handful of integers, and this app's migrations are hand-written on purpose — the cost is not
 * worth it for data that is a few hundred bytes a year.
 */
class StepStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    fun stepsOn(epochDay: Long): Int? =
        prefs.getInt(key(epochDay), -1).takeIf { it >= 0 }

    /** Whether anything has ever been recorded, so a blank day can explain itself. */
    fun everRecorded(): Boolean = prefs.contains(KEY_LAST_COUNTER)

    /**
     * Read the counter once and fold the difference into the days it belongs to.
     *
     * Registered and unregistered around a single reading rather than left listening: the counter is
     * cumulative, so one sample carries everything since the last one, and a listener held open for
     * the life of the process would wake this app up all day to learn nothing it cannot learn on
     * arrival. Called when the app opens and from the daily alarm.
     */
    fun sample(onDone: (() -> Unit)? = null) {
        if (!granted()) {
            onDone?.invoke()
            return
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            onDone?.invoke()
            return
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                manager.unregisterListener(this)
                record(event.values.firstOrNull()?.toLong() ?: return, System.currentTimeMillis())
                onDone?.invoke()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // SENSOR_DELAY_UI, not FASTEST: the first callback is what we want and it arrives either
        // way, and a faster rate only means more callbacks before the unregister lands.
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun record(counter: Long, atMs: Long) {
        val previousCounter = prefs.getLong(KEY_LAST_COUNTER, -1L)
        val previousAt = prefs.getLong(KEY_LAST_AT, -1L)

        // The first sample of all establishes a baseline and adds nothing. Anything else would
        // credit today with every step since the phone last booted, which could have been a week
        // ago — the counter's zero is a reboot, not a midnight.
        if (previousCounter < 0 || previousAt < 0) {
            prefs.edit().putLong(KEY_LAST_COUNTER, counter).putLong(KEY_LAST_AT, atMs).apply()
            return
        }

        val zone = ZoneId.systemDefault()
        val attribution = Steps.attribute(
            previousCounter = previousCounter,
            previousAtMs = previousAt,
            counter = counter,
            atMs = atMs,
            hourStartMs = { hour -> hourStart(hour, zone) },
            hourOf = { ms -> hourOf(ms, zone) },
            nextHour = { hour -> nextHour(hour, zone) },
        )

        val edit = prefs.edit().putLong(KEY_LAST_COUNTER, counter).putLong(KEY_LAST_AT, atMs)
        attribution.perHour.forEach { (hour, steps) ->
            edit.putInt(hourKey(hour), (prefs.getInt(hourKey(hour), 0)) + steps)
        }
        attribution.perDay.forEach { (day, steps) ->
            // The day total is stored as well as derived, so drawing a month of totals does not
            // mean reading twenty-four keys per day.
            edit.putInt(key(day), (stepsOn(day) ?: 0) + steps)
        }
        edit.apply()
    }

    /** Steps by hour of a day, for the graph that shows a walk as a walk. */
    fun hoursOn(epochDay: Long): List<Int> {
        val zone = ZoneId.systemDefault()
        val length = dayLengthHours(epochDay, zone)
        return (0 until length).map { prefs.getInt(hourKey(Steps.Hour(epochDay, it)), 0) }
    }

    /* ---- hour arithmetic, all of it derived from the day's real bounds ---- */

    private fun dayStart(epochDay: Long, zone: ZoneId) =
        PhotoDays.windowMs(epochDay, epochDay, zone).first

    private fun dayLengthHours(epochDay: Long, zone: ZoneId): Int {
        val window = PhotoDays.windowMs(epochDay, epochDay, zone)
        // 23 on a spring-forward morning, 25 on a fall-back one. Rounded up so a partial hour
        // still has a bucket to land in.
        return (((window.last + 1 - window.first) + HOUR_MS - 1) / HOUR_MS).toInt()
    }

    private fun hourOf(ms: Long, zone: ZoneId): Steps.Hour {
        val day = PhotoDays.localEpochDay(ms, zone)
        val offset = ms - dayStart(day, zone)
        return Steps.Hour(day, (offset / HOUR_MS).toInt().coerceAtLeast(0))
    }

    private fun hourStart(hour: Steps.Hour, zone: ZoneId) =
        dayStart(hour.epochDay, zone) + hour.hour * HOUR_MS

    private fun nextHour(hour: Steps.Hour, zone: ZoneId): Steps.Hour {
        val length = dayLengthHours(hour.epochDay, zone)
        return if (hour.hour + 1 < length) {
            Steps.Hour(hour.epochDay, hour.hour + 1)
        } else {
            Steps.Hour(hour.epochDay + 1, 0)
        }
    }

    private fun hourKey(hour: Steps.Hour) = KEY_HOUR_PREFIX + hour.epochDay + "_" + hour.hour

    private fun key(epochDay: Long) = KEY_DAY_PREFIX + epochDay

    private companion object {
        const val PREFS = "lightnotebook_steps"
        const val KEY_DAY_PREFIX = "day_"
        const val KEY_HOUR_PREFIX = "h_"
        const val HOUR_MS = 3_600_000L
        const val KEY_LAST_COUNTER = "last_counter"
        const val KEY_LAST_AT = "last_at"
    }
}
