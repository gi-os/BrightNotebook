package com.gios.lightnotebook.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A folder is just a label notes point at, so deleting one never deletes a note. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Body text carries its own markers (`**bold**`, `- `, `1. `) — see
 * [com.gios.lightnotebook.util.NoteMarkdown]. Storing text rather than spans means a
 * note survives any future export intact.
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
    val folderId: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One line of text on one calendar square. Dates are epoch days, not instants: an
 * entry belongs to a box in the grid, and a timezone could only move it out of that box.
 * [systemEventId] is set when the entry was also written to the phone's own calendar.
 */
@Entity(tableName = "day_entries")
data class DayEntryEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    val text: String,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    val fromPhoto: Boolean = false,
    val systemEventId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** How many entries a day holds — enough to mark the month grid without loading it all. */
data class DayCount(val epochDay: Long, val entries: Int)

@Dao
interface NotebookDao {

    /* ---- notes ---- */

    // Pinned first, then most recently touched: the two orders that matter, in one index.
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY pinned DESC, updatedAt DESC")
    fun observeNotesIn(folderId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observeNote(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    // No default arguments anywhere in this DAO: Room generates the implementations, and
    // the clock belongs to the caller anyway.
    @Query("UPDATE notes SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?, now: Long)

    @Query("SELECT COUNT(*) FROM notes WHERE folderId = :folderId")
    fun observeNoteCount(folderId: String): Flow<Int>

    /* ---- folders ---- */

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    /** Deleting a folder must not take its notes with it — they fall back to All Notes. */
    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :id")
    suspend fun orphanNotesOf(id: String)

    /* ---- calendar ---- */

    @Query(
        "SELECT * FROM day_entries WHERE epochDay = :epochDay " +
            "ORDER BY startMinutes IS NULL DESC, startMinutes ASC, createdAt ASC",
    )
    fun observeDay(epochDay: Long): Flow<List<DayEntryEntity>>

    @Query(
        "SELECT epochDay, COUNT(*) AS entries FROM day_entries " +
            "WHERE epochDay BETWEEN :from AND :to GROUP BY epochDay",
    )
    fun observeDayCounts(from: Long, to: Long): Flow<List<DayCount>>

    @Query(
        "SELECT * FROM day_entries WHERE epochDay >= :from " +
            "ORDER BY epochDay ASC, startMinutes IS NULL DESC, startMinutes ASC LIMIT :limit",
    )
    fun observeUpcoming(from: Long, limit: Int): Flow<List<DayEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntry(entry: DayEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntries(entries: List<DayEntryEntity>)

    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    suspend fun getDayEntry(id: String): DayEntryEntity?

    @Query("DELETE FROM day_entries WHERE id = :id")
    suspend fun deleteDayEntry(id: String)
}

@Database(
    entities = [NoteEntity::class, FolderEntity::class, DayEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun dao(): NotebookDao

    companion object {
        @Volatile
        private var instance: NotebookDatabase? = null

        fun get(context: Context): NotebookDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotebookDatabase::class.java,
                "lightnotebook.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
