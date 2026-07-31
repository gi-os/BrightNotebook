package com.gios.lightnotebook.util

import java.time.LocalDate

/**
 * The same date, in the years before it.
 *
 * The one feature that turns a log into something worth keeping: a day is more interesting for
 * what it sits on top of. Entirely local — the photographs are already on the phone and the
 * entries are already in the database, so looking back costs a query and nothing else.
 *
 * Android-free, because the only hard part is the calendar arithmetic and there is one date in the
 * year that breaks the obvious version of it.
 */
object OnThisDay {

    /** How far back to look. Ten years of a phone is more than anyone has photographs for. */
    const val DEFAULT_YEARS_BACK = 10

    data class PastDay(val yearsAgo: Int, val epochDay: Long, val year: Int)

    /**
     * The same month and day in each of the previous [yearsBack] years, nearest first.
     *
     * **29 February is why this is a function and not a subtraction.** `epochDay - 365 * n` drifts
     * by a day every leap year, so the "same date" quietly becomes the day before; and going
     * through `LocalDate.minusYears` on a 29th lands on the 28th of a non-leap year, which is a
     * *different date* being presented as the same one. Those years are skipped instead — a leap
     * day has no anniversary in a year that does not have one, and showing the 28th's photographs
     * on the 29th is worse than showing none.
     */
    fun priorYears(epochDay: Long, yearsBack: Int = DEFAULT_YEARS_BACK): List<PastDay> {
        val date = LocalDate.ofEpochDay(epochDay)
        val out = ArrayList<PastDay>(yearsBack)
        for (n in 1..yearsBack) {
            val year = date.year - n
            val candidate = runCatching { LocalDate.of(year, date.monthValue, date.dayOfMonth) }
                .getOrNull() ?: continue
            // The guard that matters: minusYears would have silently returned the 28th.
            if (candidate.dayOfMonth != date.dayOfMonth) continue
            out.add(PastDay(yearsAgo = n, epochDay = candidate.toEpochDay(), year = year))
        }
        return out
    }

    /** "1 year ago", "3 years ago" — the label under a thumbnail. */
    fun label(yearsAgo: Int): String = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
}
