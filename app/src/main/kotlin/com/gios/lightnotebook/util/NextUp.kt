package com.gios.lightnotebook.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The single next thing, for another app's lock face.
 *
 * BrightControl asks this app's `nextup` provider "what is next", and the answer has to be
 * one row or none. The choosing is here, Android-free, because it is exactly the kind of
 * logic that goes quietly wrong at a boundary — midnight, the 48-hour horizon, a day with
 * only an all-day thing on it — and a provider cannot be unit-tested off-device.
 *
 * The rules, in order:
 *  1. Only things inside the horizon count. For a timed thing that is its actual start
 *     instant, strictly after now — something that started a minute ago is not *next*.
 *  2. **A timed thing beats every all-day thing**, however far away it is inside the
 *     horizon: "DENTIST 15:30" is what a glance at a lock face is for, and a birthday never
 *     starts, so it cannot be next in the way an appointment is.
 *  3. Among timed things, the earliest wins; ties break by title so the answer is stable.
 *  4. With no timed thing at all, the nearest all-day thing stands in — today's before
 *     tomorrow's — carrying its local midnight as [Pick.startAt] and `allDay` set.
 */
object NextUp {

    /** How far ahead the lock face looks. Two days: "next", not "this month". */
    const val HORIZON_MS: Long = 48L * 60 * 60 * 1000

    /** One thing that could be next. [minutes] is clock minutes from local midnight, or null. */
    data class Candidate(
        val epochDay: Long,
        val minutes: Int?,
        val title: String,
        /** `event`, `reminder` or `ticket` — the contract's three words, decided by the caller. */
        val kind: String,
    )

    /** The one row the provider serves. [startAt] is epoch millis. */
    data class Pick(
        val startAt: Long,
        val title: String,
        val kind: String,
        val allDay: Boolean,
    )

    fun pick(
        candidates: List<Candidate>,
        nowMs: Long,
        zone: ZoneId,
        horizonMs: Long = HORIZON_MS,
    ): Pick? {
        val until = nowMs + horizonMs

        val timed = candidates.asSequence()
            .filter { it.minutes != null && it.title.isNotBlank() }
            .map { it to startAtMs(it.epochDay, it.minutes ?: 0, zone) }
            .filter { (_, start) -> start > nowMs && start <= until }
            .sortedWith(compareBy({ it.second }, { it.first.title }))
            .firstOrNull()
        if (timed != null) {
            val (candidate, start) = timed
            return Pick(startAt = start, title = candidate.title, kind = candidate.kind, allDay = false)
        }

        // No timed plans: the nearest whole-day thing stands in. Today's own all-day entry
        // counts even though its midnight has passed — it is still today's business, which is
        // what a lock face is glancing for.
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        val allDay = candidates.asSequence()
            .filter { it.minutes == null && it.title.isNotBlank() }
            .filter { it.epochDay >= today && dayStartMs(it.epochDay, zone) <= until }
            .sortedWith(compareBy({ it.epochDay }, { it.title }))
            .firstOrNull()
            ?: return null
        return Pick(
            startAt = dayStartMs(allDay.epochDay, zone),
            title = allDay.title,
            kind = allDay.kind,
            allDay = true,
        )
    }

    /**
     * A clock time on a date, as an instant — through `ZonedDateTime` so a start inside a
     * spring-forward gap shifts the way the wall clock does instead of landing an hour off.
     */
    fun startAtMs(epochDay: Long, clockMinutes: Int, zone: ZoneId): Long {
        val minutes = clockMinutes.coerceIn(0, 24 * 60 - 1)
        return LocalDate.ofEpochDay(epochDay)
            .atTime(LocalTime.of(minutes / 60, minutes % 60))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun dayStartMs(epochDay: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
}
