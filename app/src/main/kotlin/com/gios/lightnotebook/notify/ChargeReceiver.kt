package com.gios.lightnotebook.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gios.lightnotebook.data.ChargeStore

/**
 * Writes down when the phone was plugged in and unplugged.
 *
 * **Declared in the manifest, which is only possible because these two broadcasts are exempt** from
 * the ban on implicit broadcasts waking apps up (`ACTION_POWER_CONNECTED` and
 * `ACTION_POWER_DISCONNECTED` are on the exemption list precisely because they are rare and
 * user-initiated). That exemption is what makes this feature nearly free: no service, no alarm, no
 * polling, and nothing running at all on a day the cable never moves. Two events a day, twenty
 * bytes each.
 *
 * The work is done inline rather than through `goAsync` or a worker. An append to a text file is
 * microseconds, and the alternatives both cost more battery than the thing they would be deferring:
 * `goAsync` holds the broadcast's wakelock while a thread starts, and a `WorkManager` job means
 * waking the process again later to write one line.
 */
class ChargeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plugged = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> return
        }
        ChargeStore(context.applicationContext).record(System.currentTimeMillis(), plugged)
    }
}
