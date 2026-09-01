package com.gios.lightnotebook.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(nyc).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private fun tx(at: Long, merchant: String = "COFFEE", cents: Long = -450) =
        Ledger.Tx(postedAt = at, merchant = merchant, amountCents = cents, account = "Checking", pending = false)

    /* ---- the journal window: 4am to 4am, the whole reason this file exists ---- */

    @Test
    fun `a late-night taxi belongs to the night before`() {
        // Half past midnight on the 31st is the 30th's night. The bank files it under the
        // 31st, which is why both dates are fetched — but the *journal* day it lands on is
        // the 30th, and losing it is the bug this test pins.
        val taxi = tx(ms(2026, 7, 31, 0, 30), "TAXI")
        assertEquals(listOf(taxi), Ledger.onJournalDay(listOf(taxi), day(2026, 7, 30), nyc))
        assertEquals(emptyList<Ledger.Tx>(), Ledger.onJournalDay(listOf(taxi), day(2026, 7, 31), nyc))
    }

    @Test
    fun `four in the morning starts the new day's spending`() {
        val early = tx(ms(2026, 7, 31, 4, 0), "BODEGA")
        assertEquals(emptyList<Ledger.Tx>(), Ledger.onJournalDay(listOf(early), day(2026, 7, 30), nyc))
        assertEquals(listOf(early), Ledger.onJournalDay(listOf(early), day(2026, 7, 31), nyc))
    }

    @Test
    fun `three fifty-nine still belongs to the night`() {
        val last = tx(ms(2026, 7, 31, 3, 59), "BAR")
        assertEquals(listOf(last), Ledger.onJournalDay(listOf(last), day(2026, 7, 30), nyc))
    }

    @Test
    fun `an ordinary afternoon is unsurprising`() {
        val lunch = tx(ms(2026, 7, 30, 13, 0), "LUNCH")
        assertEquals(listOf(lunch), Ledger.onJournalDay(listOf(lunch), day(2026, 7, 30), nyc))
    }

    @Test
    fun `spring forward does not move the boundary off the wall clock`() {
        // 8 March 2026, clocks spring forward at 2am in New York: the day is 23 hours long.
        // 3:30am does not exist as a naive time plus offset, but the wall clock's 4am is
        // still where the day turns — a purchase at 4:30am belongs to the 8th, and one at
        // 1:30am to the 7th.
        val night = tx(ms(2026, 3, 8, 1, 30), "DINER")
        val morning = tx(ms(2026, 3, 8, 4, 30), "BAKERY")
        assertEquals(listOf(night), Ledger.onJournalDay(listOf(night, morning), day(2026, 3, 7), nyc))
        assertEquals(listOf(morning), Ledger.onJournalDay(listOf(night, morning), day(2026, 3, 8), nyc))
    }

    @Test
    fun `fall back keeps both one-thirties on the same night`() {
        // 1 November 2026, clocks fall back at 2am: 1:30am happens twice, an hour apart.
        // Both instants are the previous journal day's night.
        val base = LocalDateTime.of(2026, 11, 1, 0, 30).atZone(nyc).toInstant().toEpochMilli()
        val first = tx(base + 60 * 60_000L, "FIRST 1:30")
        val second = tx(base + 2 * 60 * 60_000L, "SECOND 1:30")
        val got = Ledger.onJournalDay(listOf(first, second), day(2026, 10, 31), nyc)
        assertEquals(listOf(first, second), got)
    }

    @Test
    fun `the overlap fetched twice is deduped and ordered`() {
        val a = tx(ms(2026, 7, 30, 9, 0), "A")
        val b = tx(ms(2026, 7, 30, 8, 0), "B")
        val got = Ledger.onJournalDay(listOf(a, b, a), day(2026, 7, 30), nyc)
        assertEquals(listOf(b, a), got)
    }

    @Test
    fun `two identical coffees with distinct timestamps are two coffees`() {
        val one = tx(ms(2026, 7, 30, 9, 0), "COFFEE")
        val two = one.copy(postedAt = one.postedAt + 1)
        assertEquals(2, Ledger.onJournalDay(listOf(one, two), day(2026, 7, 30), nyc).size)
    }

    /* ---- money: integer arithmetic only ---- */

    @Test
    fun `cents become dollars without a float in sight`() {
        assertEquals("$12.34", Ledger.money(-1234))
        assertEquals("$12.34", Ledger.money(1234))
        assertEquals("$0.05", Ledger.money(-5))
        assertEquals("$34.20", Ledger.money(-3420))
        assertEquals("$1500.00", Ledger.money(150_000))
        assertEquals("$0.00", Ledger.money(0))
    }

    /* ---- the summary line ---- */

    @Test
    fun `charges make the total and the count`() {
        val summary = Ledger.summarize(
            listOf(
                tx(1L, "A", -450),
                tx(2L, "B", -2500),
                tx(3L, "C", -470),
            ),
        )
        assertNotNull(summary)
        assertEquals(3420, summary!!.spentCents)
        assertEquals(3, summary.charges)
        assertEquals("SPENT $34.20 · 3", Ledger.summaryLine(summary))
    }

    @Test
    fun `a credit is its own news, not negative spending`() {
        val summary = Ledger.summarize(
            listOf(tx(1L, "SHOP", -1000), tx(2L, "REFUND", 1200)),
        )!!
        assertEquals(1000, summary.spentCents)
        assertEquals(1, summary.charges)
        assertEquals(1200, summary.backCents)
        assertEquals("SPENT $10.00 · 1 · $12.00 BACK", Ledger.summaryLine(summary))
    }

    @Test
    fun `only credits still says something`() {
        val summary = Ledger.summarize(listOf(tx(1L, "REFUND", 500)))!!
        assertEquals("$5.00 BACK", Ledger.summaryLine(summary))
    }

    @Test
    fun `nothing to summarise is null, not zero`() {
        assertNull(Ledger.summarize(emptyList()))
    }

    /* ---- "as of", for a total that is still running ---- */

    @Test
    fun `a sync inside the day labels the total`() {
        val label = Ledger.asOfLabel(ms(2026, 8, 31, 14, 5), day(2026, 8, 31), nyc)
        assertEquals("Mon 14:05", label)
    }

    @Test
    fun `a sync after the day ended needs no label`() {
        // The day of the 30th ends at 4am on the 31st; a sync at 9am covers all of it.
        assertNull(Ledger.asOfLabel(ms(2026, 7, 31, 9, 0), day(2026, 7, 30), nyc))
    }

    @Test
    fun `never synced is not a time`() {
        assertNull(Ledger.asOfLabel(0L, day(2026, 7, 30), nyc))
    }

    /* ---- bills ---- */

    @Test
    fun `the same bill served twice is one row, two due dates are two`() {
        val bills = Ledger.upcomingBills(
            listOf(
                Ledger.Bill(20700, "NETFLIX", -1549, 30),
                Ledger.Bill(20700, "Netflix", -1549, 30),
                Ledger.Bill(20714, "GYM", -4000, 14),
                Ledger.Bill(20728, "GYM", -4000, 14),
            ),
        )
        assertEquals(3, bills.size)
        assertTrue(bills[0].dueEpochDay <= bills[1].dueEpochDay)
    }

    @Test
    fun `a bill row says what is expected`() {
        assertEquals("$15.49 expected", Ledger.expectedLabel(Ledger.Bill(20700, "NETFLIX", -1549, 30)))
    }
}
