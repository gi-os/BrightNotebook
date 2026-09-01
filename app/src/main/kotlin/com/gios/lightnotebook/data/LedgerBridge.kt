package com.gios.lightnotebook.data

import android.content.Context
import android.net.Uri
import com.gios.lightnotebook.util.Ledger
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the money did, read out of BrightLedger.
 *
 * The same shape [LightPassBridge] set and [DayBridges] repeated: ask a read-only provider,
 * take what comes, treat every failure as nothing. BrightLedger may not be installed, may be
 * an older build with no provider, or may refuse — none of which is worth a message on a
 * diary page. The provider keeps working while BrightLedger itself is PIN-locked; only the
 * sync behind it lags, which is what the `status` row is for.
 *
 * Nothing is copied. A transaction re-categorised or a bill re-dated over there is right here
 * on the next read, and this app's own database never learns what anything cost.
 */
object LedgerBridge {

    const val PACKAGE = "com.gios.brightledger"
    private const val SPENDING = "content://com.gios.brightledger.days/spending/"
    private const val BILLS = "content://com.gios.brightledger.days/bills/upcoming"
    private const val STATUS = "content://com.gios.brightledger.days/status"

    /**
     * The journal day's transactions, earliest first.
     *
     * Asked by **calendar date** with local-midnight windows at the other end, and this app's
     * days run four to four — so both dates the journal day touches are fetched and
     * [Ledger.onJournalDay] clips the pile to the real window. Skip either half and the
     * late-night taxi lands on the wrong day or nowhere, which is the bug the stays bridge
     * shipped once so that no other bridge has to.
     */
    fun spending(
        context: Context,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Ledger.Tx> {
        val txs = datesFor(epochDay).flatMap { date ->
            read(context, SPENDING + date) { c ->
                val postedAt = c.getColumnIndex("postedAt")
                val merchant = c.getColumnIndex("merchant")
                val amount = c.getColumnIndex("amountCents")
                val account = c.getColumnIndex("account")
                val pending = c.getColumnIndex("pending")
                if (postedAt < 0 || merchant < 0 || amount < 0) return@read null
                Ledger.Tx(
                    postedAt = c.getLong(postedAt),
                    merchant = c.getString(merchant).orEmpty(),
                    amountCents = c.getLong(amount),
                    account = account.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty(),
                    pending = pending.takeIf { it >= 0 }?.let { c.getInt(it) == 1 } ?: false,
                )
            }
        }
        return Ledger.onJournalDay(txs, epochDay, zone)
    }

    /**
     * The next 35 days of expected bills, soonest first, deduped.
     *
     * Merged into the calendar at read time the way LightPass showings are — never written
     * into the database, never given a reminder. A bill that stops being expected over there
     * simply stops appearing here.
     */
    fun bills(context: Context): List<Ledger.Bill> = Ledger.upcomingBills(
        read(context, BILLS) { c ->
            val due = c.getColumnIndex("dueEpochDay")
            val merchant = c.getColumnIndex("merchant")
            val amount = c.getColumnIndex("amountCents")
            val cadence = c.getColumnIndex("cadenceDays")
            if (due < 0 || merchant < 0 || amount < 0) return@read null
            Ledger.Bill(
                dueEpochDay = c.getLong(due),
                merchant = c.getString(merchant).orEmpty(),
                amountCents = c.getLong(amount),
                cadenceDays = cadence.takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0,
            ).takeIf { it.merchant.isNotBlank() }
        },
    )

    /** When the ledger last synced, epoch millis. Zero for never, and zero on any failure. */
    fun syncedAt(context: Context): Long =
        read(context, STATUS) { c ->
            val synced = c.getColumnIndex("syncedAt")
            if (synced < 0) null else c.getLong(synced)
        }.firstOrNull() ?: 0L

    /** The two calendar dates a four-to-four journal day touches. */
    private fun datesFor(epochDay: Long): List<String> = listOf(
        LocalDate.ofEpochDay(epochDay).toString(),
        LocalDate.ofEpochDay(epochDay + 1).toString(),
    )

    private inline fun <T : Any> read(
        context: Context,
        uri: String,
        crossinline row: (android.database.Cursor) -> T?,
    ): List<T> = runCatching {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
            val out = ArrayList<T>()
            while (cursor.moveToNext()) row(cursor)?.let { out.add(it) }
            out
        }.orEmpty()
    }.getOrDefault(emptyList())
}
