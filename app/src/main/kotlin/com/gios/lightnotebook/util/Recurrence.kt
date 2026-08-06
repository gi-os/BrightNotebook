package com.gios.lightnotebook.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * How often a thing repeats. Persisted **by name** inside the `RRULE` string, so R8 full mode
 * needs a `-keepclassmembers enum` rule for it — see `app/proguard-rules.pro`.
 */
enum class RepeatFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** `1MO` (first Monday), `-1FR` (last Friday), or plain `MO` when [ordinal] is null. */
data class DayPosition(val ordinal: Int?, val day: DayOfWeek)

/** A parsed `RRULE`, reduced to the parts this app can actually honour. */
data class RecurrenceRule(
    val freq: RepeatFreq,
    val interval: Int = 1,
    val byDay: List<DayPosition> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val count: Int? = null,
    /** Inclusive last day the series may land on, as an epoch day. */
    val untilDay: Long? = null,
)

/**
 * One RFC 5545 `RRULE` implementation, used by both halves of recurrence: the repeat picker
 * on an entry, and expansion of an imported `.ics`. Two half-implementations of recurrence in
 * one app would be worse than none, so there is exactly this one.
 *
 * **The subset.** `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL`, `BYDAY` (plain `MO,TU` for
 * weekly, and the `1MO` / `-1FR` forms for monthly), `BYMONTHDAY` for monthly including
 * negatives, `COUNT`, `UNTIL`, and neither of those two — meaning forever. `EXDATE` is carried
 * alongside as a set of epoch days, which is what "delete just this one" writes.
 *
 * **What it refuses.** `BYSETPOS`, `BYWEEKNO`, `BYYEARDAY`, `BYHOUR`/`BYMINUTE`/`BYSECOND`, a
 * `WKST` other than Monday, the sub-daily frequencies, and any `BY*` part applied to a
 * frequency it does not belong to here (`BYDAY` on a daily rule, `BYMONTHDAY` on a weekly one).
 * A refused rule is **not** guessed at and **not** dropped: [expand] falls back to the event's
 * own start date, so a meeting with an exotic rule appears once, on the day it really starts,
 * rather than vanishing or being scattered across the calendar on the wrong days. That is the
 * same trade the parser made before recurrence existed at all, applied to a much smaller set.
 *
 * `BYMONTH` and `BYMONTHDAY` on a *yearly* rule are accepted only when they name the start
 * date's own month and day, which is how every exporter writes "every year on this date". Any
 * other combination is refused, because honouring it properly means a full BY-part cascade.
 *
 * **Expansion is always bounded.** [expand] takes the window the caller is drawing and never
 * produces a list for an unbounded rule; on top of that there is a hard cap on results and a
 * hard cap on candidates considered, so a malformed `INTERVAL=0` — which is invalid per the
 * RFC, and is refused here — cannot spin.
 *
 * Android-free on purpose: all of this is tested on the JVM.
 */
object Recurrence {

    /** Most occurrences one rule may contribute to one window. Six weeks of daily is 42. */
    const val MAX_OCCURRENCES = 400

    /**
     * Most candidate dates one rule may be walked through before giving up. Only reached by a
     * rule whose start is a very long way behind the window being drawn; the daily case, which
     * is the one that would hit it, skips ahead instead of stepping.
     */
    private const val MAX_STEPS = 20_000

    /** Years are walked, not computed, so a yearly rule needs a stop of its own. */
    private const val MAX_YEAR = 4000

    sealed class Parsed {
        data class Rule(val rule: RecurrenceRule) : Parsed()
        /** Well-formed enough to read, outside the subset above. Carries why, for the log. */
        data class Unsupported(val reason: String) : Parsed()
    }

    /* ---------------- parsing ---------------- */

