package com.gios.lightnotebook.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.gios.lightnotebook.util.Calls

/**
 * Calls on a day, read straight out of the system's call log.
 *
 * Nothing is recorded and nothing is cached: the provider already holds weeks of history, so a day
 * from last month answers without this app having been running for it.
 *
 * **`CACHED_NAME` is why this needs no contacts permission.** The dialler writes the display name
 * it resolved into the call log row at the time of the call, so the name arrives with the call.
 * Asking for `READ_CONTACTS` as well would buy a name for numbers the phone never knew — which is
 * to say, almost none — in exchange for a second grant and read access to every contact.
 *
 * The permission is a runtime one with no dialog worth showing on a phone with no Settings app, so
 * like usage access it is an adb grant and the app says so plainly.
 */
object CallHistory {

    const val GRANT_COMMAND =
        "adb shell pm grant com.gios.lightnotebook android.permission.READ_CALL_LOG"

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Calls that began inside the window.
     *
     * Selected on `DATE` rather than filtered in memory: a phone with years of call history would
     * otherwise read all of it to show one Tuesday.
     */
    fun forWindow(context: Context, fromMs: Long, untilMs: Long): List<Calls.Call> {
        if (!granted(context)) return emptyList()
        val projection = arrayOf(
            CallLog.Calls.DATE,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
        )
        // Every failure here is empty rather than loud, the same as every other bridge: a revoked
        // permission or a phone with no dialler at all should leave the day looking quiet, not
        // broken.
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf(fromMs.toString(), untilMs.toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { cursor ->
                val out = ArrayList<Calls.Call>(cursor.count)
                val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                while (cursor.moveToNext()) {
                    val kind = kindOf(cursor.getInt(type)) ?: continue
                    out.add(
                        Calls.Call(
                            atMs = cursor.getLong(date),
                            name = cursor.getString(name),
                            number = cursor.getString(number),
                            kind = kind,
                            seconds = cursor.getInt(duration),
                        ),
                    )
                }
                out.filter { Calls.worthShowing(it) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Only the three kinds a day can describe.
     *
     * Voicemails, rejected and blocked calls are deliberately dropped: a blocked call is something
     * the phone did, not something that happened to you, and a day that reported them would be
     * describing its own spam filter.
     */
    private fun kindOf(type: Int): Calls.Kind? = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> Calls.Kind.Outgoing
        CallLog.Calls.INCOMING_TYPE -> Calls.Kind.Incoming
        CallLog.Calls.MISSED_TYPE -> Calls.Kind.Missed
        else -> null
    }
}
