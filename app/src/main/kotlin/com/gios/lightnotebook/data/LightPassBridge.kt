package com.gios.lightnotebook.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** One film screening, read out of LightPass. Times are minutes from midnight. */
data class PassShowing(
    val passId: String,
    val title: String,
    val theater: String?,
    val seat: String?,
    val epochDay: Long,
    val startMinutes: Int?,
    val endMinutes: Int?,
) {
    /** "Regal Union Square · F12", whichever parts the ticket actually carried. */
    val where: String?
        get() = listOfNotNull(theater?.takeIf { it.isNotBlank() }, seat?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
}

/**
 * Reads film showings out of `gi-os/LightPass` (launcher name "Movie Tickets") and hands
 * back a way to open the stub.
 *
 * Tickets live in LightPass's own storage — this asks its content provider rather than
 * copying anything, so a ticket that is deleted or re-dated there stops being wrong here
 * on the next read. Nothing is written to the notebook's own tables.
 *
 * Every failure here is silent and empty: LightPass may not be installed, may be an older
 * build with no provider, or may refuse the call. None of those is worth a message on a
 * calendar screen.
 */
object LightPassBridge {

    const val PACKAGE = "com.gios.lightpass"
    private val CONTENT_URI: Uri = Uri.parse("content://com.gios.lightpass.passes/passes")

    fun showings(context: Context): List<PassShowing> = runCatching {
        context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
            val passId = cursor.getColumnIndex("pass_id")
            val title = cursor.getColumnIndex("title")
            val theater = cursor.getColumnIndex("theater")
            val seat = cursor.getColumnIndex("seat")
            val epochDay = cursor.getColumnIndex("epoch_day")
            val start = cursor.getColumnIndex("start_minutes")
            val end = cursor.getColumnIndex("end_minutes")
            if (passId < 0 || title < 0 || epochDay < 0) return@use emptyList()

            val out = mutableListOf<PassShowing>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(passId) ?: continue
                out.add(
                    PassShowing(
                        passId = id,
                        title = cursor.getString(title)?.takeIf { it.isNotBlank() } ?: "Film",
                        theater = theater.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        seat = seat.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        epochDay = cursor.getLong(epochDay),
                        startMinutes = start.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { cursor.getInt(it) },
                        endMinutes = end.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { cursor.getInt(it) },
                    ),
                )
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Opens the ticket in LightPass. Falls back to opening the app at its shelf if the
     * installed build is older than the `lightpass://` link, so the tap still goes
     * somewhere useful.
     */
    fun openPass(context: Context, passId: String): Boolean {
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("lightpass://pass/$passId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, deepLink)) return true

        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch != null && start(context, launch)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
