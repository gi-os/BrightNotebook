package com.gios.lightnotebook.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.gios.lightnotebook.util.JournalDay
import java.time.ZoneId

/**
 * Light's own files, read where Light leaves them.
 *
 * LightOS's notes tool and its voice notes write into `Documents/` — `Notes`, `AudioNotes`,
 * `MessageAudio`, and a `Temp` twin of each while something is still being written. That is the
 * only record of a note taken in Light's own app: there is no provider, no database this app can
 * ask, and no intent to fetch one. So a day that had three voice notes on it looked, from here,
 * like a day nothing happened on.
 *
 * ### Why a folder grant and not a storage permission
 *
 * `Documents/` is not a media directory, so `READ_MEDIA_*` does not reach it and `MediaStore` has
 * nothing to say about most of what is in there. The alternatives are `MANAGE_EXTERNAL_STORAGE` —
 * every file on the phone, granted from a Settings screen this phone does not have — or the
 * document tree: the user picks `Documents` once, the grant persists across reboots, and this app
 * can read exactly that folder and nothing else. The narrow one is also the only one that works.
 *
 * ### Read every time, cache nothing
 *
 * Same reasoning as the tape library in BrightRecorder: a directory listing cannot disagree with
 * itself, and an index of somebody else's files is a second copy of a truth this app does not own.
 * A note recorded a minute ago is on the day the moment you look at it, and a note deleted in
 * Light's app is gone from here for free.
 *
 * ### What it does not do
 *
 * It does not read the contents. A voice note is a row saying a voice note happened, at a time,
 * with the file's own name; opening it hands the file to whatever plays it. Light's text notes are
 * left as rows too rather than imported — copying them in would make this app a second owner of
 * notes that are still being edited somewhere else, and two owners of one note is the bug every
 * sync in this collection is written to avoid.
 */
object LightDocs {

    /** One of Light's files, as a thing that happened at a time. */
    data class Doc(
        val name: String,
        val atMs: Long,
        val kind: Kind,
        val uri: Uri,
    )

    /** What sort of file it is, decided by extension because that is all there is to go on. */
    enum class Kind { Voice, Note, Other }

    /**
     * The folders worth walking, in the order Light created them.
     *
     * Named rather than discovered so this app is not listing somebody's whole Documents folder to
     * find three files. `Temp*` are included: a note still being written is a note that exists, and
     * the temporary copy is the only evidence of it until Light renames it.
     */
    private val FOLDERS = listOf(
        "AudioNotes",
        "TempAudioNotes",
        "Notes",
        "TempNotes",
        "MessageAudio",
        "TempMessageAudio",
    )

    /** The intent that asks for the folder. Started from the settings screen. */
    fun pickFolder(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    /**
     * Hold on to the grant.
     *
     * `takePersistableUriPermission` is the difference between a folder this app can read today and
     * one it can read after a reboot. Without it the feature works until the process dies, which is
     * the worst possible failure: it looks like it worked.
     */
    fun remember(context: Context, tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Whether the grant is still held. Asked of the system, not of a preference. */
    fun granted(context: Context, tree: Uri?): Boolean {
        val uri = tree ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        }.getOrDefault(false)
    }

    /**
     * Light's files from one journal day, newest last.
     *
     * The window is the journal day — four in the morning to four in the morning — like every other
     * source on the timeline, so a voice note at one in the morning belongs to the night before
     * rather than to the day that has not started yet.
     *
     * Times come from the file's own `lastModified`. Light's filenames carry a stamp of their own,
     * but the format is Light's to change and a filename this app misparses is a note filed under
     * the wrong day with no way to tell — the filesystem's own answer cannot be misread.
     */
    fun docs(
        context: Context,
        tree: Uri?,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Doc> {
        val root = tree?.takeIf { granted(context, it) } ?: return emptyList()
        val window = JournalDay.windowMs(epochDay, zone)
        val out = ArrayList<Doc>()
        for (folder in FOLDERS) {
            childrenOf(context, root, folder).forEach { doc ->
                if (doc.atMs in window) out.add(doc)
            }
        }
        return out.sortedBy { it.atMs }.distinctBy { it.uri }
    }

    /**
     * One folder's files, or nothing at all.
     *
     * Two queries per folder, which is the cost of the document tree: one to find the folder's own
     * document id inside the root, and one to list what is in it. Six folders is twelve cursors on
     * opening a day — a few milliseconds, and only when the grant exists.
     */
    private fun childrenOf(context: Context, root: Uri, folder: String): List<Doc> = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(root, rootId)
        var folderId: String? = null
        context.contentResolver.query(
            rootChildren,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val mime = cursor.getString(2)
                if (name == folder && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folderId = cursor.getString(0)
                    break
                }
            }
        }
        val id = folderId ?: return@runCatching emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
        val docs = ArrayList<Doc>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1).orEmpty()
                val modified = cursor.getLong(3)
                if (modified <= 0L) continue
                docs.add(
                    Doc(
                        name = name,
                        atMs = modified,
                        kind = kindOf(name, mime),
                        uri = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)),
                    ),
                )
            }
        }
        docs
    }.getOrDefault(emptyList())

    /**
     * Voice, text, or neither.
     *
     * The mime type first, the extension second: a document provider is entitled to answer
     * `application/octet-stream` for a perfectly ordinary `.m4a`, and on a phone whose files this
     * app did not write, the name is often the more honest of the two.
     */
    private fun kindOf(name: String, mime: String): Kind {
        val lower = name.lowercase()
        return when {
            mime.startsWith("audio/") -> Kind.Voice
            AUDIO.any { lower.endsWith(it) } -> Kind.Voice
            mime.startsWith("text/") -> Kind.Note
            TEXT.any { lower.endsWith(it) } -> Kind.Note
            else -> Kind.Other
        }
    }

    /** Open it in whatever handles it, with a read grant riding on the intent. */
    fun open(context: Context, doc: Doc): Boolean = runCatching {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc.uri, mimeFor(doc))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
        true
    }.getOrDefault(false)

    private fun mimeFor(doc: Doc): String = when (doc.kind) {
        Kind.Voice -> "audio/*"
        Kind.Note -> "text/plain"
        Kind.Other -> "*/*"
    }

    private val AUDIO = listOf(".m4a", ".mp3", ".aac", ".wav", ".ogg", ".opus", ".3gp", ".amr")
    private val TEXT = listOf(".txt", ".md", ".json", ".rtf")
}
