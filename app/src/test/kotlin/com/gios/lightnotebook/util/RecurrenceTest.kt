package com.gios.lightnotebook.util

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private fun expand(
        rule: String?,
        start: LocalDate,
        from: LocalDate,
        to: LocalDate,
        exDays: Set<Long> = emptySet(),
    ) = Recurrence.expand(rule, start.toEpochDay(), from.toEpochDay(), to.toEpochDay(), exDays)
        .map { LocalDate.ofEpochDay(it) }

    /* ---------------- parsing ---------------- */

    @Test
    fun parsesTheSupportedSubset() {
        val parsed = Recurrence.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=6")
        val rule = (parsed as Recurrence.Parsed.Rule).rule
        assertEquals(RepeatFreq.WEEKLY, rule.freq)
        assertEquals(2, rule.interval)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), rule.byDay.map { it.day })
        assertEquals(6, rule.count)
        assertNull(rule.untilDay)
    }

    @Test
    fun toleratesTheRrulePrefixAndLowercase() {
        assertTrue(Recurrence.parse("RRULE:freq=daily") is Recurrence.Parsed.Rule)
    }

    @Test
    fun nothingAtAllIsNotARule() {
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse("   "))
        assertNull(Recurrence.describe(null))
    }

    @Test
    fun refusesWhatItCannotDo() {
        val refused = listOf(
            "FREQ=HOURLY",
            "FREQ=MONTHLY;BYSETPOS=1;BYDAY=MO",
            "FREQ=YEARLY;BYWEEKNO=3",
            "FREQ=WEEKLY;WKST=SU;BYDAY=SU",
            "FREQ=DAILY;COUNT=3;UNTIL=20260101",
            "FREQ=DAILY;INTERVAL=nonsense",
        )
        refused.forEach { assertTrue(it, Recurrence.parse(it) is Recurrence.Parsed.Unsupported) }
    }

    @Test
    fun refusesByPartsOnTheWrongFrequency() {
        val start = day(2026, 8, 5)
        assertNotNull(Recurrence.unsupportedReason("FREQ=DAILY;BYDAY=MO", start))
        assertNotNull(Recurrence.unsupportedReason("FREQ=WEEKLY;BYMONTHDAY=3", start))
        assertNotNull(Recurrence.unsupportedReason("FREQ=WEEKLY;BYDAY=1MO", start))
        assertNotNull(Recurrence.unsupportedReason("FREQ=YEARLY;BYDAY=MO", start))
        assertNull(Recurrence.unsupportedReason("FREQ=MONTHLY;BYDAY=-1FR", start))
    }

    @Test
    fun aYearlyRuleMayRestateItsOwnStartDate() {
        // The usual exporter output for "every 4 July": redundant, so it is honoured.
        val start = day(2026, 7, 4)
        assertNull(Recurrence.unsupportedReason("FREQ=YEARLY;BYMONTH=7;BYMONTHDAY=4", start))
        // A BYMONTH that disagrees with the start would need a real BY-part cascade.
        assertNotNull(Recurrence.unsupportedReason("FREQ=YEARLY;BYMONTH=9;BYMONTHDAY=4", start))
    }

    /* ---------------- the loops that must not spin ---------------- */

    @Test
    fun intervalZeroIsRefusedRatherThanLoopingForever() {
        val start = LocalDate.of(2026, 8, 5)
        assertTrue(Recurrence.parse("FREQ=DAILY;INTERVAL=0") is Recurrence.Parsed.Unsupported)
        // And an event carrying it still shows, once, on the day it starts.
        val days = expand("FREQ=DAILY;INTERVAL=0", start, start, start.plusDays(30))
        assertEquals(listOf(start), days)
    }

    @Test
    fun anUnsupportedRuleShowsOnItsStartDayAndNowhereElse() {
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO", start, start.minusDays(10), start.plusDays(200))
        assertEquals(listOf(start), days)
        assertEquals(Recurrence.UNSUPPORTED_LABEL, Recurrence.describe("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO"))
    }

    @Test
    fun anUnboundedRuleIsCappedByTheWindow() {
        val start = LocalDate.of(2020, 1, 1)
        val days = expand("FREQ=DAILY", start, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
        assertEquals(31, days.size)
        assertEquals(LocalDate.of(2026, 8, 1), days.first())
        assertEquals(LocalDate.of(2026, 8, 31), days.last())
    }

    @Test
    fun theCapIsHonouredEvenWhenTheWindowIsHuge() {
        val start = LocalDate.of(2026, 1, 1)
        val days = Recurrence.expand(
            "FREQ=DAILY",
            start.toEpochDay(),
            start.toEpochDay(),
            start.plusYears(50).toEpochDay(),
        )
        assertEquals(Recurrence.MAX_OCCURRENCES, days.size)
    }

    @Test
    fun anEmptyWindowProducesNothing() {
        val start = LocalDate.of(2026, 8, 5)
        assertTrue(expand("FREQ=DAILY", start, start.plusDays(10), start.plusDays(5)).isEmpty())
    }

    /* ---------------- daily ---------------- */

    @Test
    fun dailyWithAnInterval() {
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=DAILY;INTERVAL=3", start, start, start.plusDays(10))
        assertEquals(
            listOf(start, start.plusDays(3), start.plusDays(6), start.plusDays(9)),
            days,
        )
    }

    @Test
    fun countEndsTheSeriesEvenOutsideTheWindow() {
        val start = LocalDate.of(2026, 8, 5)
        // Five occurrences: the 5th to the 9th. A window starting on the 8th sees two.
        val days = expand("FREQ=DAILY;COUNT=5", start, start.plusDays(3), start.plusDays(30))
        assertEquals(listOf(start.plusDays(3), start.plusDays(4)), days)
    }

    @Test
    fun untilEndsTheSeriesOnItsOwnDay() {
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=DAILY;UNTIL=20260807T235959Z", start, start, start.plusDays(30))
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), days)
    }

    /* ---------------- weekly ---------------- */

    @Test
    fun weeklyOnSeveralDays() {
        // 2026-08-05 is a Wednesday.
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=WEEKLY;BYDAY=MO,WE", start, start, start.plusDays(14))
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 19),
            ),
            days,
        )
    }

    @Test
    fun weeklyNeverGoesBackBeforeItsOwnStart() {
        // Monday of the start week is behind the start date; it must not be generated.
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=WEEKLY;BYDAY=MO", start, start.minusDays(14), start.plusDays(9))
        assertEquals(listOf(LocalDate.of(2026, 8, 10)), days)
    }

    @Test
    fun weeklyWithNoByDayFollowsTheStartWeekday() {
        val start = LocalDate.of(2026, 8, 5)
        val days = expand("FREQ=WEEKLY;INTERVAL=2", start, start, start.plusDays(30))
        assertEquals(listOf(start, start.plusDays(14), start.plusDays(28)), days)
    }

    /* ---------------- monthly, and the 31st ---------------- */

    @Test
    fun monthlyOnTheThirtyFirstSkipsShortMonths() {
        // RFC 5545: a month with no 31st simply produces no occurrence. Skipping, not clamping —
        // a user who means the end of the month gets BYMONTHDAY=-1, tested below.
        val start = LocalDate.of(2026, 1, 31)
        val days = expand("FREQ=MONTHLY", start, start, LocalDate.of(2026, 6, 30))
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 5, 31),
            ),
            days,
        )
    }

    @Test
    fun monthlyOnTheLastDayLandsEveryMonth() {
        val start = LocalDate.of(2026, 1, 31)
        val days = expand("FREQ=MONTHLY;BYMONTHDAY=-1", start, start, LocalDate.of(2026, 4, 30))
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
            ),
            days,
        )
    }

    @Test
    fun monthlyOnTheFirstMonday() {
        val start = LocalDate.of(2026, 8, 3)
        val days = expand("FREQ=MONTHLY;BYDAY=1MO", start, start, LocalDate.of(2026, 11, 30))
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 11, 2),
            ),
            days,
        )
    }

    @Test
    fun monthlyOnTheLastFriday() {
        val start = LocalDate.of(2026, 8, 28)
        val days = expand("FREQ=MONTHLY;BYDAY=-1FR", start, start, LocalDate.of(2026, 10, 31))
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 30),
            ),
            days,
        )
    }

    /* ---------------- yearly ---------------- */

    @Test
    fun yearlyKeepsTheDateAndSkipsTheTwentyNinthOfFebruary() {
        val start = LocalDate.of(2024, 2, 29)
        val days = expand("FREQ=YEARLY", start, start, LocalDate.of(2029, 12, 31))
        assertEquals(listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29)), days)
    }

    /* ---------------- EXDATE ---------------- */

    @Test
    fun exdatesRemoveOneOccurrenceAndLeaveTheRest() {
        val start = LocalDate.of(2026, 8, 5)
        val skipped = setOf(start.plusDays(1).toEpochDay())
        val days = expand("FREQ=DAILY", start, start, start.plusDays(3), skipped)
        assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), days)
    }

    @Test
    fun anExdateDoesNotLengthenACountedSeries() {
        val start = LocalDate.of(2026, 8, 5)
        val skipped = setOf(start.plusDays(1).toEpochDay())
        val days = expand("FREQ=DAILY;COUNT=3", start, start, start.plusDays(30), skipped)
        assertEquals(listOf(start, start.plusDays(2)), days)
    }

    @Test
    fun exdatesRoundTripThroughTheColumn() {
        val stored = Recurrence.formatExDays(setOf(3L, 1L, 2L))
        assertEquals("1,2,3", stored)
        assertEquals(setOf(1L, 2L, 3L), Recurrence.parseExDays(stored))
        assertEquals(emptySet<Long>(), Recurrence.parseExDays(null))
        assertEquals(emptySet<Long>(), Recurrence.parseExDays(""))
        assertNull(Recurrence.formatExDays(emptySet()))
    }

    /* ---------------- writing, and the round trip ---------------- */

    @Test
    fun formattingAndParsingAgree() {
        val rule = RecurrenceRule(
            freq = RepeatFreq.MONTHLY,
            interval = 3,
            byDay = listOf(DayPosition(-1, DayOfWeek.FRIDAY)),
            count = 12,
        )
        val text = Recurrence.format(rule)
        assertEquals("FREQ=MONTHLY;INTERVAL=3;BYDAY=-1FR;COUNT=12", text)
        assertEquals(rule, (Recurrence.parse(text) as Recurrence.Parsed.Rule).rule)
    }

    @Test
    fun anUntilRoundTripsToTheSameDay() {
        val until = day(2026, 12, 31)
        val text = Recurrence.format(RecurrenceRule(RepeatFreq.WEEKLY, untilDay = until))
        assertEquals(until, (Recurrence.parse(text) as Recurrence.Parsed.Rule).rule.untilDay)
    }

    @Test
    fun anIntervalOfOneIsLeftOutOfTheText() {
        assertEquals("FREQ=DAILY", Recurrence.format(RecurrenceRule(RepeatFreq.DAILY, interval = 1)))
    }

    /* ---------------- the next one, for reminders ---------------- */

    @Test
    fun theNextOccurrenceSkipsWhatHasBeenExcluded() {
        val start = LocalDate.of(2026, 8, 5)
        val next = Recurrence.nextOnOrAfter(
            "FREQ=DAILY",
            start.toEpochDay(),
            start.plusDays(2).toEpochDay(),
            setOf(start.plusDays(2).toEpochDay()),
        )
        assertEquals(start.plusDays(3).toEpochDay(), next)
    }

    @Test
    fun aFinishedSeriesHasNoNextOccurrence() {
        val start = LocalDate.of(2026, 8, 5)
        assertNull(
            Recurrence.nextOnOrAfter("FREQ=DAILY;COUNT=2", start.toEpochDay(), start.plusDays(10).toEpochDay()),
        )
    }

    /* ---------------- words ---------------- */

    @Test
    fun describesARuleInEnglish() {
        assertEquals("Every day", Recurrence.describe("FREQ=DAILY"))
        assertEquals("Every 2 weeks on Mon, Wed", Recurrence.describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("Every month on the last day", Recurrence.describe("FREQ=MONTHLY;BYMONTHDAY=-1"))
        assertEquals("Every month on the first Mon", Recurrence.describe("FREQ=MONTHLY;BYDAY=1MO"))
        assertEquals("Every year · 5 times", Recurrence.describe("FREQ=YEARLY;COUNT=5"))
        assertEquals("Every day · until 2026-12-31", Recurrence.describe("FREQ=DAILY;UNTIL=20261231"))
    }

    @Test
    fun supportIsReportedAgainstTheStartDate() {
        assertTrue(Recurrence.isSupported("FREQ=WEEKLY;BYDAY=MO", day(2026, 8, 5)))
        assertFalse(Recurrence.isSupported("FREQ=DAILY;BYDAY=MO", day(2026, 8, 5)))
        assertFalse(Recurrence.isSupported(null, day(2026, 8, 5)))
    }
}
