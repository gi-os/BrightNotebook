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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * A labelled calendar. Days can hold entries from several at once — a work calendar
 * imported from an invite file, the phone's own, and whatever you typed yourself.
 *
 * [kind] is what the label came from, and it decides whether re-importing replaces rows:
 * imported calendars are refreshed wholesale, so an event moved at the source moves here
 * rather than doubling up.
 */
@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey val id: String,
    val label: String,
    val kind: String = KIND_LOCAL,
    /** ICS file name, or the device calendar's provider id. Null for a typed calendar. */
    val sourceRef: String? = null,
    val visible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_ICS = "ics"
        const val KIND_DEVICE = "device"
    }
}

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
    /** Null means the notebook's own calendar, which needs no row to exist. */
    val calendarId: String? = null,
    /** Minutes before the start to be told. Null is no reminder. */
    val reminderMinutes: Int? = null,
    /** The source's own identifier (ICS `UID`, or a device event id), for re-imports. */
    val sourceUid: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Whether this row can be edited here, or belongs to whatever it was imported from. */
    val isImported: Boolean get() = sourceUid != null
}

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

    /* ---- calendars ---- */

    @Query("SELECT * FROM calendars ORDER BY label COLLATE NOCASE ASC")
    fun observeCalendars(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars ORDER BY label COLLATE NOCASE ASC")
    suspend fun calendars(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE sourceRef = :sourceRef AND kind = :kind LIMIT 1")
    suspend fun calendarBySource(kind: String, sourceRef: String): CalendarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCalendar(calendar: CalendarEntity)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun deleteCalendar(id: String)

    /** Deleting an imported calendar takes its events with it — they were never yours. */
    @Query("DELETE FROM day_entries WHERE calendarId = :id")
    suspend fun deleteEntriesOf(id: String)

    @Query("SELECT COUNT(*) FROM day_entries WHERE calendarId = :id")
    suspend fun countEntriesOf(id: String): Int

    /* ---- calendar entries ---- */

    // Every read below carries the same visibility clause: a hidden calendar disappears
    // from the whole app, rather than each screen remembering to filter it out. Room has
    // no way to share a SQL fragment, so it is repeated by hand.

    @Query(
        "SELECT * FROM day_entries WHERE epochDay = :epochDay AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "ORDER BY startMinutes IS NULL DESC, startMinutes ASC, createdAt ASC",
    )
    fun observeDay(epochDay: Long): Flow<List<DayEntryEntity>>

    @Query(
        "SELECT epochDay, COUNT(*) AS entries FROM day_entries " +
            "WHERE epochDay BETWEEN :from AND :to AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "GROUP BY epochDay",
    )
    fun observeDayCounts(from: Long, to: Long): Flow<List<DayCount>>

    @Query(
        "SELECT * FROM day_entries WHERE epochDay >= :from AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "ORDER BY epochDay ASC, startMinutes IS NULL DESC, startMinutes ASC LIMIT :limit",
    )
    fun observeUpcoming(from: Long, limit: Int): Flow<List<DayEntryEntity>>

    /** Everything still to come that asked to be reminded — used to re-arm alarms. */
    @Query(
        "SELECT * FROM day_entries WHERE reminderMinutes IS NOT NULL AND epochDay >= :from AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1))",
    )
    suspend fun entriesWithReminders(from: Long): List<DayEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntry(entry: DayEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntries(entries: List<DayEntryEntity>)

    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    suspend fun getDayEntry(id: String): DayEntryEntity?

    /**
     * Blocking pair of the two reads above, for [com.gios.lightnotebook.notify]: a
     * broadcast receiver runs on a plain thread with no coroutine scope to suspend in.
     */
    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    fun getDayEntryBlocking(id: String): DayEntryEntity?

    @Query(
        "SELECT * FROM day_entries WHERE reminderMinutes IS NOT NULL AND epochDay >= :from AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1))",
    )
    fun entriesWithRemindersBlocking(from: Long): List<DayEntryEntity>

    @Query("DELETE FROM day_entries WHERE id = :id")
    suspend fun deleteDayEntry(id: String)
}

/**
 * Version 2 added labelled calendars, reminders and import provenance.
 *
 * Written as a real migration rather than left to `fallbackToDestructiveMigration`: that
 * fallback is fine for a database nobody has yet, and this one already holds somebody's
 * notes. The columns are added exactly as Room would declare them — nullable, no SQL
 * default — because Room validates the schema on open and a mismatch only shows up as a
 * crash on the phone.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendars` (" +
                "`id` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`sourceRef` TEXT, " +
                "`visible` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `calendarId` TEXT")
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `reminderMinutes` INTEGER")
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `sourceUid` TEXT")
    }
}

@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        DayEntryEntity::class,
        CalendarEntity::class,
    ],
    version = 2,
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
            )
                .addMigrations(MIGRATION_1_2)
                // Upgrades migrate; only a downgrade — installing an older APK over a
                // newer database — starts over, and that is a choice the user made.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
