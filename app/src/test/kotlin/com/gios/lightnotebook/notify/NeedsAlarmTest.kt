package com.gios.lightnotebook.notify

import com.gios.lightnotebook.data.DayEntryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows a re-arm pass must keep.
 *
 * The bug this exists to stop: both re-arm call sites filtered their freshly written rows on
 * `epochDay >= today`, and a repeating entry's stored day is the day the **series began**. A
 * weekly meeting imported in March therefore failed the test every day after March, so the hourly
 * calendar sync — which rewrites every imported row under a new id — left every recurring event on
 * the phone with no alarm at all until the next launch or reboot.
 */
class NeedsAlarmTest {

    private val today = 20_000L

    private fun entry(
        epochDay: Long,
        lead: Int? = 10,
        rrule: String? = null,
        endEpochDay: Long? = null,
    ) = DayEntryEntity(
        id = "e",
        epochDay = epochDay,
        text = "Standup",
        startMinutes = 9 * 60,
        endEpochDay = endEpochDay,
        reminderMinutes = lead,
        rrule = rrule,
    )

    @Test
    fun aSeriesIsKeptHoweverLongAgoItStarted() {
        assertTrue(Reminders.needsAlarm(entry(today - 400, rrule = "FREQ=WEEKLY"), today))
    }

    @Test
    fun aSeriesWithNoReminderIsNotKept() {
        assertFalse(
            Reminders.needsAlarm(entry(today - 400, lead = null, rrule = "FREQ=WEEKLY"), today),
        )
    }

    @Test
    fun todayIsStillAhead() {
        assertTrue(Reminders.needsAlarm(entry(today), today))
    }

    @Test
    fun aPlainEntryInThePastIsDropped() {
        assertFalse(Reminders.needsAlarm(entry(today - 1), today))
    }

    @Test
    fun aSpanIsJudgedOnItsLastDay() {
        // A conference that started on Monday is still running on Wednesday, and the row is one
        // row: judging it on `epochDay` would retire it the morning after it began.
        assertTrue(Reminders.needsAlarm(entry(today - 2, endEpochDay = today + 2), today))
        assertFalse(Reminders.needsAlarm(entry(today - 5, endEpochDay = today - 3), today))
    }

    @Test
    fun aBlankRruleIsNotASeries() {
        assertFalse(Reminders.needsAlarm(entry(today - 400, rrule = "  "), today))
    }
}
