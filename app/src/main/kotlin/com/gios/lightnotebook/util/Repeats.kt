package com.gios.lightnotebook.util

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Writing the recurrence rules [Recurrence] already reads.
 *
 * The two halves have to agree or the app loses events: a rule this app writes and cannot expand is
 * a series that shows up once, and a rule it writes that a real calendar rejects is an export that
 * silently arrives as a single event. So the presets here are deliberately inside the subset
 * [Recurrence] documents — `FREQ` with `INTERVAL`, `BYDAY` for weekly, `UNTIL` — and nothing
 * clever.
 *
 * `BYDAY` on the weekly presets is not decoration. Without it, "weekly" means *the weekday DTSTART
 * happens to fall on*, which is the same thing right up until the event is moved to another day and
 * the series keeps repeating on the old one.
 */
object Repeats {

    /** One offered rule, with the words to show for it. */
    data class Preset(val label: String, val rrule: String?)

    /**
     * What can be chosen, for an event that starts on [epochDay].
     *
     * The day matters because two of these name it — "every Tuesday" is a rule about the event's
     * own weekday, and the list has to say which day that is or the row is a promise the user
     * cannot check.
     */
    fun presets(epochDay: Long): List<Preset> {
        val day = NoteDates.of(epochDay).dayOfWeek
        val short = day.getDisplayName(TextStyle.SHORT, Locale.US)
        val code = code(day)
        return listOf(
            Preset("Never", null),
            Preset("Every day", "FREQ=DAILY"),
            Preset("Every $short", "FREQ=WEEKLY;BYDAY=$code"),
            Preset("Every other $short", "FREQ=WEEKLY;INTERVAL=2;BYDAY=$code"),
            Preset("Weekdays", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
            Preset("Every month", "FREQ=MONTHLY"),
            Preset("Every year", "FREQ=YEARLY"),
        )
    }

    /** "Every Tue", or "Never". Read off the stored rule rather than from anything remembered. */
    fun describe(rrule: String?): String {
        val rule = rrule?.takeIf { it.isNotBlank() } ?: return "Never"
        val parts = fields(rule)
        val freq = parts["FREQ"]?.uppercase(Locale.US) ?: return "Custom rule"
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { name(it.trim()) }.orEmpty()
        return when (freq) {
            "DAILY" -> if (interval == 1) "Every day" else "Every $interval days"
            "WEEKLY" -> when {
                byDay.size > 1 -> byDay.joinToString(", ")
                interval == 1 -> "Every " + (byDay.firstOrNull() ?: "week")
                else -> "Every $interval weeks" + byDay.firstOrNull()?.let { " on $it" }.orEmpty()
            }
            "MONTHLY" -> if (interval == 1) "Every month" else "Every $interval months"
            "YEARLY" -> if (interval == 1) "Every year" else "Every $interval years"
            // An imported rule this app can write but not name. Saying so is better than guessing:
            // the row is read by somebody deciding whether to change it.
            else -> "Custom rule"
        }
    }

    /** What the end row says. */
    fun describeEnd(rrule: String?): String {
        if (rrule.isNullOrBlank()) return "Not repeating"
        val fields = fields(rrule)
        fields["COUNT"]?.toIntOrNull()?.let { return "After $it times" }
        untilDay(rrule)?.let { return NoteDates.dayTitle(it) }
        return "Forever"
    }

    /** The `UNTIL` day, if the rule has one. */
    fun untilDay(rrule: String?): Long? {
        val raw = fields(rrule ?: return null)["UNTIL"] ?: return null
        val date = raw.take(8)
        if (date.length != 8) return null
        val year = date.take(4).toIntOrNull() ?: return null
        val month = date.substring(4, 6).toIntOrNull() ?: return null
        val dayOfMonth = date.substring(6, 8).toIntOrNull() ?: return null
        return runCatching { java.time.LocalDate.of(year, month, dayOfMonth).toEpochDay() }
            .getOrNull()
    }

    /**
     * A new rule that keeps whatever end the old one had.
     *
     * Changing "every week" to "every day" should not quietly drop the "until December" somebody
     * set on it — the two rows are separate questions and the sheet for one must not answer the
     * other. `COUNT` is carried the same way.
     */
    fun withEnd(rrule: String?, previous: String?): String? {
        val base = rrule?.takeIf { it.isNotBlank() } ?: return null
        val old = fields(previous ?: "")
        val until = old["UNTIL"]
        val count = old["COUNT"]
        return when {
            until != null -> "$base;UNTIL=$until"
            count != null -> "$base;COUNT=$count"
            else -> base
        }
    }

    /** The same rule, ending on a day — or never, when the day is null. */
    fun endingOn(rrule: String?, epochDay: Long?): String? {
        val base = strip(rrule?.takeIf { it.isNotBlank() } ?: return null)
        val day = epochDay ?: return base
        // Written as a date rather than a UTC timestamp, matching how this app stores times at all:
        // floating, because an event at half past two is at half past two wherever you are.
        val date = NoteDates.of(day)
        val stamp = "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
        return "$base;UNTIL=$stamp"
    }

    /** The rule with any end taken off it. */
    private fun strip(rrule: String): String = rrule
        .split(';')
        .filter { it.isNotBlank() }
        .filterNot { it.uppercase(Locale.US).startsWith("UNTIL=") }
        .filterNot { it.uppercase(Locale.US).startsWith("COUNT=") }
        .joinToString(";")

    private fun fields(rrule: String): Map<String, String> = rrule
        .removePrefix("RRULE:")
        .split(';')
        .mapNotNull { part ->
            val name = part.substringBefore('=', "").trim().uppercase(Locale.US)
            val value = part.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toMap()

    private fun code(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    private fun name(code: String): String? = when (code.uppercase(Locale.US)) {
        "MO" -> "Mon"
        "TU" -> "Tue"
        "WE" -> "Wed"
        "TH" -> "Thu"
        "FR" -> "Fri"
        "SA" -> "Sat"
        "SU" -> "Sun"
        else -> null
    }
}
