package com.gios.lightnotebook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gios.lightnotebook.util.ImportedEvent
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/** What an import produced: the calendar it landed in, and the rows written. */
data class ImportResult(
    val calendar: CalendarEntity,
    val entries: List<DayEntryEntity>,
    val replaced: Boolean,
)

/**
 * Thin wrapper over Room plus the two bits of local state that aren't rows: the
 * Anthropic key and the folder photos are captured into.
 */
class NotebookRepository(private val context: Context) {

    private val dao = NotebookDatabase.get(context).dao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ---- key ---- */

    fun getApiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
    }

    fun setMirrorToSystemCalendar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR, enabled).apply()
    }

    fun mirrorToSystemCalendar(): Boolean = prefs.getBoolean(KEY_MIRROR, true)

    /**
     * Lead time given to a new timed entry, in minutes. Null is "don't remind me", stored
     * as -1 because SharedPreferences has no absent-but-set state for an Int.
     */
    fun defaultReminderMinutes(): Int? =
        prefs.getInt(KEY_LEAD, DEFAULT_LEAD).takeIf { it >= 0 }

    fun setDefaultReminderMinutes(minutes: Int?) {
        prefs.edit().putInt(KEY_LEAD, minutes ?: -1).apply()
    }

    /* ---- files handed in by the document picker ---- */

    /** Reads a picked .ics file. Capped: an .ics is text, and a huge one is a mistake. */
    fun readText(uri: Uri, maxBytes: Int = 4 * 1024 * 1024): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            String(stream.readNBytes(maxBytes), Charsets.UTF_8)
        }
    }.getOrNull()

    /** The file's own name, for the calendar label and for re-import matching. */
    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val name = fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.removeSuffix(".ics")?.takeIf { it.isNotBlank() } ?: "Imported"
    }

    /* ---- capture files ---- */

    /** Captures live in cache: once the model has read the page the pixels are litter. */
    fun newCaptureFile(): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "capture-${System.currentTimeMillis()}.jpg")
    }

    fun clearCaptures() {
        File(context.cacheDir, "captures").listFiles()?.forEach { it.delete() }
    }

    /* ---- notes ---- */

    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes()

    fun observeNotesIn(folderId: String): Flow<List<NoteEntity>> = dao.observeNotesIn(folderId)

    fun observeNote(id: String): Flow<NoteEntity?> = dao.observeNote(id)

    suspend fun getNote(id: String): NoteEntity? = dao.getNote(id)

    /** Creates an empty note and hands back its id so the caller can open the editor. */
    suspend fun createNote(
        title: String = "",
        body: String = "",
        folderId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.putNote(
            NoteEntity(
                id = id,
                title = title,
                body = body,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    suspend fun saveNote(note: NoteEntity) =
        dao.putNote(note.copy(updatedAt = System.currentTimeMillis()))

    /** Appends photographed text under whatever is already in the note. */
    suspend fun appendToNote(id: String, text: String): Boolean {
        val note = dao.getNote(id) ?: return false
        val joined = if (note.body.isBlank()) text else note.body.trimEnd() + "\n\n" + text
        dao.putNote(note.copy(body = joined, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deleteNote(id: String) = dao.deleteNote(id)

    suspend fun setPinned(id: String, pinned: Boolean) =
        dao.setPinned(id, pinned, System.currentTimeMillis())

    suspend fun setFolder(id: String, folderId: String?) =
        dao.setFolder(id, folderId, System.currentTimeMillis())

    /* ---- folders ---- */

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    suspend fun createFolder(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.putFolder(FolderEntity(id = id, name = name.trim()))
        return id
    }

    suspend fun renameFolder(folder: FolderEntity, name: String) =
        dao.putFolder(folder.copy(name = name.trim()))

    /** The folder goes; its notes come back to All Notes. */
    suspend fun deleteFolder(id: String) {
        dao.orphanNotesOf(id)
        dao.deleteFolder(id)
    }

    /* ---- calendar ---- */

    fun observeDay(epochDay: Long): Flow<List<DayEntryEntity>> = dao.observeDay(epochDay)

    fun observeDayCounts(from: Long, to: Long): Flow<List<DayCount>> =
        dao.observeDayCounts(from, to)

    fun observeUpcoming(from: Long, limit: Int = 30): Flow<List<DayEntryEntity>> =
        dao.observeUpcoming(from, limit)

    suspend fun addDayEntry(
        epochDay: Long,
        text: String,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        fromPhoto: Boolean = false,
        systemEventId: Long? = null,
        reminderMinutes: Int? = null,
    ): DayEntryEntity {
        val entry = DayEntryEntity(
            id = UUID.randomUUID().toString(),
            epochDay = epochDay,
            text = text.trim(),
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            fromPhoto = fromPhoto,
            systemEventId = systemEventId,
            // A reminder on something with no time has nothing to count back from.
            reminderMinutes = reminderMinutes?.takeIf { startMinutes != null },
        )
        dao.putDayEntry(entry)
        return entry
    }

    suspend fun updateDayEntry(entry: DayEntryEntity): DayEntryEntity {
        val updated = entry.copy(updatedAt = System.currentTimeMillis())
        dao.putDayEntry(updated)
        return updated
    }

    suspend fun getDayEntry(id: String): DayEntryEntity? = dao.getDayEntry(id)

    suspend fun deleteDayEntry(id: String) = dao.deleteDayEntry(id)

    suspend fun entriesWithReminders(from: Long): List<DayEntryEntity> =
        dao.entriesWithReminders(from)

    /* ---- calendars ---- */

    fun observeCalendars(): Flow<List<CalendarEntity>> = dao.observeCalendars()

    suspend fun calendars(): List<CalendarEntity> = dao.calendars()

    suspend fun countEntriesOf(calendarId: String): Int = dao.countEntriesOf(calendarId)

    suspend fun setCalendarVisible(calendar: CalendarEntity, visible: Boolean) =
        dao.putCalendar(calendar.copy(visible = visible))

    suspend fun renameCalendar(calendar: CalendarEntity, label: String) =
        dao.putCalendar(calendar.copy(label = label.trim()))

    /** Removing a calendar removes what came with it; those rows were never the user's. */
    suspend fun deleteCalendar(id: String) {
        dao.deleteEntriesOf(id)
        dao.deleteCalendar(id)
    }

    /**
     * Writes an imported batch under one label.
     *
     * Re-importing the same source **replaces** its events rather than adding to them, so
     * an event moved at the source moves here instead of appearing twice. That is the whole
     * reason `sourceUid` and `sourceRef` are stored.
     */
    suspend fun importEvents(
        label: String,
        kind: String,
        sourceRef: String,
        events: List<ImportedEvent>,
        reminderMinutes: Int?,
    ): ImportResult {
        val existing = dao.calendarBySource(kind, sourceRef)
        val calendar = existing?.copy(label = label.trim())
            ?: CalendarEntity(
                id = UUID.randomUUID().toString(),
                label = label.trim(),
                kind = kind,
                sourceRef = sourceRef,
            )
        dao.putCalendar(calendar)
        if (existing != null) dao.deleteEntriesOf(calendar.id)

        val now = System.currentTimeMillis()
        val rows = events.map { event ->
            DayEntryEntity(
                id = UUID.randomUUID().toString(),
                epochDay = event.epochDay,
                text = event.title,
                startMinutes = event.startMinutes,
                endMinutes = event.endMinutes,
                calendarId = calendar.id,
                reminderMinutes = reminderMinutes?.takeIf { event.startMinutes != null },
                sourceUid = event.uid,
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.putDayEntries(rows)
        return ImportResult(calendar = calendar, entries = rows, replaced = existing != null)
    }

    private companion object {
        const val PREFS = "lightnotebook"
        const val KEY_API = "anthropic_key"
        const val KEY_MIRROR = "mirror_system_calendar"
        const val KEY_LEAD = "default_reminder_minutes"
        const val DEFAULT_LEAD = 10
    }
}
