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
)

/**
 * Reads events out of an iCalendar (.ics) file — the format every calendar exports and
 * every invite arrives as.
 *
 * Deliberately partial. It reads `VEVENT` blocks and takes `UID`, `SUMMARY`, `DTSTART`
 * and `DTEND`, and nothing else. Recurrence in particular is skipped rather than half
 * done: expanding `RRULE` correctly needs exception dates, count/until arithmetic and a
 * timezone database, and a weekly meeting silently appearing once would be worse than not
 * appearing at all.
 *
 * Android-free, so all of it is tested off-device.
 */
object IcsParser {

    private const val MAX_EVENTS = 2000
    private val basicDate = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val basicDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<ImportedEvent> {
        val events = mutableListOf<ImportedEvent>()
        var inEvent = false
        var uid: String? = null
        var summary: String? = null
        var start: Moment? = null
        var end: Moment? = null

        for (line in unfold(text)) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    inEvent = true
                    uid = null; summary = null; start = null; end = null
                }

                line.equals("END:VEVENT", ignoreCase = true) -> {
                    inEvent = false
                    val begin = start
                    // A recurring event is imported as its first occurrence only, which is
                    // at least true, and never as a series this parser cannot expand.
                    if (begin != null && events.size < MAX_EVENTS) {
                        events.add(
                            ImportedEvent(
                                uid = uid?.takeIf { it.isNotBlank() }
                                    ?: syntheticUid(summary, begin),
                                title = summary?.takeIf { it.isNotBlank() } ?: "Event",
                                epochDay = begin.epochDay,
                                startMinutes = begin.minutes,
                                endMinutes = end
                                    ?.takeIf { it.epochDay == begin.epochDay }
                                    ?.minutes
                                    ?.takeIf { begin.minutes != null && it > begin.minutes },
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
                    }
                }
            }
        }
        return events.sortedWith(compareBy({ it.epochDay }, { it.startMinutes ?: -1 }))
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
}
