package com.gios.lightnotebook.backup

import android.content.Context
import com.gios.lightnotebook.data.NotebookDatabase
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup as one file, saved and loaded by hand (light-reports#42).
 *
 * [Backup] next door is the nightly half: LightSync carries the same stores onto a home server,
 * silently, for the person who runs one. This is the other half — the person whose "server" is a
 * laptop and a USB cable, whose Light desktop tool stopped working, and who still deserves to
 * walk away with their journal in their hand. Same store list as LightSync's, deliberately: the
 * two halves must never disagree about what "everything" means.
 *
 * The file is a plain zip of the app's own files — the SQLite database, the two preference
 * files, the capture photographs, the charging log — plus a manifest naming the app and format.
 * Not an invented row-by-row format: a database is one file, and exporting rows would mean
 * owning a schema forever (the same reasoning as [Backup]). The zip opens anywhere, so even
 * with this app gone the notes are a `sqlite3` command away.
 *
 * **Restore replaces.** It is a restore, not a merge — half of each is a promise nobody can
 * keep across primary keys. Two guards make that safe to offer: nothing is touched until the
 * whole file has been unpacked and validated in staging, and the data being replaced is first
 * written to a safety zip in the app's own files, so even a restore of last month's backup
 * over this morning's writing is one more restore away from undone.
 */
object BackupFile {

    private const val MANIFEST = "manifest.json"
    private const val DB = "databases/lightnotebook.db"

    /** Where the pre-restore safety copy lands. Overwritten by the next restore, never synced. */
    fun safetyFile(context: Context): File = File(context.filesDir, "pre-restore-backup.zip")

    /**
     * Write everything to [out].
     *
     * The WAL is checkpointed first so the `.db` file alone is the entire database — without
     * that, recent writes live only in `-wal` and a copy of the main file is silently missing
     * the newest notes, which is the worst possible property for a backup to have.
     */
    fun export(context: Context, out: OutputStream) {
        val db = NotebookDatabase.get(context)
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                it.moveToFirst()
            }
        }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject()
                    .put("app", "brightnotebook")
                    .put("format", 1)
                    .put("exported", System.currentTimeMillis())
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            val data = context.dataDir
            file(zip, context.getDatabasePath("lightnotebook.db"), DB)
            file(zip, File(data, "shared_prefs/lightnotebook.xml"), "shared_prefs/lightnotebook.xml")
            file(zip, File(data, "shared_prefs/lightnotebook_steps.xml"), "shared_prefs/lightnotebook_steps.xml")
            tree(zip, File(context.filesDir, "captures"), "files/captures")
            tree(zip, File(context.filesDir, "charge"), "files/charge")
        }
    }

    /**
     * Unpack, validate, then replace. Returns null on success — after which the process **must
     * exit** without touching the database again — or a one-line reason the file was refused,
     * with nothing changed.
     *
     * The order is the safety argument. Everything lands in a staging directory and is checked
     * there; a truncated zip, someone else's zip, or a zip with no database inside all return
     * before a single live file has moved. Only then is the current data written to
     * [safetyFile], the Room handle closed, and the swap done — and from that moment the only
     * correct next step is `exitProcess`, because the closed database and the in-memory
     * preferences are both lies now. The caller owns the exit so it can say goodbye first.
     */
    fun restore(context: Context, input: InputStream): String? {
        val staging = File(context.cacheDir, "restore-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var manifestOk = false
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == MANIFEST) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val root = runCatching { JSONObject(text) }.getOrNull()
                            ?: return "the manifest is not readable"
                        if (root.optString("app") != "brightnotebook") {
                            return "not a Notebook backup file"
                        }
                        if (root.optInt("format") > 1) {
                            return "made by a newer Notebook — update first"
                        }
                        manifestOk = true
                        continue
                    }
                    val dest = File(staging, entry.name)
                    // The classic zip-slip: an entry named `../` walks out of staging and a
                    // malicious file overwrites whatever it likes. Canonical paths or nothing.
                    if (!dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        return "refused a path that escapes the backup"
                    }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { zip.copyTo(it) }
                    }
                }
            }
            if (!manifestOk) return "not a Notebook backup file"
            if (!File(staging, DB).isFile) return "the backup carries no database"

            // Everything validated; the current data gets its safety copy before anything moves.
            runCatching {
                FileOutputStream(safetyFile(context)).use { export(context, it) }
            }

            runCatching { NotebookDatabase.get(context).close() }
            val dbFile = context.getDatabasePath("lightnotebook.db")
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(staging, DB).copyTo(dbFile, overwrite = true)
            for (name in listOf("lightnotebook.xml", "lightnotebook_steps.xml")) {
                val staged = File(staging, "shared_prefs/$name")
                if (staged.isFile) {
                    staged.copyTo(File(context.dataDir, "shared_prefs/$name"), overwrite = true)
                }
            }
            for (dir in listOf("captures", "charge")) {
                val staged = File(staging, "files/$dir")
                val live = File(context.filesDir, dir)
                if (staged.isDirectory) {
                    live.deleteRecursively()
                    staged.copyRecursively(live, overwrite = true)
                }
            }
            return null
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun file(zip: ZipOutputStream, source: File, name: String) {
        if (!source.isFile) return
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(source).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun tree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            file(zip, f, "$prefix/${f.relativeTo(root).path}")
        }
    }
}