    fun parse(rrule: String?): Parsed? {
        val text = rrule?.trim()?.removePrefix("RRULE:")?.removePrefix("rrule:")?.trim()
        if (text.isNullOrBlank()) return null

        var freq: RepeatFreq? = null
        var interval = 1
        var count: Int? = null
        var until: Long? = null
        val byDay = mutableListOf<DayPosition>()
        val byMonthDay = mutableListOf<Int>()
        val byMonth = mutableListOf<Int>()

        for (part in text.split(';')) {
            if (part.isBlank()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) return Parsed.Unsupported("malformed part '$part'")
            val key = part.substring(0, eq).trim().uppercase(Locale.US)
            val value = part.substring(eq + 1).trim()
            when (key) {
                "FREQ" -> freq = when (value.uppercase(Locale.US)) {
                    "DAILY" -> RepeatFreq.DAILY
                    "WEEKLY" -> RepeatFreq.WEEKLY
                    "MONTHLY" -> RepeatFreq.MONTHLY
                    "YEARLY" -> RepeatFreq.YEARLY
                    else -> return Parsed.Unsupported("FREQ=$value")
                }

                "INTERVAL" -> {
                    val n = value.toIntOrNull() ?: return Parsed.Unsupported("INTERVAL=$value")
                    // Zero and negatives are invalid per the RFC, and zero is the one that
                    // would loop forever. Refused rather than repaired.
                    if (n < 1) return Parsed.Unsupported("INTERVAL=$value")
                    interval = n
                }

                "COUNT" -> {
                    val n = value.toIntOrNull() ?: return Parsed.Unsupported("COUNT=$value")
                    if (n < 1) return Parsed.Unsupported("COUNT=$value")
                    count = n
                }

                "UNTIL" -> until = untilDay(value) ?: return Parsed.Unsupported("UNTIL=$value")

                "BYDAY" -> value.split(',').forEach { raw ->
                    byDay.add(dayPosition(raw.trim()) ?: return Parsed.Unsupported("BYDAY=$value"))
                }

                "BYMONTHDAY" -> value.split(',').forEach { raw ->
                    val n = raw.trim().toIntOrNull()
                        ?: return Parsed.Unsupported("BYMONTHDAY=$value")
                    if (n == 0 || n > 31 || n < -31) return Parsed.Unsupported("BYMONTHDAY=$value")
                    byMonthDay.add(n)
                }

                "BYMONTH" -> value.split(',').forEach { raw ->
                    val n = raw.trim().toIntOrNull() ?: return Parsed.Unsupported("BYMONTH=$value")
                    if (n !in 1..12) return Parsed.Unsupported("BYMONTH=$value")
                    byMonth.add(n)
                }

                // The default week start is Monday and nothing here reads any other one; a
                // feed that says otherwise changes which week a BYDAY lands in, so it is
                // refused rather than quietly ignored.
                "WKST" -> if (!value.equals("MO", ignoreCase = true)) {
                    return Parsed.Unsupported("WKST=$value")
                }

                else -> return Parsed.Unsupported(key)
            }
        }

        val f = freq ?: return Parsed.Unsupported("no FREQ")
        if (count != null && until != null) {
            // The RFC forbids both. Taking one silently would be a guess about which.
            return Parsed.Unsupported("COUNT and UNTIL together")
        }
        return Parsed.Rule(
            RecurrenceRule(f, interval, byDay.toList(), byMonthDay.toList(), byMonth.toList(), count, until),
        )
    }

    /**
     * Why a rule cannot be honoured against a particular start date, or null when it can.
     *
     * Start-dependent because the yearly case is: `BYMONTH`/`BYMONTHDAY` are fine as long as
     * they only restate the start date.
     */
    fun unsupportedReason(rrule: String?, startDay: Long): String? = when (val p = parse(rrule)) {
        null -> null
        is Parsed.Unsupported -> p.reason
        is Parsed.Rule -> conflict(p.rule, startDay)
    }

    private fun conflict(rule: RecurrenceRule, startDay: Long): String? {
        val start = LocalDate.ofEpochDay(startDay)
        return when (rule.freq) {
            RepeatFreq.DAILY -> when {
                rule.byDay.isNotEmpty() -> "BYDAY on a daily rule"
                rule.byMonthDay.isNotEmpty() -> "BYMONTHDAY on a daily rule"
                rule.byMonth.isNotEmpty() -> "BYMONTH on a daily rule"
                else -> null
            }

            RepeatFreq.WEEKLY -> when {
                rule.byDay.any { it.ordinal != null } -> "an ordinal BYDAY on a weekly rule"
                rule.byMonthDay.isNotEmpty() -> "BYMONTHDAY on a weekly rule"
                rule.byMonth.isNotEmpty() -> "BYMONTH on a weekly rule"
                else -> null
            }

            RepeatFreq.MONTHLY -> when {
                rule.byDay.isNotEmpty() && rule.byMonthDay.isNotEmpty() ->
                    "BYDAY and BYMONTHDAY together"
                rule.byMonth.isNotEmpty() -> "BYMONTH on a monthly rule"
                else -> null
            }

            RepeatFreq.YEARLY -> when {
                rule.byDay.isNotEmpty() -> "BYDAY on a yearly rule"
                rule.byMonth.isNotEmpty() && rule.byMonth != listOf(start.monthValue) ->
                    "BYMONTH that is not the start month"
                rule.byMonthDay.isNotEmpty() && rule.byMonthDay != listOf(start.dayOfMonth) ->
                    "BYMONTHDAY that is not the start day"
                else -> null
            }
        }
    }

