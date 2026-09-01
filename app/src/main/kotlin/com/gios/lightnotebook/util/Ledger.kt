package com.gios.lightnotebook.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * What the money did, as a journal fact.
 *
 * The rows come out of BrightLedger's `days` provider and everything fiddly about them is done
 * here, Android-free, where it can be tested: the journal-window filter (a journal day runs
 * 4am to 4am, so a transaction after midnight belongs to the night before), the cents-to-words
 * formatting (a `Long` of cents, never a float — floats are how $34.20 becomes $34.19), and
 * the one-line summary a day carries at its foot.
 *
 * Amounts follow the provider's convention throughout: **charges are negative, credits are
 * positive.** Nothing in here flips a sign into a different column; display code asks for the
 * magnitude when the direction is already in the words around it.
 */
object Ledger {

    /** One transaction, as BrightLedger serves it. [amountCents] negative for a charge. */
    data class Tx(
        val postedAt: Long,
        val merchant: String,
        val amountCents: Long,
        val account: String,
        val pending: Boolean,
    ) {
        val isCharge: Boolean get() = amountCents < 0
    }

    /** One expected bill. [amountCents] negative, like the charge it will become. */
    data class Bill(
        val dueEpochDay: Long,
        val merchant: String,
        val amountCents: Long,
        val cadenceDays: Int,
    )

    /**
     * A day's transactions, in one line and a count.
     *
     * [asOf] is set when the ledger's last sync predates the end of the day being looked at —
     * which is every time today is open — so the line can say it is a running total rather
     * than a closed one.
     */
    data class DaySummary(
        val spentCents: Long,
        val charges: Int,
        val backCents: Long,
        val credits: Int,
        val asOf: String?,
    )

    /**
     * The transactions that belong to a journal day, in order.
     *
     * The provider is asked by **calendar date** and a journal day spans two of them, so the
     * caller fetches both dates and this filters the pile to the real 4am-to-4am window —
     * the same shape every bridge in [com.gios.lightnotebook.data.DayBridges] uses, and the
     * same bug it exists to prevent: skip it and everything bought after midnight silently
     * vanishes from the night it belongs to.
     *
     * Both dates are fetched, so anything in the overlap arrives twice; deduped on the whole
     * fact, because two coffees from the same machine in the same minute are two coffees only
     * when the bank says so with two distinct `postedAt`s.
     */
    fun onJournalDay(txs: List<Tx>, epochDay: Long, zone: ZoneId): List<Tx> {
        val window = JournalDay.windowMs(epochDay, zone)
        return txs
            .filter { it.postedAt in window }
            .sortedBy { it.postedAt }
            .distinctBy { Triple(it.postedAt, it.merchant, it.amountCents) }
    }

    /**
     * Cents as dollars, by integer arithmetic only: "$12.34". The sign is dropped — every
     * caller says which way the money went in words, and "-$12.34 spent" says it twice.
     */
    fun money(cents: Long): String {
        val magnitude = abs(cents)
        return "$" + (magnitude / 100) + "." + (magnitude % 100).toString().padStart(2, '0')
    }

    /** Null when there is nothing to summarise — the section is absent, not empty. */
    fun summarize(txs: List<Tx>, asOf: String? = null): DaySummary? {
        if (txs.isEmpty()) return null
        val charges = txs.filter { it.isCharge }
        val credits = txs.filterNot { it.isCharge }
        return DaySummary(
            spentCents = -charges.sumOf { it.amountCents },
            charges = charges.size,
            backCents = credits.sumOf { it.amountCents },
            credits = credits.size,
            asOf = asOf,
        )
    }

    /**
     * "SPENT $34.20 · 3", with " · $12.00 BACK" when something came back. Only charges make
     * the total — a refund is not negative spending, it is its own small good news.
     */
    fun summaryLine(summary: DaySummary): String {
        val parts = mutableListOf<String>()
        if (summary.charges > 0) parts.add("SPENT ${money(summary.spentCents)} · ${summary.charges}")
        if (summary.credits > 0) parts.add("${money(summary.backCents)} BACK")
        return parts.joinToString(" · ")
    }

    /**
     * "as of Mon 14:05", or null when the sync already covers the whole of the day shown.
     *
     * BrightLedger's `status` row can lag — reads work while the app is PIN-locked, but the
     * sync behind them does not — and a total labelled with when it was true stops being wrong
     * when it is merely old. Zero means never synced, which is not a time worth printing.
     */
    fun asOfLabel(syncedAt: Long, epochDay: Long, zone: ZoneId): String? {
        if (syncedAt <= 0L) return null
        val window = JournalDay.windowMs(epochDay, zone)
        if (syncedAt > window.last) return null
        return DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)
            .format(Instant.ofEpochMilli(syncedAt).atZone(zone))
    }

    /**
     * The bills worth listing, one row per bill per due day.
     *
     * The provider may serve the same bill more than once — its 35-day horizon can hold two
     * due dates of a fortnightly bill, which are genuinely two rows, and can also repeat one
     * — so identity is the merchant on the day, and soonest stays first.
     */
    fun upcomingBills(bills: List<Bill>): List<Bill> = bills
        .sortedBy { it.dueEpochDay }
        .distinctBy { it.merchant.lowercase() to it.dueEpochDay }

    /** "$15.49 expected" — what a bill row says under the merchant's name. */
    fun expectedLabel(bill: Bill): String = money(bill.amountCents) + " expected"
}
