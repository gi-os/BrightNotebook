package com.gios.lightnotebook.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {

    private val day = LocalDate.of(2026, 8, 3).toEpochDay()

    private fun entry(
        id: String,
        title: String,
        minutes: Int? = null,
        label: String? = null,
        lead: Int? = null,
    ) = AgendaRow(
        id = "entry:$id",
        epochDay = day,
        minutes = minutes,
        title = title,
        label = label,
        reminderMinutes = lead,
        entryId = id,
    )

    private fun film(id: String, title: String, minutes: Int? = null, where: String? = null) =
        AgendaRow(
            id = "pass:$id",
            epochDay = day,
            minutes = minutes,
            title = title,
            label = where,
            passId = id,
        )

    /* ---------- ordering and keys ---------- */

    @Test
    fun rowsComeBackInTimeOrderWithAllDayFirst() {
        val rows = Agenda.merge(
            listOf(
                entry("a", "Late", minutes = 20 * 60),
                entry("b", "All day"),
                entry("c", "Morning", minutes = 9 * 60),
            ),
        )
        assertEquals(listOf("All day", "Morning", "Late"), rows.map { it.title })
    }

    @Test
    fun twoThingsWithTheSameNameDayAndTimeAreBothKept() {
        // This is the crash: identical text on the same day is ordinary, and every row still
        // needs its own key.
        val rows = Agenda.merge(
            listOf(entry("1", "Gym", minutes = 9 * 60), entry("2", "Gym", minutes = 9 * 60)),
        )
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.id }.toSet().size)
    }

    @Test
    fun aRepeatedIdIsDroppedRatherThanCrashingTheList() {
        val duplicate = entry("1", "Gym")
        val rows = Agenda.merge(listOf(duplicate), listOf(duplicate))
        assertEquals(1, rows.size)
    }

    /* ---------- folding tickets into entries ---------- */

    @Test
    fun aTicketAndItsCalendarEntryBecomeOneRow() {
        val rows = Agenda.collapse(
            entries = listOf(entry("1", "Dune Part Two", minutes = 19 * 60 + 30, lead = 30)),
            films = listOf(film("p1", "DUNE: PART TWO", minutes = 19 * 60 + 30, where = "Regal")),
        )
        val row = rows.single()
        // The ticket wins the tap; the entry's reminder and the entry's own id survive.
        assertEquals("p1", row.passId)
        assertEquals("1", row.entryId)
        assertEquals(30, row.reminderMinutes)
        assertEquals("Regal", row.label)
    }

    @Test
    fun anAllDayEntryStillMatchesATimedTicket() {
        val rows = Agenda.collapse(
            entries = listOf(entry("1", "Dune", lead = 60)),
            films = listOf(film("p1", "Dune", minutes = 19 * 60)),
        )
        val row = rows.single()
        assertEquals(19 * 60, row.minutes)
        assertEquals(60, row.reminderMinutes)
    }

    @Test
    fun differentFilmsOnOneDayStayApart() {
        val rows = Agenda.collapse(
            entries = listOf(entry("1", "Dune", minutes = 19 * 60)),
            films = listOf(film("p1", "Paddington", minutes = 19 * 60)),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun aDoubleFeatureKeepsBothRows() {
        val rows = Agenda.collapse(
            entries = listOf(
                entry("1", "Dune", minutes = 14 * 60),
                entry("2", "Dune", minutes = 20 * 60),
            ),
            films = listOf(
                film("p1", "Dune", minutes = 14 * 60),
                film("p2", "Dune", minutes = 20 * 60),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("p1", "p2"), rows.map { it.passId })
        assertEquals(listOf("1", "2"), rows.map { it.entryId })
    }

    @Test
    fun aTicketOnAnotherDayIsNotFolded() {
        val rows = Agenda.collapse(
            entries = listOf(entry("1", "Dune", minutes = 19 * 60)),
            films = listOf(film("p1", "Dune", minutes = 19 * 60).copy(epochDay = day + 1)),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun aTicketHoursFromTheEntryIsNotFolded() {
        val rows = Agenda.collapse(
            entries = listOf(entry("1", "Dune", minutes = 11 * 60)),
            films = listOf(film("p1", "Dune", minutes = 22 * 60)),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun anUnmatchedTicketPassesThrough() {
        val rows = Agenda.collapse(entries = emptyList(), films = listOf(film("p1", "Dune")))
        assertEquals("p1", rows.single().passId)
        assertNull(rows.single().entryId)
    }

    /* ---------- the matching itself ---------- */

    @Test
    fun titlesMatchThroughCasePunctuationAndFiller() {
        assertTrue(Agenda.titlesAgree("DUNE: PART TWO", "Dune Part Two"))
        assertTrue(Agenda.titlesAgree("Dune", "The Dune movie"))
        assertTrue(Agenda.titlesAgree("Paddington in Peru", "paddington peru"))
    }

    @Test
    fun differentTitlesDoNotMatch() {
        assertFalse(Agenda.titlesAgree("Dune", "Paddington"))
        assertFalse(Agenda.titlesAgree("Dentist", "Dune"))
        assertFalse(Agenda.titlesAgree("", "Dune"))
        // All filler and nothing else is not a match to anything.
        assertFalse(Agenda.titlesAgree("the movie", "the film"))
    }

    @Test
    fun timesAgreeWithinTheWindowOnly() {
        assertTrue(Agenda.timesAgree(null, 19 * 60))
        assertTrue(Agenda.timesAgree(19 * 60, null))
        assertTrue(Agenda.timesAgree(19 * 60, 19 * 60 + 30))
        assertFalse(Agenda.timesAgree(19 * 60, 21 * 60))
    }

    @Test
    fun keywordsDropFillerAndPunctuation() {
        assertEquals(setOf("dune", "part", "two"), Agenda.keywords("DUNE: PART TWO"))
        assertEquals(emptySet<String>(), Agenda.keywords("The movie!"))
    }

    /* ---------- headings ---------- */

    @Test
    fun headingsNameTodayAndTomorrow() {
        assertTrue(Agenda.heading(day, day).startsWith("TODAY · "))
        assertTrue(Agenda.heading(day + 1, day).startsWith("TOMORROW · "))
        assertFalse(Agenda.heading(day + 2, day).contains("·"))
    }

    /* ---------- subtitles ---------- */

    @Test
    fun subtitleJoinsWhatThereIs() {
        assertEquals("Work · 10 min before", entry("1", "x", label = "Work", lead = 10).subtitle)
        assertEquals("at the time", entry("1", "x", lead = 0).subtitle)
        assertEquals("Work", entry("1", "x", label = "Work").subtitle)
        assertNull(entry("1", "x").subtitle)
    }
}