    /** `MO`, `1MO`, `-1FR`. */
    private fun dayPosition(raw: String): DayPosition? {
        if (raw.length < 2) return null
        val code = raw.takeLast(2).uppercase(Locale.US)
        val day = WEEKDAY_CODES[code] ?: return null
        val head = raw.dropLast(2)
        if (head.isEmpty()) return DayPosition(null, day)
        val n = head.toIntOrNull() ?: return null
        if (n == 0 || n > 5 || n < -5) return null
        return DayPosition(n, day)
    }

    /**
     * `20261231T235959Z` or `20261231` → an epoch day.
     *
     * Only the date part is read. The calendar is keyed by day throughout, so the clock time on
     * an UNTIL cannot change which square anything lands in; the one thing it could change is
     * whether the very last occurrence counts, and taking the UTC date means the series may run
     * a matter of hours long at its far end rather than a day short.
     */
    private fun untilDay(value: String): Long? {
        val date = value.trim().take(8)
        if (date.length != 8 || date.any { !it.isDigit() }) return null
        return runCatching {
            LocalDate.of(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt(),
                date.substring(6, 8).toInt(),
            ).toEpochDay()
        }.getOrNull()
    }

    /* ---------------- writing ---------------- */

    /** The rule as an `RRULE` value — no `RRULE:` prefix, which is the line and not the rule. */
    fun format(rule: RecurrenceRule): String = buildList {
        add("FREQ=${rule.freq.name}")
        if (rule.interval > 1) add("INTERVAL=${rule.interval}")
        if (rule.byDay.isNotEmpty()) {
            add("BYDAY=" + rule.byDay.joinToString(",") { pos ->
                (pos.ordinal?.toString() ?: "") + CODE_FOR_WEEKDAY.getValue(pos.day)
            })
        }
        if (rule.byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + rule.byMonthDay.joinToString(","))
        rule.count?.let { add("COUNT=$it") }
        rule.untilDay?.let {
            val d = LocalDate.ofEpochDay(it)
            add("UNTIL=%04d%02d%02dT235959Z".format(Locale.US, d.year, d.monthValue, d.dayOfMonth))
        }
    }.joinToString(";")

    /* ---------------- EXDATE storage ---------------- */

