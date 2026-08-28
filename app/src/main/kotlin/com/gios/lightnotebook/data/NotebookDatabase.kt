package com.gios.lightnotebook.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
@Entity(
    tableName = "notes",
    // Unique so two taps arriving at once cannot leave a conversation with two notes.
    // SQLite allows any number of NULLs in a unique index, so every ordinary note is
    // unaffected.
    indices = [Index(value = ["externalKey"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val body: String = "",
    val folderId: String? = null,
    val pinned: Boolean = false,
    /** The photograph this was transcribed from, kept so the original can be read back. */
    val imagePath: String? = null,
    /**
     * The note another app owns this note *for*, or null for a note made here.
     *
     * LightChat's contact page keeps one note per conversation and asks for it by the
     * conversation's normalised handles (`+12125550148`), never by a chat guid — a guid is
     * local to one Mac's `chat.db`, so a restore or a new Mac would strand every note. The
     * key is opaque here: this app only has to find the same row again.
     *
     * Handles are not free of that problem either, only much better at it: adding or
     * removing somebody from a group changes the key, and the group's old note stays in the
     * notes list but stops being reachable from LightChat. A 1:1 — which is what the note
     * is mostly for — never changes.
     */
    val externalKey: String? = null,
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
    /**
     * ICS file uri, feed URL, or the device calendar's provider id. Null for a typed
     * calendar.
     */
    val sourceRef: String? = null,
    val visible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_ICS = "ics"
        const val KIND_DEVICE = "device"

        /**
         * A feed fetched over HTTP every refresh — how a work calendar gets here, since a
         * server can hold the corporate account and publish an .ics the phone just GETs.
         *
         * No schema change was needed to add it: `kind` is a TEXT column, so a new kind is
         * a new constant and nothing else.
         */
        const val KIND_URL = "url"
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
    /**
     * The last day this covers, when it spans more than one.
     *
     * Null for the overwhelming majority — a thing that happens on a day. A trip, a holiday or a
     * conference is one entry that is true of several days rather than several copies of an entry,
     * which is why this is a column and not a row per day: re-dating it, renaming it or deleting it
     * has to be one act, and a week's holiday as seven rows means seven of everything.
     *
     * Always greater than [epochDay] when set; the repository normalises a backwards range rather
     * than trusting it.
     */
    val endEpochDay: Long? = null,
    val fromPhoto: Boolean = false,
    val systemEventId: Long? = null,
    /** Null means the notebook's own calendar, which needs no row to exist. */
    val calendarId: String? = null,
    /** Minutes before the start to be told. Null is no reminder. */
    val reminderMinutes: Int? = null,
    /** The source's own identifier (ICS `UID`, or a device event id), for re-imports. */
    val sourceUid: String? = null,
    /**
     * The RFC 5545 `RRULE` this entry repeats on, or null for a thing that happens once.
     *
     * Stored as the rule's own text rather than as parsed columns, and **not** expanded into
     * rows. One row is one series: a daily meeting imported from a decade-long feed is a single
     * entry here, and the days it lands on are worked out by
     * [com.gios.lightnotebook.util.Recurrence] for whatever window is being drawn. Materialising
     * instances would mean thousands of rows, a re-import that has to reconcile them, and a
     * reminder table to match.
     *
     * [epochDay] and [startMinutes] stay what they always were: the *first* occurrence. Every
     * query in this file still finds the row on that day, so nothing that predates recurrence
     * had to change.
     */
    val rrule: String? = null,
    /**
     * Occurrences that were taken out of the series — an `EXDATE`, as epoch days separated by
     * commas. Written by "delete just this one", and by a feed's own EXDATE lines.
     */
    val exDays: String? = null,
    /** The photograph this was read off, so a transcription can be checked against it. */
    val imagePath: String? = null,
    /**
     * Where it is, as words. Null for the overwhelming majority of entries.
     *
     * Whatever the calendar said, unparsed: `EVENT_LOCATION` and an ICS `LOCATION` are free text
     * and arrive as anything from "Regal Union Square" to a full postal address to "moms". Storing
     * it as it came is the only honest option — and it is enough, because the thing that has to
     * understand it is a maps search, which is built for exactly that kind of string.
     */
    val location: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Whether this row can be edited here, or belongs to whatever it was imported from. */
    val isImported: Boolean get() = sourceUid != null

    /** Whether this covers more than the day it starts on. */
    val isMultiDay: Boolean get() = (endEpochDay ?: epochDay) > epochDay

    /** The last day covered, which is the first when it is not a span. */
    val lastDay: Long get() = endEpochDay ?: epochDay

    /** Whether this row is a series rather than a single occurrence. */
    val repeats: Boolean get() = !rrule.isNullOrBlank()
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

    /**
     * Notes written or returned to inside a window, for the day's own record of itself.
     *
     * Both columns are tested because a note can belong to a day either way: written on it, or
     * written earlier and come back to on it. Bounds are milliseconds and half-open, computed
     * from a real time zone by the caller — a day is 23 hours some mornings.
     */
    @Query(
        "SELECT * FROM notes WHERE (createdAt >= :fromMs AND createdAt < :toMs) " +
            "OR (updatedAt >= :fromMs AND updatedAt < :toMs) ORDER BY updatedAt ASC",
    )
    fun observeNotesTouched(fromMs: Long, toMs: Long): Flow<List<NoteEntity>>

    /** The earliest day anything was written on, or null on a fresh install. */
    @Query("SELECT MIN(epochDay) FROM day_entries")
    suspend fun earliestEntryDay(): Long?

    @Query("SELECT MIN(createdAt) FROM notes")
    suspend fun earliestNoteAt(): Long?

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observeNote(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: String): NoteEntity?

    /** The note another app owns, by the key it asks for it with. See [NoteEntity.externalKey]. */
    @Query("SELECT * FROM notes WHERE externalKey = :key LIMIT 1")
    suspend fun getNoteByExternalKey(key: String): NoteEntity?

    // Used before deleting a capture: a photo may be shared by a note and several events.
    @Query("SELECT COUNT(*) FROM notes WHERE imagePath = :path")
    suspend fun countNotesWithImage(path: String): Int

    @Query("SELECT COUNT(*) FROM day_entries WHERE imagePath = :path")
    suspend fun countEntriesWithImage(path: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: NoteEntity)

    /**
     * Insert that fails on a conflict instead of replacing.
     *
     * [putNote] is REPLACE, which for a unique-index clash *deletes the row already there*
     * — so two simultaneous first taps on the same conversation would leave the second
     * one's note and silently drop the first. Only the externally-keyed create uses this.
     *
     * ABORT rather than IGNORE, and that matters: IGNORE returns normally without writing,
     * so the caller would hand back the id of a row that does not exist and the editor
     * would open on nothing. ABORT throws `SQLiteConstraintException`, which is what the
     * caller catches to go and find the row that won.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: NoteEntity)

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

    /**
     * The rows of one calendar that hold an alarm, so a re-import can take those alarms down
     * before it deletes the rows they belong to. See [NotebookRepository.importEvents].
     */
    @Query("SELECT * FROM day_entries WHERE calendarId = :id AND reminderMinutes IS NOT NULL")
    suspend fun entriesWithRemindersOf(id: String): List<DayEntryEntity>

    @Query("SELECT COUNT(*) FROM day_entries WHERE calendarId = :id")
    suspend fun countEntriesOf(id: String): Int

    /* ---- calendar entries ---- */

    // Every read below carries the same visibility clause: a hidden calendar disappears
    // from the whole app, rather than each screen remembering to filter it out. Room has
    // no way to share a SQL fragment, so it is repeated by hand.

    @Query(
        // A span that began days ago still belongs to this day, so the test is containment
        // rather than equality. COALESCE because the column is null for the ordinary case.
        "SELECT * FROM day_entries WHERE " +
            ":epochDay BETWEEN epochDay AND COALESCE(endEpochDay, epochDay) AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "ORDER BY startMinutes IS NULL DESC, startMinutes ASC, createdAt ASC",
    )
    fun observeDay(epochDay: Long): Flow<List<DayEntryEntity>>

    @Query(
        "SELECT epochDay, COUNT(*) AS entries FROM day_entries " +
            "WHERE epochDay <= :to AND COALESCE(endEpochDay, epochDay) >= :from AND " +
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

    /** Every entry in a span of days, for the zoomable planner's visible window. */
    @Query(
        // Overlap, not containment: a fortnight's trip is visible in a window that shows none of
        // its ends, and asking only for entries whose *start* falls inside would hide it entirely.
        "SELECT * FROM day_entries WHERE " +
            "epochDay <= :to AND COALESCE(endEpochDay, epochDay) >= :from AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "ORDER BY epochDay ASC, startMinutes IS NULL DESC, startMinutes ASC, createdAt ASC",
    )
    fun observeRange(from: Long, to: Long): Flow<List<DayEntryEntity>>

    /**
     * Everything still to come that asked to be reminded — used to re-arm alarms.
     *
     * A repeating entry is kept whatever its start day, because its *first* occurrence is
     * usually in the past and its next one is not. Which day that is comes from the rule, in
     * [com.gios.lightnotebook.notify.Reminders].
     */
    @Query(
        "SELECT * FROM day_entries WHERE reminderMinutes IS NOT NULL AND " +
            "(epochDay >= :from OR (rrule IS NOT NULL AND rrule != '')) AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1))",
    )
    suspend fun entriesWithReminders(from: Long): List<DayEntryEntity>

    /**
     * Every repeating entry, whenever it started.
     *
     * Unwindowed on purpose, and safely so: a series is one row, so this is a handful of rows on
     * any real phone, and the alternative — asking the database which days a rule lands on —
     * would mean teaching SQLite to read an RRULE. The days come from
     * [com.gios.lightnotebook.util.Recurrence] once these rows are in hand, bounded by whatever
     * window the screen is drawing.
     */
    @Query(
        "SELECT * FROM day_entries WHERE rrule IS NOT NULL AND rrule != '' AND " +
            "(calendarId IS NULL OR calendarId IN (SELECT id FROM calendars WHERE visible = 1)) " +
            "ORDER BY startMinutes IS NULL DESC, startMinutes ASC, createdAt ASC",
    )
    fun observeRecurring(): Flow<List<DayEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntry(entry: DayEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDayEntries(entries: List<DayEntryEntity>)

    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    suspend fun getDayEntry(id: String): DayEntryEntity?

    /**
     * The same row, watched. For the event editor, which writes a field and has to redraw from
     * what was actually stored rather than from what it hoped it stored.
     */
    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    fun dayEntryFlow(id: String): Flow<DayEntryEntity?>

    /**
     * Blocking pair of the two reads above, for [com.gios.lightnotebook.notify]: a
     * broadcast receiver runs on a plain thread with no coroutine scope to suspend in.
     */
    @Query("SELECT * FROM day_entries WHERE id = :id LIMIT 1")
    fun getDayEntryBlocking(id: String): DayEntryEntity?

    @Query(
        "SELECT * FROM day_entries WHERE reminderMinutes IS NOT NULL AND " +
            "(epochDay >= :from OR (rrule IS NOT NULL AND rrule != '')) AND " +
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

/**
 * Version 3 keeps the photograph a transcription came from.
 *
 * The capture used to be deleted the moment Claude had read it, which made a wrong
 * transcription impossible to check. Both notes and day entries can now point at one.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `imagePath` TEXT")
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `imagePath` TEXT")
    }
}

/**
 * Version 4 lets another app own a note.
 *
 * The column and its unique index are created exactly as Room declares them, because Room
 * validates the schema — including indices — when it opens the database, and a mismatch
 * only shows up as a crash on the phone. The index name is Room's own generated form.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `externalKey` TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_externalKey` ON `notes` (`externalKey`)",
        )
    }
}

/**
 * A day entry gains an end.
 *
 * Nullable rather than defaulted to `epochDay`: null means "one day", which is what almost every row
 * is, and a column full of copies of another column is a column that will eventually disagree with
 * it. Written out exactly as Room declares it, because Room validates the schema on open and a
 * mismatch is a crash on the phone rather than a warning.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `endEpochDay` INTEGER")
    }
}

/**
 * Version 6: an entry can repeat.
 *
 * A real migration and not `fallbackToDestructiveMigration`, which this database has never used
 * for an upgrade and should not start using now: destroying somebody's notes to add a repeat
 * field is not an upgrade, it is a data loss with a changelog entry. Two nullable TEXT columns,
 * declared exactly as Room declares them, because Room validates the schema when it opens the
 * file and a mismatch is a crash on the phone rather than a build failure.
 *
 * Nothing needs backfilling: null means "happens once", which is what every existing row is.
 */
/** Where an entry is, as words. See [DayEntryEntity.location]. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `location` TEXT")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `rrule` TEXT")
        db.execSQL("ALTER TABLE `day_entries` ADD COLUMN `exDays` TEXT")
    }
}

@Database(
    entities = [
        NoteEntity::class,
        FolderEntity::class,
        DayEntryEntity::class,
        CalendarEntity::class,
    ],
    version = 7,
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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                // Upgrades migrate; only a downgrade — installing an older APK over a
                // newer database — starts over, and that is a choice the user made.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { instance = it }
        }
    }
}
