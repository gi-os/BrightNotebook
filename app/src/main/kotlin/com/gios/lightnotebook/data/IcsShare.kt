package com.gios.lightnotebook.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gios.lightnotebook.util.IcsWriter
import java.io.File

/**
 * Sending an entry out as a calendar invite.
 *
 * The whole write path to Google Calendar and Outlook, and it is one file. Both of them — and
 * Apple, and Fastmail, and every corporate mail client — offer to add a `.ics` the moment they see
 * one, which means this app can put an event into any of them without holding an account, a token,
 * or write access to anything.
 *
 * The file is written into `filesDir/shared/`, which is the only directory the `FileProvider` will
 * hand out beyond `captures/`. Deliberately its own directory rather than a corner of the existing
 * one: a provider path is a hole in this app's private storage and the notebook's database lives in
 * the same place, so the holes stay narrow and named.
 *
 * Old files are swept on the way in. Nothing else deletes them — a share is fire-and-forget and
 * this process is not told what the other app did — so the tidying happens the next time somebody
 * shares something, which is the only moment anything here is known to be running.
 */
object IcsShare {

    /**
     * Write the entry out and hand it to whatever can take it. False when the file could not be
     * written, which is the only failure worth reporting: what happens after the chooser is up
     * belongs to whoever the user picked.
     */
    fun send(context: Context, entry: DayEntryEntity): Boolean = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        sweep(dir)
        // Named after the event, not after its id: the filename is the first thing the receiving
        // client shows, and `a3f9c1e2-….ics` in a mail attachment tells nobody anything.
        val file = File(dir, safeName(entry.text) + EXTENSION)
        file.writeText(IcsWriter.calendar(entry))
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entry.text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A chooser rather than a default: which app an invite leaves by is a decision about who
        // is receiving it, and it changes every time.
        context.startActivity(
            Intent.createChooser(send, "Send invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** Anything older than a day. A shared file has been read within seconds or never. */
    private fun sweep(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    /**
     * A filename from an event's own words.
     *
     * Everything outside letters, digits, space, dash and underscore goes: a title with a slash in
     * it — "Standup / retro" — is a perfectly ordinary thing to write and a path separator in the
     * only place this string is used.
     */
    private fun safeName(title: String): String = title
        .map { if (it.isLetterOrDigit() || it in " -_") it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(60)
        .ifBlank { "event" }

    private const val DIR = "shared"
    private const val EXTENSION = ".ics"
    private const val MIME = "text/calendar"
    private const val AUTHORITY_SUFFIX = ".captures"
    private const val KEEP_MS = 24 * 60 * 60 * 1000L
}
