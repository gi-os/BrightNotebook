package com.gios.lightnotebook.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which day a photograph belongs to.
 *
 * Free of Android imports so it unit tests off-device, and separate from the MediaStore
 * query for one reason: **every hard part of "photos on this day" is arithmetic, not
 * querying.** MediaStore hands back two timestamps in two different units, one of which is
 * frequently a lie, and a calendar square is a *local* day while both timestamps are UTC.
 * Get either of those wrong and the feature is subtly, unfalsifiably broken — an evening
 * photograph shows up on tomorrow, which looks like a photo that vanished.
 *
 * The rules, each of which cost something to learn:
 *
 * 1. **`DATE_TAKEN` is milliseconds; `DATE_ADDED` is seconds.** They sit next to each other
 *    in the same cursor and differ by a factor of 1000. Mixing them puts a 2026 photograph
 *    in 1970.
 * 2. **`DATE_TAKEN` is often absent, 0, or written in the wrong unit** by whatever app saved
 *    the file — it comes from EXIF, and a screenshot has no EXIF. So it is a *preference*,
 *    not a source of truth, and it has to be sanity-checked before it is believed rather
 *    than after.
 * 3. **A calendar cell is a local day.** `epochDay = instant / 86_400_000` is UTC, and in New
 *    York that is four or five hours late: every photograph taken after 8pm lands on the
 *    following square. The conversion has to go through a real zone, and it has to be the
 *    same zone the rest of the notebook uses ([[NoteDates]] is local too).
 * 4. **A day's window is not 24 hours.** On a DST boundary it is 23 or 25, so the range for
 *    a query is built from midnight-to-midnight in the zone, never from a day count times a
 *    constant.
 */
object PhotoDays {

    /**
     * The earliest `DATE_TAKEN` worth believing, in milliseconds: 2001-09-09.
     *
     * This is the one number doing real work here. Below it a value cannot be a plausible
     * photo date in milliseconds, but it is a perfectly plausible one in *seconds* — which
     * is exactly the corruption seen in the wild, because an app wrote a Unix timestamp into
     * a column documented in millis. Anything under the threshold is therefore not trusted
     * as millis, and the seconds reading is tried instead.
     */
    const val PLAUSIBLE_MS = 1_000_000_000_000L

    /** The upper guard: a timestamp far in the future is a bug, not a photograph. */
    const val IMPLAUSIBLE_MS = 32_503_680_000_000L // 3000-01-01

    /**
     * One trustworthy instant in milliseconds from the two columns MediaStore offers, or
     * null when neither can be believed.
     *
     * Order is deliberate: `DATE_TAKEN` first because it is when the photograph was *taken*,
     * which is what a calendar is asking; `DATE_ADDED` only as a fallback, because for a
     * received or copied file it is when it arrived on this phone, not when it was made.
     */
    fun instantMs(dateTakenMs: Long?, dateAddedSec: Long?): Long? {
        val taken = dateTakenMs ?: 0L
        if (taken in PLAUSIBLE_MS until IMPLAUSIBLE_MS) return taken
        // A DATE_TAKEN too small to be millis may still be seconds — the common corruption.
        if (taken > 0L && taken * 1000L in PLAUSIBLE_MS until IMPLAUSIBLE_MS) return taken * 1000L
        val added = dateAddedSec ?: 0L
        if (added > 0L && added * 1000L in PLAUSIBLE_MS until IMPLAUSIBLE_MS) return added * 1000L
        return null
    }

    /** The local calendar day an instant falls on. */
    fun localEpochDay(instantMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate().toEpochDay()

    /**
     * The half-open millisecond window covering [fromDay]..[toDay] inclusive, in [zone].
     *
     * Half-open on purpose: the end is the *start* of the day after, so there is no
     * last-millisecond-of-the-day to get wrong, and a photo at exactly midnight belongs to
     * one day rather than two.
     */
    fun windowMs(fromDay: Long, toDay: Long, zone: ZoneId): LongRange {
        val lo = min(fromDay, toDay)
        val hi = max(fromDay, toDay)
        val start = LocalDate.ofEpochDay(lo).atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusive = LocalDate.ofEpochDay(hi + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until endExclusive
    }

    /**
     * Whether a row read out of the cursor belongs in [fromDay]..[toDay].
     *
     * The query's `WHERE` cannot do this alone. It has to be loose — an `OR` across both
     * timestamp columns in two different units — because a row whose `DATE_TAKEN` is 0 must
     * still be considered on its `DATE_ADDED`. So the SQL over-selects and this decides, with
     * the same rules that decided which day to file it under. One place, one answer.
     */
    fun dayIfWithin(dateTakenMs: Long?, dateAddedSec: Long?, fromDay: Long, toDay: Long, zone: ZoneId): Long? {
        val ms = instantMs(dateTakenMs, dateAddedSec) ?: return null
        val day = localEpochDay(ms, zone)
        return day.takeIf { it >= min(fromDay, toDay) && it <= max(fromDay, toDay) }
    }

    private fun min(a: Long, b: Long) = if (a <= b) a else b
    private fun max(a: Long, b: Long) = if (a >= b) a else b
}
