package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A day, as a person means it.
 *
 * **A day ends when you go to bed, not at midnight.** A photograph taken at one in the morning
 * belongs to the night you were having, not to the morning you had not started yet — and a journal
 * that files it under the new date is describing a day nobody lived. So a journal day runs from a
 * cutover hour to the same hour the next date, and everything on this screen agrees about it:
 * photographs, notes, entries, steps, screen time, the now line, the bookends.
 *
 * That last part is the reason this is one small object rather than a constant sprinkled about. Five
 * different places already ask "which day does this instant belong to", and if any one of them keeps
 * answering "midnight" the day quietly disagrees with itself — a photograph in the strip that the
 * step graph does not count, a note on a day whose bookends exclude it.
 *
 * The **date** is unchanged: journal day 20300 is still the calendar date 20300, and the planner's
 * grid still says 30 July. Only its bounds move.
 */
object JournalDay {

    /**
     * When one day becomes the next.
     *
     * Four in the morning, which is late enough to catch an ordinary late night and early enough
     * that it is before almost anyone's alarm. It is a compromise rather than a truth — the honest
     * version is the gap in your own activity, and this app can see that gap once usage access is
     * granted, so detecting it per night is a sensible refinement later. A fixed hour has the
     * advantage of being right about the *same* thing every day, including days with no data at all.
     */
    const val DEFAULT_CUTOVER_HOUR = 4

    /** Roughly a day's worth of minutes; the real length varies with the clocks. */
    const val NOMINAL_MINUTES = 24 * 60

    /**
     * When a journal day begins.
     *
     * Resolved as a **wall-clock time**, not as midnight plus four hours. On a spring-forward day
     * those are different: adding four real hours to midnight lands at five in the morning, whereas
     * what is wanted is four o'clock as the clock on the wall reads it. `ZonedDateTime` also handles
     * the two awkward cases for free — an hour that does not exist shifts forward, an hour that
     * happens twice takes the first.
     */
    fun startMs(epochDay: Long, zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Long =
        startOf(epochDay, zone, cutoverHour).toInstant().toEpochMilli()

    private fun startOf(epochDay: Long, zone: ZoneId, cutoverHour: Int): ZonedDateTime =
        LocalDate.ofEpochDay(epochDay)
            .atTime(LocalTime.of(cutoverHour.coerceIn(0, 23), 0))
            .atZone(zone)

    /**
     * The half-open window a journal day covers.
     *
     * Half-open so the cutover instant belongs to exactly one day, and built from the *next* day's
     * start rather than by adding twenty-four hours — a day is 23 or 25 hours twice a year, and the
     * zone is the only thing that knows which.
     */
    fun windowMs(epochDay: Long, zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): LongRange {
        val start = startMs(epochDay, zone, cutoverHour)
        val end = startMs(epochDay + 1, zone, cutoverHour)
        return start until end
    }

    /** How long a journal day actually is, which is not always a day. */
    fun lengthMs(epochDay: Long, zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Long {
        val window = windowMs(epochDay, zone, cutoverHour)
        return window.last + 1 - window.first
    }

    /**
     * Which journal day an instant belongs to.
     *
     * The whole point of the file, in one line: anything before the cutover belongs to the date
     * before. One in the morning on the 31st is the 30th's night.
     */
    fun dayOf(instantMs: Long, zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Long {
        val local = java.time.Instant.ofEpochMilli(instantMs).atZone(zone)
        val date = local.toLocalDate()
        // Compared against the cutover *on that date*, not against the hour number, so a date whose
        // cutover hour does not exist still resolves — the zone shifted it, and this follows.
        return if (local.toInstant().toEpochMilli() < startMs(date.toEpochDay(), zone, cutoverHour)) {
            date.toEpochDay() - 1
        } else {
            date.toEpochDay()
        }
    }

    /**
     * How far into its journal day an instant sits, in minutes.
     *
     * This is what the vertical axis of a planner cell means, and what the now line and the day's
     * bookends are measured in. Zero is the cutover — so the top of a cell is four in the morning,
     * and a night owl's activity runs down the cell instead of falling off the bottom of one day and
     * reappearing at the top of the next.
     */
    fun minutesInto(
        instantMs: Long,
        epochDay: Long,
        zone: ZoneId,
        cutoverHour: Int = DEFAULT_CUTOVER_HOUR,
    ): Int {
        val start = startMs(epochDay, zone, cutoverHour)
        val minutes = ((instantMs - start) / 60_000L).toInt()
        val length = (lengthMs(epochDay, zone, cutoverHour) / 60_000L).toInt()
        return minutes.coerceIn(0, (length - 1).coerceAtLeast(0))
    }

    /** The clock time a minute-offset corresponds to, for labelling. */
    fun clockMinutes(minutesInto: Int, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Int =
        (minutesInto + cutoverHour * 60) % NOMINAL_MINUTES

    /**
     * The inverse: how far into the journal day a wall-clock time sits.
     *
     * Needed because the two halves of a day arrive in different units. Anything derived from an
     * instant — a photograph, a place, a pickup — is measured from the cutover by
     * [minutesInto]. A calendar entry is not derived from an instant at all: it is a time
     * somebody typed or an importer resolved, stored as minutes from midnight, deliberately, so
     * that no timezone can move it out of its square. Sorting the two together without
     * converting puts every entry four hours later than it belongs, which is the bug this
     * exists to close.
     *
     * A time before the cutover wraps to the **end** of the journal day, which is correct: on a
     * day that runs 4am to 4am, two in the morning is the tail of it, not the start.
     */
    fun fromClockMinutes(clockMinutes: Int, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Int =
        ((clockMinutes - cutoverHour * 60) % NOMINAL_MINUTES + NOMINAL_MINUTES) % NOMINAL_MINUTES

    /** The journal day containing now. */
    fun today(zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Long =
        dayOf(System.currentTimeMillis(), zone, cutoverHour)

    /** How far into today we are, for the now line. */
    fun nowMinutes(zone: ZoneId, cutoverHour: Int = DEFAULT_CUTOVER_HOUR): Int {
        val now = System.currentTimeMillis()
        return minutesInto(now, dayOf(now, zone, cutoverHour), zone, cutoverHour)
    }
}
