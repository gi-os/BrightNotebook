package com.gios.lightnotebook.util

import com.gios.lightnotebook.data.DayEntryEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One entry, written out as an iCalendar file.
 *
 * This is how an event made on this phone reaches Google Calendar, Outlook, or anybody else: not
 * by holding their credentials and speaking their APIs, but by producing the file both of them
 * already know how to swallow. Send it to yourself and every calendar on earth offers to add it.
 *
 * ### Why export rather than sync
 *
 * A two-way sync needs an account, a token, a refresh, a conflict rule and somewhere to keep the
 * secret — five moving parts, on a phone whose whole argument is that it has fewer. An .ics is one
 * file, needs no permission, works with a provider nobody here has heard of, and cannot silently
 * corrupt a calendar it has write access to. What it costs is that the copy stops being live: the
 * event this app sent is the receiving calendar's event now, and later edits here do not follow it.
 * That is a real cost and it is the honest one to pay at this size.
 *
 * ### What is written
 *
 * The subset [IcsParser] reads back, which is deliberate — a file this app cannot re-import is a
 * file it will lose data through. `UID`, `DTSTAMP`, `SUMMARY`, `DTSTART`/`DTEND` (or a `VALUE=DATE`
 * pair for an all-day event), `LOCATION`, `RRULE`, `EXDATE`, and a `VALARM` for the reminder.
 *
 * Times are written **floating** — no `TZID`, no `Z` — because that is what this app means. An
 * entry says half past two, and it says half past two after a flight; attaching a zone to it would
 * be inventing a fact the user never gave. The one exception is `DTSTAMP`, which is a fact about
 * the file rather than the event and is written in UTC, as the spec requires.
 */
object IcsWriter {

    /** A whole calendar file for one entry, ready to be written to disk and shared. */
    fun calendar(entry: DayEntryEntity, zone: ZoneId = ZoneId.systemDefault()): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//gi-os//BrightNotebook//EN"
        // Requesting nothing: an exported event is a copy, not an invitation somebody has to answer.
        lines += "METHOD:PUBLISH"
        lines += "CALSCALE:GREGORIAN"
        lines += vevent(entry, zone)
        lines += "END:VCALENDAR"
        return lines.joinToString(LINE_BREAK) + LINE_BREAK
    }

    private fun vevent(entry: DayEntryEntity, zone: ZoneId): String {
        val out = ArrayList<String>()
        out += "BEGIN:VEVENT"
        // The entry's own id, kept: re-importing this file recognises the event as the one it
        // already has rather than making a second copy of it.
        out += "UID:${entry.id}@brightnotebook"
        out += "DTSTAMP:" + ZonedDateTime.now(ZoneId.of("UTC")).format(STAMP_UTC)
        out += fold("SUMMARY:" + escape(entry.text))
        entry.location?.takeIf { it.isNotBlank() }?.let { out += fold("LOCATION:" + escape(it)) }

        val start = entry.startMinutes
        if (start == null) {
            // All-day. DTEND is exclusive in iCalendar — the day *after* the last one — which is
            // the single most commonly got-wrong line in the format: writing the last day itself
            // makes a one-day event vanish and a three-day trip two days long.
            val firstDay = NoteDates.of(entry.epochDay)
            val afterLast = NoteDates.of(entry.lastDay + 1)
            out += "DTSTART;VALUE=DATE:" + firstDay.format(DATE)
            out += "DTEND;VALUE=DATE:" + afterLast.format(DATE)
        } else {
            val startAt = NoteDates.of(entry.epochDay).atStartOfDay(zone).plusMinutes(start.toLong())
            val endMinutes = entry.endMinutes?.takeIf { it > start }
            val endAt = when {
                endMinutes != null -> startAt.plusMinutes((endMinutes - start).toLong())
                // An entry with a time and no end is a moment, and a zero-length VEVENT is legal
                // but reads as a bug in every calendar that shows it. Half an hour is the
                // convention every calendar app uses for exactly this.
                else -> startAt.plusMinutes(DEFAULT_LENGTH_MINUTES)
            }
            out += "DTSTART:" + startAt.format(LOCAL)
            out += "DTEND:" + endAt.format(LOCAL)
        }

        entry.rrule?.takeIf { it.isNotBlank() }?.let { out += "RRULE:" + it.removePrefix("RRULE:") }
        Recurrence.parseExDays(entry.exDays)
            .takeIf { it.isNotEmpty() }
            ?.let { days ->
                out += "EXDATE;VALUE=DATE:" + days.sorted()
                    .joinToString(",") { NoteDates.of(it).format(DATE) }
            }

        // The alert, as a VALARM. Every calendar reads this; nothing reads a reminder any other way.
        entry.reminderMinutes?.let { minutes ->
            out += "BEGIN:VALARM"
            out += "ACTION:DISPLAY"
            out += fold("DESCRIPTION:" + escape(entry.text))
            out += "TRIGGER:" + trigger(minutes)
            out += "END:VALARM"
        }
        out += "END:VEVENT"
        return out.joinToString(LINE_BREAK)
    }

    /**
     * A reminder as an iCalendar duration, counted back from the start.
     *
     * Whole days and whole hours are written as such rather than as a pile of minutes: `-P1D` is
     * what a calendar shows as "1 day before", and `-PT1440M` is the same instant shown as
     * "1440 minutes before" in at least one of them.
     */
    private fun trigger(minutes: Int): String = when {
        minutes <= 0 -> "-PT0M"
        minutes % 1440 == 0 -> "-P${minutes / 1440}D"
        minutes % 60 == 0 -> "-PT${minutes / 60}H"
        else -> "-PT${minutes}M"
    }

    /**
     * The escaping the format demands: backslash, semicolon, comma, newline. In that order —
     * escaping the backslash second would escape the ones this function just added.
     */
    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /**
     * Long lines, folded at 75 octets with a leading space on the continuation.
     *
     * Not cosmetic: the spec's limit is a hard one, and a long `SUMMARY` sent unfolded is a file
     * some parsers reject outright and others truncate. Folded on characters rather than bytes,
     * which is right for everything ASCII and slightly conservative otherwise — the failure
     * direction being a line shorter than it had to be.
     */
    private fun fold(line: String): String {
        if (line.length <= FOLD_AT) return line
        val out = StringBuilder(line.substring(0, FOLD_AT))
        var i = FOLD_AT
        while (i < line.length) {
            val end = minOf(i + FOLD_AT - 1, line.length)
            out.append(LINE_BREAK).append(' ').append(line, i, end)
            i = end
        }
        return out.toString()
    }

    /** CRLF, because the spec says CRLF and some parsers mean it. */
    private const val LINE_BREAK = "\r\n"
    private const val FOLD_AT = 73
    private const val DEFAULT_LENGTH_MINUTES = 30L

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
    private val STAMP_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
}
