package com.gios.lightnotebook.notify

import com.gios.lightnotebook.data.DayEntryEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTimingTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 8, 3)
    private val epochDay = day.toEpochDay()

    /** 09:00 on the day, in millis, as the wall clock reads it. */
    private val nineAm = day.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

    private fun entry(startMinutes: Int?, lead: Int?) = DayEntryEntity(
        id = "e1",
        epochDay = epochDay,
        text = "Dentist",
        startMinutes = startMinutes,
        reminderMinutes = lead,
    )

    @Test
    fun leadTimeIsSubtractedFromTheStart() {
        val at = Reminders.triggerAtMillis(entry(9 * 60, 10), zone, now = nineAm - 3_600_000L)
        assertEquals(nineAm - 10 * 60_000L, at)
    }

    @Test
    fun zeroLeadFiresAtTheTime() {
        val at = Reminders.triggerAtMillis(entry(9 * 60, 0), zone, now = nineAm - 60_000L)
        assertEquals(nineAm, at)
    }

    @Test
    fun anEntryWithNoTimeCannotBeReminded() {
        assertNull(Reminders.triggerAtMillis(entry(null, 10), zone, now = nineAm - 3_600_000L))
    }

    @Test
    fun noLeadMeansNoReminder() {
        assertNull(Reminders.triggerAtMillis(entry(9 * 60, null), zone, now = nineAm - 3_600_000L))
    }

    @Test
    fun amomentAlreadyPastIsNotArmed() {
        // Ten past nine: the 08:50 reminder is history, and arming it would fire at once.
        val at = Reminders.triggerAtMillis(entry(9 * 60, 10), zone, now = nineAm + 600_000L)
        assertNull(at)
    }

    @Test
    fun aLeadLongerThanTheDayStillLandsBeforeTheEvent() {
        val at = Reminders.triggerAtMillis(entry(30, 60), zone, now = nineAm - 86_400_000L)
        val expected = day.atStartOfDay(zone).plusMinutes(30).toInstant().toEpochMilli() -
            60 * 60_000L
        assertEquals(expected, at)
    }

    @Test
    fun theOfferedLeadTimesAreSaneAndSorted() {
        assertEquals(Reminders.LEAD_CHOICES.sorted(), Reminders.LEAD_CHOICES)
        assertEquals(0, Reminders.LEAD_CHOICES.first())
        assertEquals(true, Reminders.DEFAULT_LEAD_MINUTES in Reminders.LEAD_CHOICES)
    }

    @Test
    fun aRepeatingEntryIsArmedForItsNextOccurrenceNotItsFirst() {
        // The series began on 3 August; it is now the 5th, before nine.
        val entry = entry(9 * 60, 10).copy(rrule = "FREQ=DAILY")
        val nowOnTheFifth = day.plusDays(2).atStartOfDay(zone).plusHours(7).toInstant().toEpochMilli()
        val expected = day.plusDays(2).atStartOfDay(zone).plusHours(9).minusMinutes(10)
            .toInstant().toEpochMilli()
        assertEquals(expected, Reminders.triggerAtMillis(entry, zone, now = nowOnTheFifth))
    }

    @Test
    fun anOccurrenceAlreadyGoneByArmsTheNextOne() {
        val entry = entry(9 * 60, 10).copy(rrule = "FREQ=DAILY")
        val afternoon = day.plusDays(2).atStartOfDay(zone).plusHours(15).toInstant().toEpochMilli()
        val expected = day.plusDays(3).atStartOfDay(zone).plusHours(9).minusMinutes(10)
            .toInstant().toEpochMilli()
        assertEquals(expected, Reminders.triggerAtMillis(entry, zone, now = afternoon))
    }

    @Test
    fun anExcludedOccurrenceIsNotRemindedAbout() {
        val skipped = day.plusDays(2).toEpochDay()
        val entry = entry(9 * 60, 10).copy(rrule = "FREQ=DAILY", exDays = skipped.toString())
        val nowOnTheFifth = day.plusDays(2).atStartOfDay(zone).plusHours(7).toInstant().toEpochMilli()
        val expected = day.plusDays(3).atStartOfDay(zone).plusHours(9).minusMinutes(10)
            .toInstant().toEpochMilli()
        assertEquals(expected, Reminders.triggerAtMillis(entry, zone, now = nowOnTheFifth))
    }

    @Test
    fun aFinishedSeriesHasNothingLeftToFire() {
        val entry = entry(9 * 60, 10).copy(rrule = "FREQ=DAILY;COUNT=2")
        val later = day.plusDays(9).atStartOfDay(zone).toInstant().toEpochMilli()
        assertNull(Reminders.triggerAtMillis(entry, zone, now = later))
    }
}