    /**
     * Excluded days are kept as a plain comma-separated list of epoch days in one column.
     *
     * A table of exceptions would be the tidier schema and buys nothing: the list is read only
     * ever with the row it belongs to, is deleted with it, and is a handful of numbers even for
     * somebody who has skipped a standup every week for a year.
     */
    fun parseExDays(stored: String?): Set<Long> {
        if (stored.isNullOrBlank()) return emptySet()
        return stored.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun formatExDays(days: Set<Long>): String? =
        days.sorted().joinToString(",").takeIf { it.isNotBlank() }

    /* ---------------- expansion ---------------- */

    /**
     * The days this rule lands on inside `[from, to]`, inclusive of both ends.
     *
     * Bounded twice over: by the window, which is whatever the caller is actually drawing, and
     * by [cap]. Nothing here ever materialises an unbounded series, which is why a decade-long
     * daily import is one row in the database and not three and a half thousand.
     *
     * An absent rule is one occurrence on [startDay]; so is a rule outside the supported subset.
     */
    fun expand(
        rrule: String?,
        startDay: Long,
        from: Long,
        to: Long,
        exDays: Set<Long> = emptySet(),
        cap: Int = MAX_OCCURRENCES,
    ): List<Long> {
        if (from > to || cap <= 0) return emptyList()
        val rule = ruleFor(rrule, startDay)
            ?: return listOf(startDay).filter { it in from..to && it !in exDays }

        val out = ArrayList<Long>()
        var generated = 0
        var steps = 0
        // A daily rule that started years ago is skipped forward rather than stepped through,
        // but only when COUNT is absent: with a COUNT every occurrence before the window still
        // has to be counted, because it is what ends the series.
        val skipTo = if (rule.count == null) from else null
        for (day in candidates(rule, startDay, skipTo)) {
            if (++steps > MAX_STEPS) break
            if (rule.untilDay != null && day > rule.untilDay) break
            if (rule.count != null && generated >= rule.count) break
            generated++
            if (day > to) break
            if (day < from) continue
            // Counted before being excluded: COUNT counts what the rule generates, and an
            // EXDATE removes an instance rather than shifting the end of the series.
            if (day in exDays) continue
            out.add(day)
            if (out.size >= cap) break
        }
        return out
    }

    /** The first occurrence on or after [day], or null when the series is over. */
    fun nextOnOrAfter(
        rrule: String?,
        startDay: Long,
        day: Long,
        exDays: Set<Long> = emptySet(),
        horizonDays: Long = 400L,
    ): Long? = expand(rrule, startDay, maxOf(day, startDay), day + horizonDays, exDays, cap = 1)
        .firstOrNull()

    /** True when this text is a rule this app will actually expand. */
    fun isSupported(rrule: String?, startDay: Long): Boolean =
        parse(rrule) is Parsed.Rule && conflict((parse(rrule) as Parsed.Rule).rule, startDay) == null

    private fun ruleFor(rrule: String?, startDay: Long): RecurrenceRule? {
        val parsed = parse(rrule) as? Parsed.Rule ?: return null
        return if (conflict(parsed.rule, startDay) == null) parsed.rule else null
    }

    /**
     * Every date the rule could land on, ascending, ignoring COUNT and UNTIL — the caller stops.
     *
     * A month the rule cannot land in produces nothing and the walk carries on: the 31st of a
     * 30-day month is **skipped**, which is what RFC 5545 says, and somebody who means "the last
     * day of the month" wants `BYMONTHDAY=-1`. The same rule takes 29 February out of three
     * years in four.
     */
    private fun candidates(rule: RecurrenceRule, startDay: Long, skipTo: Long?): Sequence<Long> {
        val start = LocalDate.ofEpochDay(startDay)
        return when (rule.freq) {
            RepeatFreq.DAILY -> {
                val first = if (skipTo != null && skipTo > startDay) {
                    val gap = skipTo - startDay
                    val steps = (gap + rule.interval - 1) / rule.interval
                    startDay + steps * rule.interval
                } else {
                    startDay
                }
                generateSequence(first) { it + rule.interval }
            }

            RepeatFreq.WEEKLY -> {
                val days = rule.byDay.map { it.day }.distinct().sortedBy { it.value }
                    .ifEmpty { listOf(start.dayOfWeek) }
                // Weeks start on Monday, which is both the RFC default and the only WKST
                // accepted above, so an interval walks whole Monday-to-Sunday blocks.
                val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
                sequence {
                    var week = firstMonday
                    while (true) {
                        for (d in days) {
                            val day = week.plusDays((d.value - 1).toLong()).toEpochDay()
                            if (day >= startDay) yield(day)
                        }
                        week = week.plusWeeks(rule.interval.toLong())
                        if (week.year > MAX_YEAR) break
                    }
                }
            }

            RepeatFreq.MONTHLY -> sequence {
                var month = YearMonth.from(start)
                while (true) {
                    for (day in monthDays(rule, month, start)) {
                        if (day >= startDay) yield(day)
                    }
                    month = month.plusMonths(rule.interval.toLong())
                    if (month.year > MAX_YEAR) break
                }
            }

            RepeatFreq.YEARLY -> sequence {
                var year = start.year
                while (year <= MAX_YEAR) {
                    val month = YearMonth.of(year, start.monthValue)
                    if (start.dayOfMonth <= month.lengthOfMonth()) {
                        yield(month.atDay(start.dayOfMonth).toEpochDay())
                    }
                    year += rule.interval
                }
            }
        }
    }

    /** The days one monthly rule lands on inside one month, ascending and de-duplicated. */
    private fun monthDays(rule: RecurrenceRule, month: YearMonth, start: LocalDate): List<Long> {
        val length = month.lengthOfMonth()
        val days = sortedSetOf<Long>()
        when {
            rule.byMonthDay.isNotEmpty() -> rule.byMonthDay.forEach { n ->
                // Negative counts back from the end: -1 is the last day, whatever the month is.
                val dom = if (n > 0) n else length + n + 1
                if (dom in 1..length) days.add(month.atDay(dom).toEpochDay())
            }

            rule.byDay.isNotEmpty() -> rule.byDay.forEach { pos ->
                if (pos.ordinal == null) {
                    // Plain BYDAY in a monthly rule means every one of that weekday.
                    var d = month.atDay(1)
                    while (d.monthValue == month.monthValue) {
                        if (d.dayOfWeek == pos.day) days.add(d.toEpochDay())
                        d = d.plusDays(1)
                    }
                } else {
                    nthWeekday(month, pos.ordinal, pos.day)?.let { days.add(it) }
                }
            }

            else -> if (start.dayOfMonth <= length) days.add(month.atDay(start.dayOfMonth).toEpochDay())
        }
        return days.toList()
    }

    /** The nth (or -nth) given weekday of a month, or null when the month has no such week. */
    private fun nthWeekday(month: YearMonth, ordinal: Int, day: DayOfWeek): Long? {
        val length = month.lengthOfMonth()
        val matches = (1..length)
            .map { month.atDay(it) }
            .filter { it.dayOfWeek == day }
        val index = if (ordinal > 0) ordinal - 1 else matches.size + ordinal
        return matches.getOrNull(index)?.toEpochDay()
    }

    /* ---------------- saying it in words ---------------- */

    /**
     * What the row under "Repeats" says. Null when nothing repeats.
     *
     * A rule outside the subset says so plainly rather than being described wrongly — the
     * calendar is showing one occurrence in that case, and the row has to admit it.
     */
    fun describe(rrule: String?, startDay: Long? = null): String? {
        val parsed = parse(rrule) ?: return null
        val rule = (parsed as? Parsed.Rule)?.rule ?: return UNSUPPORTED_LABEL
        if (startDay != null && conflict(rule, startDay) != null) return UNSUPPORTED_LABEL

        val every = when (rule.freq) {
            RepeatFreq.DAILY -> if (rule.interval == 1) "Every day" else "Every ${rule.interval} days"
            RepeatFreq.WEEKLY -> {
                val head = if (rule.interval == 1) "Every week" else "Every ${rule.interval} weeks"
                val days = rule.byDay.sortedBy { it.day.value }
                    .joinToString(", ") { SHORT_NAME.getValue(it.day) }
                if (days.isBlank()) head else "$head on $days"
            }

            RepeatFreq.MONTHLY -> {
                val head = if (rule.interval == 1) "Every month" else "Every ${rule.interval} months"
                when {
                    rule.byMonthDay.size == 1 && rule.byMonthDay.first() == -1 ->
                        "$head on the last day"
                    rule.byMonthDay.isNotEmpty() ->
                        "$head on the ${rule.byMonthDay.joinToString(", ") { ordinalDay(it) }}"
                    rule.byDay.size == 1 -> {
                        val pos = rule.byDay.first()
                        val which = pos.ordinal?.let { ORDINAL_WORD[it] ?: "${ordinalDay(it)}" } ?: "every"
                        "$head on the $which ${SHORT_NAME.getValue(pos.day)}"
                    }
                    else -> head
                }
            }

            RepeatFreq.YEARLY -> if (rule.interval == 1) "Every year" else "Every ${rule.interval} years"
        }

        val ends = when {
            rule.count != null -> "${rule.count} times"
            rule.untilDay != null -> "until " + NoteDates.isoDate(rule.untilDay)
            else -> null
        }
        return listOfNotNull(every, ends).joinToString(" · ")
    }

    const val UNSUPPORTED_LABEL = "Repeats in a way this app cannot read"

    private fun ordinalDay(n: Int): String = when {
        n < 0 -> if (n == -1) "last day" else "${-n}th from last"
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

    private val ORDINAL_WORD = mapOf(
        1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth", -1 to "last",
    )

    private val WEEKDAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY,
        "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )

    private val CODE_FOR_WEEKDAY = WEEKDAY_CODES.entries.associate { (code, day) -> day to code }

    private val SHORT_NAME = mapOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun",
    )

    /** The weekday letters the picker shows, Monday first, in the same order [SHORT_NAME] is. */
    val WEEKDAYS: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    fun shortName(day: DayOfWeek): String = SHORT_NAME.getValue(day)

    fun letter(day: DayOfWeek): String = SHORT_NAME.getValue(day).take(1)
}
