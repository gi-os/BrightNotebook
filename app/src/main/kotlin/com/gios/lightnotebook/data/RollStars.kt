package com.gios.lightnotebook.data

import android.content.Context
import android.net.Uri

/**
 * The photographs you starred in Roll.
 *
 * **The one thing about a photograph that MediaStore cannot answer.** Everything else this app shows
 * — which days have pictures, when they were taken, what they look like — comes from the system, so
 * the calendar needs no agreement with Roll at all. A star is different: `IS_FAVORITE` is
 * effectively writable only by the system gallery, so Roll keeps its own list, and this is the one
 * place the two apps genuinely have to talk.
 *
 * **Matched by file name, not by id.** That is Roll's choice and the right one: an id is a row
 * number, and a rescan or a restored backup gives the same photograph a different one, which would
 * quietly empty a favourites list. So the query asks MediaStore for `DISPLAY_NAME` too and matches
 * on that.
 *
 * Every failure is an empty set. Roll may not be installed, may be an older build with no provider,
 * or may refuse the call — none of those is worth a message on a calendar, and the cover simply
 * falls back to the day's first photograph.
 */
object RollStars {

    private val CONTENT_URI: Uri = Uri.parse("content://com.gios.lightcamera.stars/stars")
    private const val COLUMN_NAME = "display_name"

    fun names(context: Context): Set<String> = runCatching {
        context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(COLUMN_NAME)
            if (nameCol < 0) return@use emptySet()
            val out = HashSet<String>()
            while (cursor.moveToNext()) {
                cursor.getString(nameCol)?.takeIf { it.isNotBlank() }?.let(out::add)
            }
            out
        }.orEmpty()
    }.getOrDefault(emptySet())
}
