package com.gios.lightnotebook.util

import kotlin.math.abs

/**
 * A line on the agenda: a calendar entry, a film from LightPass, or one of each that turned
 * out to be the same plan.
 *
 * [id] must be the row's own identity, not something derived from what it says. An earlier
 * version keyed rows on day + time + title, which crashed the agenda the moment two things
 * matched — the same event imported into two calendars, or "gym" typed on two days at no
 * particular time. A `LazyColumn` throws on a repeated key rather than tolerating it.
 */
data class AgendaRow(
    val id: String,
    val epochDay: Long,
    val minutes: Int?,
    val title: String,
    val label: String? = null,
    val reminderMinutes: Int? = null,
    /** Set when there is a ticket behind this row: tapping it opens the stub. */
    val passId: String? = null,
    /** Set when there is a calendar entry behind this row: its own sheet still works. */
    val entryId: String? = null,
    /** Which day of a span this is, and how long the span is. Both 1 for an ordinary entry. */
    val dayOfSpan: Int = 1,
    val spanDays: Int = 1,
    /**
     * The day the occurrence behind this row *starts*, when the entry repeats.
     *
     * A series is one database row and many rows on screen, so "which one did you tap" cannot be
     * answered by the entry alone — the sheet needs it to know which Tuesday you meant when you
     * chose "just this one". Null for anything that happens once, which is nearly everything.
     */
    val occurrenceDay: Long? = null,
    /**
     * Set when this row is a US federal holiday, to [Holidays.Holiday.id].
     *
     * A holiday rides in as an ordinary all-day row rather than as a type of its own, which is
     * what keeps it out of the database, out of `DayTimeline.Item` and out of every exhaustive
     * `when` over it. The id is here only so the grid can pick a glyph.
     */
    val holidayId: String? = null,
    /**
     * Where it is, as the calendar wrote it. Null for nearly everything.
     *
     * On the row it is the first thing in the subtitle, ahead of the calendar's name: "where" is
     * what you look at an entry for on the way out of the door, and which calendar it came from is
     * a detail you already know.
     */
    val location: String? = null,
) {
    val isSpan: Boolean get() = spanDays > 1

    /**
     * What a span says about itself, which depends on where you are looking at it from.
     *
     * On the day it begins — and in the agenda, which lists a trip once — the useful fact is how
     * long it runs. On any later day the useful fact is how far through it you are. "Day 1 of 5"
     * would be true on the first day and is the less useful of the two things it could say.
     */
    val spanLabel: String? get() = when {
        !isSpan -> null
        dayOfSpan == 1 -> "$spanDays days"
        else -> "Day $dayOfSpan of $spanDays"
    }

    /** "Regal Union Square · 10 min before", whichever parts exist. */
    val subtitle: String?
        get() {
            val remind = reminderMinutes?.let {
                if (it <= 0) "at the time" else "$it min before"
            }
            return listOfNotNull(spanLabel, location, label, remind).joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }
}

/**
 * Ordering, de-duplication and the matching of tickets to calendar entries. Android-free so
 * all of it is tested off-device — which is where the crash above should have been caught.
 */
object Agenda {

    /**
     * The holidays in a day range, as rows.
     *
     * Computed on demand rather than stored: [Holidays] is arithmetic, so there is nothing to
     * cache and nothing to go stale. Ids are namespaced like every other non-database row, and
     * an observed date is a row of its own because it is a different day off.
     */
    fun holidayRows(fromDay: Long, toDay: Long): List<AgendaRow> =
        Holidays.inRange(fromDay, toDay).map { holiday ->
            AgendaRow(
                id = "holiday:${holiday.id}:${holiday.epochDay}",
                epochDay = holiday.epochDay,
                // No time: a holiday is the whole day, and `merge` puts all-day rows first.
                minutes = null,
                title = holiday.label,
                holidayId = holiday.id,
            )
        }

    /** Two showings of the same film count as one plan if they start about now. */
    private const val SAME_PLAN_MINUTES = 45

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "at", "in", "on", "to",
        "movie", "movies", "film", "ticket", "tickets", "showing", "screening",
    )

    /**
     * Everything in the order it happens: by day, and within a day all-day items first,
     * then by clock time. Duplicate ids are dropped rather than allowed to reach the list.
     */
    fun merge(vararg sources: List<AgendaRow>): List<AgendaRow> =
        sources.asSequence()
            .flatten()
            .sortedWith(compareBy({ it.epochDay }, { it.minutes ?: -1 }))
            .distinctBy { it.id }
            .toList()

    /**
     * Folds tickets and calendar entries that describe the same plan into single rows.
     *
     * A film in the calendar and a ticket for it are one thing you are doing, and listing
     * both is how the agenda ended up saying "Dune" twice. The ticket wins the tap, because
     * that is where the barcode is; the entry's reminder and label survive, because that is
     * what the calendar was for. Anything unmatched passes through untouched.
     *
     * Each ticket claims at most one entry and each entry can only be claimed once, so a
     * double feature stays two rows.
     */
    fun collapse(entries: List<AgendaRow>, films: List<AgendaRow>): List<AgendaRow> {
        val claimed = mutableSetOf<String>()
        val folded = films.map { film ->
            val match = entries.firstOrNull { entry ->
                entry.id !in claimed && samePlan(entry, film)
            } ?: return@map film
            claimed.add(match.id)
            film.copy(
                // The entry's own time is trusted over the ticket's when the ticket has none.
                minutes = film.minutes ?: match.minutes,
                label = film.label ?: match.label,
                reminderMinutes = match.reminderMinutes,
                entryId = match.entryId ?: match.id,
            )
        }
        val untouched = entries.filterNot { it.id in claimed }
        return merge(untouched, folded)
    }

    /** Same day, near enough the same time, and near enough the same words. */
    internal fun samePlan(entry: AgendaRow, film: AgendaRow): Boolean {
        if (entry.epochDay != film.epochDay) return false
        if (!timesAgree(entry.minutes, film.minutes)) return false
        return titlesAgree(entry.title, film.title)
    }

    /**
     * An all-day entry and a timed ticket are still the same plan — "Dune, Saturday" is how
     * people write down a film they have a 19:30 ticket for.
     */
    internal fun timesAgree(a: Int?, b: Int?): Boolean {
        if (a == null || b == null) return true
        return abs(a - b) <= SAME_PLAN_MINUTES
    }

    /**
     * Loose on purpose. A ticket says "DUNE: PART TWO" and the calendar says "Dune Part 2
     * w/ Alex"; being strict here would leave the duplicate on screen, which is the thing
     * being fixed.
     */
    internal fun titlesAgree(a: String, b: String): Boolean {
        val left = keywords(a)
        val right = keywords(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val shared = left.intersect(right).size
        if (shared == 0) return false
        // One title containing the other counts: "dune" inside "dune part two".
        if (shared == minOf(left.size, right.size)) return true
        return shared.toDouble() / maxOf(left.size, right.size) >= 0.6
    }

    /** Words that carry meaning: lowercase, punctuation gone, filler gone. */
    internal fun keywords(title: String): Set<String> = title
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() && it !in STOP_WORDS }
        .toSet()

    /** The heading above a day's rows. */
    fun heading(epochDay: Long, today: Long): String = when (epochDay) {
        today -> "TODAY · ${NoteDates.dayTitle(epochDay)}"
        today + 1 -> "TOMORROW · ${NoteDates.dayTitle(epochDay)}"
        else -> NoteDates.dayTitle(epochDay)
    }
}
