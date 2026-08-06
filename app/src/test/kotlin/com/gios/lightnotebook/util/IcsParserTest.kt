package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    private val newYork = ZoneId.of("America/New_York")

    /**
     * Built by hand rather than with a raw string: interpolating a block into one and
     * calling trimIndent leaves the wrapper lines indented, and a line starting with a
     * space is a *continuation* in this format — the whole file would fold into one line.
     */
    private fun ics(body: String) =
        "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//test//EN\n$body\nEND:VCALENDAR\n"

    @Test
    fun readsATimedEventInItsOwnZone() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:abc-123
                SUMMARY:Dentist
                DTSTART;TZID=America/New_York:20260729T093000
                DTEND;TZID=America/New_York:20260729T101500
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("abc-123", event.uid)
        assertEquals("Dentist", event.title)
        assertEquals(LocalDate.of(2026, 7, 29).toEpochDay(), event.epochDay)
        assertEquals(9 * 60 + 30, event.startMinutes)
        assertEquals(10 * 60 + 15, event.endMinutes)
    }

    @Test
    fun utcIsConvertedToTheLocalDay() {
        // 01:30 UTC on the 30th is 21:30 on the 29th in New York — the day matters.
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:utc-1
                SUMMARY:Late call
                DTSTART:20260730T013000Z
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        val event = events.single()
        assertEquals(LocalDate.of(2026, 7, 29).toEpochDay(), event.epochDay)
        assertEquals(21 * 60 + 30, event.startMinutes)
    }

    @Test
    fun allDayEventsHaveNoTime() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:allday-1
                SUMMARY:Holiday
                DTSTART;VALUE=DATE:20260814
                DTEND;VALUE=DATE:20260815
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        val event = events.single()
        assertEquals(LocalDate.of(2026, 8, 14).toEpochDay(), event.epochDay)
        assertNull(event.startMinutes)
        assertNull(event.endMinutes)
    }

    @Test
    fun foldedLinesAreRejoined() {
        // Unfolding removes the CRLF and exactly one space, restoring the original text —
        // so the space between the two words has to be on the wire, as it is here at the
        // end of the first line. Adding one back would corrupt every other folded value.
        val events = IcsParser.parse(
            ics(
                "BEGIN:VEVENT\nUID:fold-1\nSUMMARY:A very long title that the exporter \n" +
                    " decided to wrap\nDTSTART;VALUE=DATE:20260901\nEND:VEVENT",
            ),
            newYork,
        )
        assertEquals("A very long title that the exporter decided to wrap", events.single().title)
    }

    @Test
    fun escapedTextIsUnescaped() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:esc-1
                SUMMARY:Lunch\, then a walk\; maybe
                DTSTART;VALUE=DATE:20260901
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        assertEquals("Lunch, then a walk; maybe", events.single().title)
    }

    @Test
    fun eventsComeBackInOrder() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:b
                SUMMARY:Second
                DTSTART;TZID=America/New_York:20260729T140000
                END:VEVENT
                BEGIN:VEVENT
                UID:a
                SUMMARY:First
                DTSTART;TZID=America/New_York:20260729T090000
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        assertEquals(listOf("First", "Second"), events.map { it.title })
    }

    @Test
    fun anEndBeforeTheStartOrOnAnotherDayIsDropped() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:overnight
                SUMMARY:Overnight
                DTSTART;TZID=America/New_York:20260729T230000
                DTEND;TZID=America/New_York:20260730T010000
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        // The start is kept; the end is not pretended to belong to the same square.
        assertEquals(23 * 60, events.single().startMinutes)
        assertNull(events.single().endMinutes)
    }

    @Test
    fun anEventWithoutAUidStillGetsAStableOne() {
        val body = """
            BEGIN:VEVENT
            SUMMARY:No id here
            DTSTART;VALUE=DATE:20260901
            END:VEVENT
        """.trimIndent()
        val first = IcsParser.parse(ics(body), newYork).single()
        val second = IcsParser.parse(ics(body), newYork).single()
        assertEquals(first.uid, second.uid)
        assertTrue(first.uid.isNotBlank())
    }

    @Test
    fun anEventWithNoStartIsSkipped() {
        val events = IcsParser.parse(
            ics("BEGIN:VEVENT\nUID:nostart\nSUMMARY:Nowhere\nEND:VEVENT"),
            newYork,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun recognisesCalendarFiles() {
        assertTrue(IcsParser.looksLikeIcs("BEGIN:VCALENDAR\nVERSION:2.0\n"))
        assertFalse(IcsParser.looksLikeIcs("{\"not\": \"a calendar\"}"))
        assertFalse(IcsParser.looksLikeIcs(""))
    }

    @Test
    fun crlfFilesParse() {
        val events = IcsParser.parse(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:crlf\r\nSUMMARY:Windows\r\n" +
                "DTSTART;VALUE=DATE:20260901\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            newYork,
        )
        assertEquals("Windows", events.single().title)
    }

    @Test
    fun startMillisMatchesTheLocalWallClock() {
        val event = ImportedEvent(
            uid = "x",
            title = "x",
            epochDay = LocalDate.of(2026, 7, 29).toEpochDay(),
            startMinutes = 9 * 60 + 30,
        )
        val expected = LocalDate.of(2026, 7, 29)
            .atStartOfDay(newYork)
            .plusMinutes(570)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, IcsParser.startMillis(event, newYork))
        assertNull(IcsParser.startMillis(event.copy(startMinutes = null), newYork))
    }

    @Test
    fun feedNameIsReadFromTheCalendarHeader() {
        // A subscribed URL has no filename to borrow a label from, so this is what keeps the
        // calendars list from reading "cal.basilnet.com".
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:Work
            BEGIN:VEVENT
            UID:1
            SUMMARY:Standup
            DTSTART:20260804T130000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals("Work", IcsParser.calendarName(text))
    }

    @Test
    fun feedNameIsNullWhenTheCalendarDoesNotNameItself() {
        val text = "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR"
        assertNull(IcsParser.calendarName(text))
    }

    /* ---------------- recurrence ---------------- */

    @Test
    fun carriesTheRuleRatherThanExpandingIt() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:standup
                SUMMARY:Standup
                DTSTART;TZID=America/New_York:20260803T093000
                RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        // One row, not one per Monday for the rest of time.
        assertEquals(1, events.size)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", events.first().rrule)
        // And the row knows which days it means when something asks.
        val days = Recurrence.expand(
            events.first().rrule,
            events.first().epochDay,
            LocalDate.of(2026, 8, 3).toEpochDay(),
            LocalDate.of(2026, 8, 9).toEpochDay(),
        )
        assertEquals(3, days.size)
    }

    @Test
    fun readsExdatesOffTheFeed() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:standup
                SUMMARY:Standup
                DTSTART;TZID=America/New_York:20260803T093000
                RRULE:FREQ=DAILY
                EXDATE;TZID=America/New_York:20260804T093000,20260805T093000
                EXDATE;TZID=America/New_York:20260807T093000
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        val event = events.single()
        assertEquals(
            setOf(
                LocalDate.of(2026, 8, 4).toEpochDay(),
                LocalDate.of(2026, 8, 5).toEpochDay(),
                LocalDate.of(2026, 8, 7).toEpochDay(),
            ),
            event.exDays,
        )
        val days = Recurrence.expand(
            event.rrule,
            event.epochDay,
            LocalDate.of(2026, 8, 3).toEpochDay(),
            LocalDate.of(2026, 8, 7).toEpochDay(),
            event.exDays,
        )
        assertEquals(listOf(LocalDate.of(2026, 8, 3).toEpochDay(), LocalDate.of(2026, 8, 6).toEpochDay()), days)
    }

    @Test
    fun anOverriddenInstanceBecomesItsOwnEventAndAHoleInTheSeries() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:standup
                SUMMARY:Standup
                DTSTART;TZID=America/New_York:20260803T093000
                RRULE:FREQ=DAILY
                END:VEVENT
                BEGIN:VEVENT
                UID:standup
                RECURRENCE-ID;TZID=America/New_York:20260805T093000
                SUMMARY:Standup (moved)
                DTSTART;TZID=America/New_York:20260805T150000
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        assertEquals(2, events.size)
        val series = events.first { it.rrule != null }
        val moved = events.first { it.rrule == null }
        // Two rows that would otherwise fight over one sourceUid.
        assertTrue(moved.uid.startsWith("standup#"))
        assertEquals(15 * 60, moved.startMinutes)
        assertTrue(LocalDate.of(2026, 8, 5).toEpochDay() in series.exDays)
    }

    @Test
    fun anEventWithNoRuleStillHasNone() {
        val events = IcsParser.parse(
            ics(
                """
                BEGIN:VEVENT
                UID:once
                SUMMARY:Dentist
                DTSTART;VALUE=DATE:20260729
                END:VEVENT
                """.trimIndent(),
            ),
            newYork,
        )
        assertNull(events.single().rrule)
        assertTrue(events.single().exDays.isEmpty())
    }
}
