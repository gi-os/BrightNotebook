package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One event read out of an .ics file, already reduced to a day and minutes. */
data class ImportedEvent(
    val uid: String,
    val title: String,
    val epochDay: Long,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    /** The feed's own `RRULE`, kept as written. Null for an event that happens once. */
    val rrule: String? = null,
    /** Days the series does not happen on: the feed's `EXDATE`s, plus any overridden instance. */
    val exDays: Set<Long> = emptySet(),
    /**
     * The event's `LOCATION`, as written. Null when the invite carried none.
     *
     * Not parsed and not validated. What arrives here is a room name, a postal address, a Teams
     * link or the word "gym", and every one of those is the correct answer to "where is this" for
     * somebody. The only thing downstream of it is a maps search, which wants the string.
     */
    val location: String? = null,
    /**
     * The event's own alarm, in minutes before it starts. Null when the invite carried none.
     *
     * Read from the first `VALARM` in the block. Calendars write several — a pop-up and an email,
     * say — and this app has one reminder per entry, so the first is taken and the rest are
     * dropped rather than collapsed into something nobody asked for.
     */
    val reminderMinutes: Int? = null,
)

/**
 * Reads events out of an iCalendar (.ics) file — the format every calendar exports and
 * every invite arrives as.
 *
 * Deliberately partial. It reads `VEVENT` blocks and takes `UID`, `SUMMARY`, `DTSTART`,
 * `DTEND`, `RRULE`, `EXDATE` and `RECURRENCE-ID`, and nothing else.
 *
 * **Recurrence is carried, not expanded.** An `RRULE` is stored on the event as written and
 * turned into days later, by [Recurrence], for whatever window the calendar is drawing. That is
 * the only way a feed with a ten-year daily meeting in it can be imported at all: expanding at
 * parse time would put three and a half thousand rows in the database for one line of text.
 * Rules outside [Recurrence]'s subset are still kept — expansion falls back to the start date
 * for those, so the event appears once, on the day it really starts, exactly as it did before
 * this parser knew what an RRULE was.
 *
 * A `VEVENT` carrying `RECURRENCE-ID` is an override of one instance of a series: it comes out
 * as an event of its own, and the day it replaces is added to its series' exceptions so the two
 * do not both appear.
 *
 * Android-free, so all of it is tested off-device.
 */
object IcsParser {

    private const val MAX_EVENTS = 2000
    private val basicDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val basicDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<ImportedEvent> {
        val events = mutableListOf<ImportedEvent>()
        // Which days each series has had overridden by a RECURRENCE-ID event, by UID. Applied
        // after the whole file is read, because the override may be written before its master.
        val overridden = mutableMapOf<String, MutableSet<Long>>()
        var inEvent = false
        var uid: String? = null
        var summary: String? = null
        var start: Moment? = null
        var end: Moment? = null
        var rrule: String? = null
        var location: String? = null
        var alarm: Int? = null
        var recurrenceId: Moment? = null
        var exDays = mutableSetOf<Long>()

        for (line in unfold(text)) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    inEvent = true
                    uid = null; summary = null; start = null; end = null
                    rrule = null; recurrenceId = null; exDays = mutableSetOf(); location = null
                    alarm = null
                }

                line.equals("END:VEVENT", ignoreCase = true) -> {
                    inEvent = false
                    val begin = start
                    if (begin != null && events.size < MAX_EVENTS) {
                        val realUid = uid?.takeIf { it.isNotBlank() } ?: syntheticUid(summary, begin)
                        val replaces = recurrenceId
                        if (replaces != null) {
                            // An overridden instance: its own event, and a hole in the series.
                            overridden.getOrPut(realUid) { mutableSetOf() }.add(replaces.epochDay)
                        }
                        events.add(
                            ImportedEvent(
                                // A moved instance must not collide with its own series: both
                                // rows carry the same UID in the file, and `sourceUid` is what a
                                // re-import matches on.
                                uid = if (replaces != null) "$realUid#${replaces.epochDay}" else realUid,
                                title = summary?.takeIf { it.isNotBlank() } ?: "Event",
                                epochDay = begin.epochDay,
                                startMinutes = begin.minutes,
                                endMinutes = end
                                    ?.takeIf { it.epochDay == begin.epochDay }
                                    ?.minutes
                                    ?.takeIf { begin.minutes != null && it > begin.minutes },
                                // An override happens once, whatever the master says.
                                rrule = if (replaces == null) rrule else null,
                                location = location?.takeIf { it.isNotBlank() },
                                reminderMinutes = alarm,
                                exDays = if (replaces == null) exDays.toSet() else emptySet(),
                            ),
                        )
                    }
                }

