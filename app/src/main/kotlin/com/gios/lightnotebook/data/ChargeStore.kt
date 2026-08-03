package com.gios.lightnotebook.data

import android.content.Context
import android.util.Log
import com.gios.lightnotebook.util.Charging
import java.io.File

/**
 * The plug-and-unplug log. One line per event, one file per month.
 *
 * Charging is the one thing here that has to be *recorded* rather than queried: Android keeps no
 * history of it, so an event not written when it happens is gone. That makes this the cheapest
 * possible writer — an append of about twenty bytes, from a broadcast the system was already
 * sending, with no service, no alarm and no polling. If nothing ever plugs in, nothing ever runs.
 *
 * A month per file rather than a day per file (which is what [Weather] does) because a charge
 * spans midnight far more often than not: keeping a night in one file means the pairing in
 * [Charging.spansIn] never has to open two.
 *
 * Plain text on purpose, like every other store in this app: `cat 2026-08.txt` is the recovery
 * path, and a format you can read is a format you can fix.
 */
class ChargeStore(private val context: Context) {

    /** Appends one event. Called from a broadcast receiver, so it must be quick and must not throw. */
    fun record(atMs: Long, plugged: Boolean) {
        runCatching {
            val file = fileFor(atMs)
            file.parentFile?.mkdirs()
            file.appendText("$atMs,${if (plugged) 1 else 0}\n")
            trim()
        }.onFailure { Log.w(TAG, "could not record charge: $it") }
    }

    /**
     * Events overlapping a window, plus the last one before it.
     *
     * That trailing event is not optional: a night's charge seen from the morning is an unplug
     * with no plug inside the window, and without knowing the state at the boundary the whole
     * span is invisible.
     */
    fun eventsAround(windowStartMs: Long, windowEndMs: Long): List<Charging.Event> {
        val all = read(windowStartMs, windowEndMs)
        val inside = all.filter { it.atMs in windowStartMs..windowEndMs }
        val before = all.filter { it.atMs < windowStartMs }.maxByOrNull { it.atMs }
        return (listOfNotNull(before) + inside).sortedBy { it.atMs }
    }

    /** Reads the month a window falls in, and the one before it in case the window straddles. */
    private fun read(windowStartMs: Long, windowEndMs: Long): List<Charging.Event> {
        val files = setOf(fileFor(windowStartMs - MONTH_MS), fileFor(windowStartMs), fileFor(windowEndMs))
        return files.filter { it.exists() }.flatMap { file ->
            runCatching {
                file.readLines().mapNotNull { line ->
                    val parts = line.split(',')
                    val at = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
                    val plugged = parts.getOrNull(1)?.trim() == "1"
                    Charging.Event(
                        at,
                        if (plugged) Charging.Kind.Plugged else Charging.Kind.Unplugged,
                    )
                }
            }.getOrDefault(emptyList())
        }.sortedBy { it.atMs }
    }

    /** True once anything has ever been written, so a quiet day can be told from a new install. */
    fun everRecorded(): Boolean = dir().listFiles()?.any { it.length() > 0 } == true

    /** A year of months is a few kilobytes; beyond that there is no reason to keep them. */
    private fun trim() {
        val files = dir().listFiles()?.sortedBy { it.name } ?: return
        files.dropLast(KEEP_MONTHS).forEach { it.delete() }
    }

    private fun dir(): File = File(context.filesDir, "charge")

    private fun fileFor(atMs: Long): File {
        val month = java.time.Instant.ofEpochMilli(atMs)
            .atZone(java.time.ZoneId.systemDefault())
            .let { "%04d-%02d".format(it.year, it.monthValue) }
        return File(dir(), "$month.txt")
    }

    private companion object {
        const val TAG = "ChargeStore"
        const val KEEP_MONTHS = 13
        const val MONTH_MS = 31L * 24 * 60 * 60 * 1000
    }
}
