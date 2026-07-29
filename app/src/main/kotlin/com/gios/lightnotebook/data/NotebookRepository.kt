package com.gios.lightnotebook.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

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
    ): String {
        val id = UUID.randomUUID().toString()
        dao.putDayEntry(
            DayEntryEntity(
                id = id,
                epochDay = epochDay,
                text = text.trim(),
                startMinutes = startMinutes,
                endMinutes = endMinutes,
                fromPhoto = fromPhoto,
                systemEventId = systemEventId,
            ),
        )
        return id
    }

    suspend fun updateDayEntry(entry: DayEntryEntity) =
        dao.putDayEntry(entry.copy(updatedAt = System.currentTimeMillis()))

    suspend fun getDayEntry(id: String): DayEntryEntity? = dao.getDayEntry(id)

    suspend fun deleteDayEntry(id: String) = dao.deleteDayEntry(id)

    private companion object {
        const val PREFS = "lightnotebook"
        const val KEY_API = "anthropic_key"
        const val KEY_MIRROR = "mirror_system_calendar"
    }
}
