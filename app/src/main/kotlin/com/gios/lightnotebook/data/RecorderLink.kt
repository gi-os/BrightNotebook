package com.gios.lightnotebook.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opening BrightRecorder at the clip a day is showing.
 *
 * The day already knows what you recorded and when — [DayBridges.recordings] reads it out of the
 * recorder's own provider. Every row was a dead end though: a fact about half past two with no way
 * back to the thing itself, which is the one thing you would want from it. This is the way back.
 *
 * The link is `brightrecorder://clip`, with the tape directory and the file name the provider
 * handed over in the first place, and the recorder cues the tape at that clip without playing it.
 * `ClipLink` at the other end is the matching half, including the checks on the two names.
 *
 * **Aimed at one package, with the launcher as the fallback.** An older recorder has no filter for
 * this scheme, and an unresolvable explicit intent throws rather than doing nothing visible — so a
 * phone that has not updated the recorder yet gets the app opened on its current tape, which is
 * wrong but is not a tap that appears to be broken. A phone with no recorder at all does nothing:
 * there is nothing to open, and the row it came from could not have been drawn.
 */
object RecorderLink {

    const val RECORDER = "com.gios.brightrecorder"

    /** True if the tap did something. */
    fun openClip(context: Context, tapeDir: String, fileName: String): Boolean {
        if (tapeDir.isBlank() || fileName.isBlank()) return openApp(context)
        val uri = Uri.Builder()
            .scheme("brightrecorder")
            .authority("clip")
            .appendQueryParameter("tape", tapeDir)
            .appendQueryParameter("file", fileName)
            .build()
        val deepLink = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(RECORDER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Resolved before it is fired: this is the check that turns "no filter for this scheme" into
        // the fallback below rather than into an ActivityNotFoundException on a tap.
        val handled = context.packageManager.resolveActivity(deepLink, 0) != null
        if (!handled) return openApp(context)
        return runCatching { context.startActivity(deepLink); true }.getOrElse { openApp(context) }
    }

    /** The recorder, wherever it happens to be. Used when the clip itself cannot be asked for. */
    private fun openApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(RECORDER) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }
}