                !inEvent -> Unit

                else -> {
                    val (name, params, value) = split(line) ?: continue
                    when (name.uppercase(Locale.US)) {
                        "UID" -> uid = value.trim()
                        "SUMMARY" -> summary = unescape(value).trim().take(200)
                        "DTSTART" -> start = moment(params, value, zone)
                        "DTEND" -> end = moment(params, value, zone)
                        "RRULE" -> rrule = value.trim().takeIf { it.isNotBlank() }
                        // `unescape` earns its keep here: an address is the one field in a
                        // VEVENT that routinely contains the commas ICS escapes.
                        "LOCATION" -> location = unescape(value).trim().take(200)
                        // EXDATE may appear several times and may list several dates at once.
                        "EXDATE" -> value.split(',').forEach { one ->
                            moment(params, one, zone)?.let { exDays.add(it.epochDay) }
                        }
                        "RECURRENCE-ID" -> recurrenceId = moment(params, value, zone)
                        // Inside a VALARM, and only the first one. A relative TRIGGER is the only
                        // form worth reading: an absolute one (`VALUE=DATE-TIME`) is an alarm for
                        // a moment rather than for this event, and on a repeating event it would
                        // fire once in 1998.
                        "TRIGGER" -> if (alarm == null) alarm = triggerMinutes(params, value)
                    }
                }
            }
        }

        return events
            .map { event ->
                val holes = overridden[event.uid]
                if (holes == null) event else event.copy(exDays = event.exDays + holes)
            }
            .sortedWith(compareBy({ it.epochDay }, { it.startMinutes ?: -1 }))
    }

    /** A point in time reduced to the grid: which square, and how far into it. */
    private data class Moment(val epochDay: Long, val minutes: Int?)

    /**
     * Folded lines are the format's one real trap: a long SUMMARY is broken with CRLF and
     * a leading space, and reading it line-by-line truncates every long title.
     */
    internal fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        text.split("\n").forEach { raw ->
            val line = raw.removeSuffix("\r")
            if (line.startsWith(" ") || line.startsWith("\t")) {
                out.lastOrNull()?.append(line.substring(1)) ?: out.add(StringBuilder(line.trim()))
            } else {
                out.add(StringBuilder(line))
            }
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }

    /** `DTSTART;TZID=Europe/Paris:20260729T090000` → name, params, value. */
    private fun split(line: String): Triple<String, String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val semi = head.indexOf(';')
        return if (semi == -1) {
            Triple(head, "", value)
        } else {
            Triple(head.substring(0, semi), head.substring(semi + 1), value)
        }
    }

    private fun moment(params: String, rawValue: String, zone: ZoneId): Moment? {
        val value = rawValue.trim()
        val upperParams = params.uppercase(Locale.US)

        if (upperParams.contains("VALUE=DATE") && !value.contains('T')) {
            val date = runCatching { LocalDate.parse(value, basicDate) }.getOrNull() ?: return null
            return Moment(date.toEpochDay(), null)
        }

        if (value.endsWith("Z")) {
            // UTC. Convert to the phone's zone, or a 23:00 UTC event lands on the wrong day.
            val local = runCatching {
                LocalDateTime.parse(value.dropLast(1), basicDateTime)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .atZone(zone)
            }.getOrNull() ?: return null
            return Moment(local.toLocalDate().toEpochDay(), local.hour * 60 + local.minute)
        }

        // Zone ids are case-sensitive, so match the parameter name case-insensitively but
        // take its value exactly as written.
        val tzid = params.split(';')
            .firstOrNull { it.uppercase(Locale.US).startsWith("TZID=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sourceZone = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone

        if (value.contains('T')) {
            val local = runCatching {
                LocalDateTime.parse(value, basicDateTime).atZone(sourceZone).withZoneSameInstant(zone)
            }.getOrNull() ?: return null
            return Moment(local.toLocalDate().toEpochDay(), local.hour * 60 + local.minute)
        }

        val date = runCatching { LocalDate.parse(value, basicDate) }.getOrNull() ?: return null
        return Moment(date.toEpochDay(), null)
    }

    /** Text values escape commas, semicolons and newlines. */
    internal fun unescape(value: String): String = value
        .replace("\\n", " ")
        .replace("\\N", " ")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    /**
     * Some exporters omit UID. Deriving one from the title and start keeps re-imports
     * idempotent, which is the whole reason the field is stored.
     */
    private fun syntheticUid(summary: String?, start: Moment): String =
        "lnb-${start.epochDay}-${start.minutes ?: -1}-${(summary ?: "").hashCode()}"

    /**
     * The name a feed gives itself (`X-WR-CALNAME`), if it gives one.
     *
     * A subscribed URL has no filename to borrow a label from, so this is the difference
     * between a row that says "Work" and one that says "cal.basilnet.com".
     */
    fun calendarName(text: String): String? = unfold(text)
        .asSequence()
        .take(40)
        .firstOrNull { it.startsWith("X-WR-CALNAME", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.let { unescape(it) }
        ?.trim()
        ?.take(60)
        ?.takeIf { it.isNotBlank() }

    /** True when the text looks like an iCalendar file at all. */
    fun looksLikeIcs(text: String): Boolean =
        text.lineSequence().take(20).any { it.trim().equals("BEGIN:VCALENDAR", ignoreCase = true) }

    /** Millis for the reminder maths; kept here so the parser owns all its date logic. */
    fun startMillis(event: ImportedEvent, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val minutes = event.startMinutes ?: return null
        return LocalDate.ofEpochDay(event.epochDay)
            .atStartOfDay(zone)
            .plusMinutes(minutes.toLong())
            .toInstant()
            .toEpochMilli()
    }
    /**
     * A `TRIGGER` as minutes before the start, or null for one this app cannot honour.
     *
     * Only negative, relative durations: `-PT15M`, `-PT1H`, `-P1D`, and the combinations calendars
     * actually write. Refused, on purpose:
     *
     *  - **`VALUE=DATE-TIME`** — an absolute alarm. It is a time, not an offset, so on a repeating
     *    event it belongs to exactly one occurrence and this app would attach it to all of them.
     *  - **`RELATED=END`** — counted back from the end of the event, which is not the field this
     *    app has. Reading it as an offset from the start would move the alarm by the event's
     *    length, silently.
     *  - **A positive duration** — an alarm *after* the event starts. Legal, occasionally
     *    deliberate, and not a reminder in the sense this app's one field means.
     */
    private fun triggerMinutes(params: String, value: String): Int? {
        val upper = params.uppercase(Locale.US)
        if (upper.contains("VALUE=DATE-TIME") || upper.contains("RELATED=END")) return null
        val raw = value.trim().uppercase(Locale.US)
        if (!raw.startsWith("-P")) return null
        val body = raw.removePrefix("-P")
        val (dayPart, timePart) = body.substringBefore('T') to body.substringAfter('T', "")
        var minutes = 0
        Regex("(\\d+)([WD])").findAll(dayPart).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            minutes += if (m.groupValues[2] == "W") n * 7 * 24 * 60 else n * 24 * 60
        }
        Regex("(\\d+)([HMS])").findAll(timePart).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            minutes += when (m.groupValues[2]) {
                "H" -> n * 60
                "M" -> n
                else -> 0 // Seconds are not a reminder anybody set on purpose.
            }
        }
        return minutes.takeIf { it >= 0 }
    }

}
