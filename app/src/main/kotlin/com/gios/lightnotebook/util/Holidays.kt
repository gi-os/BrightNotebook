package com.gios.lightnotebook.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The US federal holidays, worked out on the phone.
 *
 * Computed rather than fetched. Eleven holidays whose rules have not changed since Juneteenth
 * was added in 2021 do not need a network call, a cache, an API key or an hourly refresh —
 * and a calendar that only knows about Christmas when it has signal is worse than one that
 * knows offline, forever. The whole thing is arithmetic on [LocalDate].
 *
 * **Observed dates are separate entries, on purpose.** When Independence Day falls on a
 * Saturday the fireworks are on the Saturday and the day off is the Friday, and those are two
 * different facts about your week. The holiday always appears on its real date; a second
 * entry appears on the observed weekday only when the two differ, so a Sunday Christmas puts
 * "Christmas Day" on the Sunday and "Christmas Day (observed)" on the Monday. Both are true,
 * and neither is the one you can infer from the other.
 *
 * Android-free, so all of it is tested off-device — which is the only way to check a rule like
 * "the fourth Thursday in November" against a year nobody has lived yet.
 */
object Holidays {

    /**
     * One holiday on one day.
     *
     * [id] is stable and is what an icon is chosen by, so it survives a rename of [name].
     * The day is an epoch day because that is what everything downstream is keyed on — a
     * holiday belongs to a square in the grid, and no timezone should be able to move it.
     */
    data class Holiday(
        val id: String,
        val name: String,
        val epochDay: Long,
        /** True when this is the weekday the day off moved to, not the date itself. */
        val observed: Boolean = false,
    ) {
        /** What a row says: "Christmas Day", or "Christmas Day (observed)". */
        val label: String get() = if (observed) "$name (observed)" else name
    }

    const val NEW_YEAR = "new_year"
    const val MLK = "mlk"
    const val PRESIDENTS = "presidents"
    const val MEMORIAL = "memorial"
    const val JUNETEENTH = "juneteenth"
    const val INDEPENDENCE = "independence"
    const val LABOR = "labor"
    const val COLUMBUS = "columbus"
    const val VETERANS = "veterans"
    const val THANKSGIVING = "thanksgiving"
    const val CHRISTMAS = "christmas"

    /**
     * Juneteenth became a federal holiday in June 2021. Before that it was not one, and a
     * calendar that shows it in 2019 is quietly wrong about history.
     */
    private const val JUNETEENTH_FROM_YEAR = 2021

    /** Every holiday touching the day range, inclusive, observed dates included. */
    fun inRange(fromDay: Long, toDay: Long): List<Holiday> {
        if (toDay < fromDay) return emptyList()
        val firstYear = LocalDate.ofEpochDay(fromDay).year
        val lastYear = LocalDate.ofEpochDay(toDay).year
        // A window can straddle New Year, and an observed date can land in the year either
        // side of its own, so the neighbouring years are always considered.
        return ((firstYear - 1)..(lastYear + 1))
            .flatMap { year -> ofYear(year) }
            .filter { it.epochDay in fromDay..toDay }
            .sortedWith(compareBy({ it.epochDay }, { it.observed }))
    }

    /** Every holiday in one calendar year, each followed by its observed date if it differs. */
    fun ofYear(year: Int): List<Holiday> {
        val dates = buildList {
            add(NEW_YEAR to LocalDate.of(year, 1, 1))
            add(MLK to nth(year, 1, DayOfWeek.MONDAY, 3))
            add(PRESIDENTS to nth(year, 2, DayOfWeek.MONDAY, 3))
            add(MEMORIAL to last(year, 5, DayOfWeek.MONDAY))
            if (year >= JUNETEENTH_FROM_YEAR) add(JUNETEENTH to LocalDate.of(year, 6, 19))
            add(INDEPENDENCE to LocalDate.of(year, 7, 4))
            add(LABOR to nth(year, 9, DayOfWeek.MONDAY, 1))
            add(COLUMBUS to nth(year, 10, DayOfWeek.MONDAY, 2))
            add(VETERANS to LocalDate.of(year, 11, 11))
            add(THANKSGIVING to nth(year, 11, DayOfWeek.THURSDAY, 4))
            add(CHRISTMAS to LocalDate.of(year, 12, 25))
        }
        return dates.flatMap { (id, date) ->
            val holiday = Holiday(id = id, name = nameOf(id), epochDay = date.toEpochDay())
            val moved = observedDate(date)
            if (moved == date) {
                listOf(holiday)
            } else {
                listOf(holiday, holiday.copy(epochDay = moved.toEpochDay(), observed = true))
            }
        }
    }

    /** The single holiday to draw for a day, preferring the real date over an observed one. */
    fun on(epochDay: Long): Holiday? =
        inRange(epochDay, epochDay).minByOrNull { if (it.observed) 1 else 0 }

    fun nameOf(id: String): String = when (id) {
        NEW_YEAR -> "New Year's Day"
        MLK -> "Martin Luther King Jr. Day"
        PRESIDENTS -> "Presidents' Day"
        MEMORIAL -> "Memorial Day"
        JUNETEENTH -> "Juneteenth"
        INDEPENDENCE -> "Independence Day"
        LABOR -> "Labor Day"
        COLUMBUS -> "Columbus Day"
        VETERANS -> "Veterans Day"
        THANKSGIVING -> "Thanksgiving"
        CHRISTMAS -> "Christmas Day"
        else -> id
    }

    /**
     * Where the day off lands: Saturday moves back to Friday, Sunday forward to Monday.
     *
     * This is the federal rule (5 U.S.C. 6103), and it is the one people plan around.
     */
    private fun observedDate(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun nth(year: Int, month: Int, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    private fun last(year: Int, month: Int, day: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(day))
}
