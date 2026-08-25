package com.gios.lightnotebook.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.provider.OpenableColumns
import com.gios.lightnotebook.util.Daylight
import java.time.ZoneId
import com.gios.lightnotebook.util.PhotoDays
import com.gios.lightnotebook.util.ImportedEvent
import com.gios.lightnotebook.util.Recurrence
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

    /* ---- the zone imported times are read in ---- */

    /**
     * Which timezone an imported calendar's clock times mean, or null to trust the phone.
     *
     * This exists because the phone cannot always be trusted. An `.ics` carries instants —
     * `20260804T130000Z` — and turning one into "9:30, Tuesday" needs a zone; every other kind
     * of entry here is typed in local terms and needs none. So a device that reports the wrong
     * zone shifts every imported event by hours while leaving everything you typed correct,
     * which is a confusing thing to look at and impossible to explain from the calendar alone.
     *
     * Stored as an id rather than a [ZoneId] so a zone the platform later stops recognising
     * degrades to "use the phone" instead of throwing on read.
     */
    fun calendarZoneId(): String? = prefs.getString(KEY_ZONE, null)?.takeIf { it.isNotBlank() }

    /** The zone to actually read imported times in: the chosen one, else the phone's. */
    fun calendarZone(): ZoneId =
        calendarZoneId()?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** True when the zone is the phone's own, rather than one that was chosen here. */
    fun calendarZoneIsFromPhone(): Boolean = calendarZoneId() == null

    /** Refuses an id the platform doesn't know rather than storing a zone that can't resolve. */
    fun setCalendarZone(id: String?): Boolean {
        if (id == null) {
            prefs.edit().remove(KEY_ZONE).apply()
            return true
        }
        if (runCatching { ZoneId.of(id) }.getOrNull() == null) return false
        prefs.edit().putString(KEY_ZONE, id).apply()
        return true
    }

    fun setMirrorToSystemCalendar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR, enabled).apply()
    }

    fun mirrorToSystemCalendar(): Boolean = prefs.getBoolean(KEY_MIRROR, true)

    /**
     * Where the sun rises and sets, until something knows better.
     *
     * Sunrise needs a place and nothing on this phone records one yet, so this is a setting with a
     * sensible default rather than a reason to go without daylight. When a location recorder lands,
     * the day's own coordinates should win for days that have them; a single home position is only
     * ever the fallback.
     *
     * Stored as `Float` because `SharedPreferences` has no double. A float carries about seven
     * significant figures — a hundred metres or so at these magnitudes, and thousands of times more
     * precision than a sunrise minute needs.
     */
    fun homeLatitude(): Double =
        prefs.getFloat(KEY_LAT, Daylight.DEFAULT_LATITUDE.toFloat()).toDouble()

    fun homeLongitude(): Double =
        prefs.getFloat(KEY_LON, Daylight.DEFAULT_LONGITUDE.toFloat()).toDouble()

    /** Refuses nonsense rather than storing it: a bad latitude is a crash inside `asin` later. */
    fun setHome(latitude: Double, longitude: Double): Boolean {
        if (!Daylight.validLatitude(latitude) || !Daylight.validLongitude(longitude)) return false
        prefs.edit()
            .putFloat(KEY_LAT, latitude.toFloat())
            .putFloat(KEY_LON, longitude.toFloat())
            .apply()
        return true
    }

    /**
     * The earliest day this phone has any record of, or null when it has none.
     *
     * Used to bound how far back the weather archive reaches. Fetching the weather for 2019 on a
     * phone whose oldest photograph is from March is a hundred requests describing days that do not
     * exist in the journal — "as far as we have data" is exactly the right limit, and it is cheap to
     * ask: two `MIN()` queries and one MediaStore row.
     */
    suspend fun earliestRecordedDay(): Long? {
        val entry = dao.earliestEntryDay()
        val note = dao.earliestNoteAt()?.let { PhotoDays.localEpochDay(it, ZoneId.systemDefault()) }
        val photo = PhotoLibrary.earliestDay(context)
        return listOfNotNull(entry, note, photo).minOrNull()
    }

    fun showDaylight(): Boolean = prefs.getBoolean(KEY_DAYLIGHT, true)

    fun setShowDaylight(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DAYLIGHT, enabled).apply()
    }

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

    /**
     * Captures are **kept**, in files rather than cache.
     *
     * They used to be deleted as soon as Claude had read them, which meant a transcription
     * that got a word wrong could never be checked against the page it came from. A JPEG of a
     * sheet of paper is a couple of hundred kilobytes; the answer to "was that a 3 or an 8" is
     * worth more than that.
     */
    fun newCaptureFile(): File {
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        return File(dir, "capture-${System.currentTimeMillis()}.jpg")
    }

    /** Deletes a capture no row points at any more. */
    suspend fun forgetCapture(path: String?) {
        val target = path?.takeIf { it.isNotBlank() } ?: return
        if (dao.countNotesWithImage(target) > 0) return
        if (dao.countEntriesWithImage(target) > 0) return
        runCatching { File(target).delete() }
    }

    /* ---- notes ---- */

    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes()

    /** Notes written or returned to inside a millisecond window. */
    fun observeNotesTouched(fromMs: Long, toMs: Long): Flow<List<NoteEntity>> =
        dao.observeNotesTouched(fromMs, toMs)

    fun observeNotesIn(folderId: String): Flow<List<NoteEntity>> = dao.observeNotesIn(folderId)

    fun observeNote(id: String): Flow<NoteEntity?> = dao.observeNote(id)

    suspend fun getNote(id: String): NoteEntity? = dao.getNote(id)

    /** Creates an empty note and hands back its id so the caller can open the editor. */
    suspend fun createNote(
        title: String = "",
        body: String = "",
        folderId: String? = null,
        imagePath: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.putNote(
            NoteEntity(
                id = id,
                title = title,
                body = body,
                folderId = folderId,
                imagePath = imagePath,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    /**
     * The note another app owns under [key], creating it the first time it is asked for.
     *
     * Create-if-absent rather than a separate "make one" call because the first tap from
     * LightChat's contact page happens with nothing set up here, and a link that lands on
     * "no such note" would be a dead end. [title] only seeds a new note — a note that
     * already exists keeps whatever it was renamed to.
     *
     * The unique index on `externalKey` is what makes this safe against two taps at once:
     * the loser's insert throws and the re-read finds the winner's row. Only that one
     * failure is caught — a blanket `runCatching` here would swallow a coroutine
     * cancellation and turn a full disk into a silent no-op.
     *
     * A seeded title means an externally-made note is never blank, so the editor's "opened
     * and never written in, so throw it away" rule cannot apply to it: one stray tap on
     * LightChat's note row does leave an empty note behind, titled with the contact's name.
     * That is the trade for the row being able to say whose note it is.
     */
    suspend fun noteForExternalKey(key: String, title: String): String? {
        if (key.isBlank()) return null
        dao.getNoteByExternalKey(key)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return try {
            dao.insertNote(
                NoteEntity(
                    id = id,
                    title = title.ifBlank { key },
                    externalKey = key,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        } catch (_: SQLiteConstraintException) {
            dao.getNoteByExternalKey(key)?.id
        }
    }

    suspend fun saveNote(note: NoteEntity) =
        dao.putNote(note.copy(updatedAt = System.currentTimeMillis()))

    /** Appends photographed text under whatever is already in the note. */
    suspend fun appendToNote(id: String, text: String, imagePath: String? = null): Boolean {
        val note = dao.getNote(id) ?: return false
        val joined = if (note.body.isBlank()) text else note.body.trimEnd() + "\n\n" + text
        dao.putNote(
            note.copy(
                body = joined,
                // The newest photograph wins: it is the one that produced the text at the
                // bottom, which is the part somebody would want to check.
                imagePath = imagePath ?: note.imagePath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
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

    fun observeRange(from: Long, to: Long): Flow<List<DayEntryEntity>> =
        dao.observeRange(from, to)

    /** Every repeating entry. See [NotebookDao.observeRecurring] for why this is unwindowed. */
    fun observeRecurring(): Flow<List<DayEntryEntity>> = dao.observeRecurring()

    /**
     * Sets, changes or removes an entry's repeat rule.
     *
     * Changing the rule clears the exceptions with it. An `EXDATE` is a hole in a *particular*
     * series — "not that Tuesday" — and once the series is a different one, the day it used to
     * describe means nothing. Keeping them would silently punch holes in the new schedule.
     */
    suspend fun setRepeat(entry: DayEntryEntity, rrule: String?): DayEntryEntity =
        updateDayEntry(
            entry.copy(rrule = rrule?.takeIf { it.isNotBlank() }, exDays = null),
        )

    /** "Delete just this one": the occurrence on [epochDay] is excluded, the series survives. */
    suspend fun skipOccurrence(entry: DayEntryEntity, epochDay: Long): DayEntryEntity {
        if (!entry.repeats) return entry
        val days = Recurrence.parseExDays(entry.exDays) + epochDay
        return updateDayEntry(entry.copy(exDays = Recurrence.formatExDays(days)))
    }

    /**
     * "Edit just this one": one occurrence leaves the series and becomes an entry of its own.
     *
     * The series gets an `EXDATE` for that day and a detached copy is written on it, which is
     * exactly how a calendar server models an overridden instance. The copy deliberately drops
     * three things: the rule (it happens once now), the source uid and the calendar id — a
     * detached occurrence of an *imported* series belongs to you, and leaving the calendar id on
     * it would mean the next re-import deleted the edit along with the feed's own rows.
     */
    suspend fun detachOccurrence(entry: DayEntryEntity, epochDay: Long): DayEntryEntity {
        if (!entry.repeats) return entry
        skipOccurrence(entry, epochDay)
        val now = System.currentTimeMillis()
        val detached = entry.copy(
            id = UUID.randomUUID().toString(),
            epochDay = epochDay,
            // A span keeps its length: a three-day occurrence detached onto another day is
            // still three days long.
            endEpochDay = entry.endEpochDay?.let { epochDay + (it - entry.epochDay) },
            rrule = null,
            exDays = null,
            sourceUid = null,
            calendarId = null,
            // Its own event in the phone's calendar, or none: the mirror row belongs to the
            // series, and two entries pointing at one system event delete each other's copy.
            systemEventId = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.putDayEntry(detached)
        return detached
    }

    /**
     * How many days an entry covers.
     *
     * Null or the same day ends the span. A range that runs backwards is refused rather than stored:
     * every query tests containment with `BETWEEN epochDay AND COALESCE(endEpochDay, epochDay)`, and
     * a backwards range makes that false everywhere — the entry would disappear from every day,
     * including the one it starts on.
     */
    suspend fun setEntrySpan(entry: DayEntryEntity, endEpochDay: Long?): DayEntryEntity {
        val end = endEpochDay?.takeIf { it > entry.epochDay }
        return updateDayEntry(entry.copy(endEpochDay = end))
    }

    suspend fun addDayEntry(
        epochDay: Long,
        text: String,
        endEpochDay: Long? = null,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        fromPhoto: Boolean = false,
        systemEventId: Long? = null,
        reminderMinutes: Int? = null,
        imagePath: String? = null,
    ): DayEntryEntity {
        val entry = DayEntryEntity(
            id = UUID.randomUUID().toString(),
            epochDay = epochDay,
            // Normalised rather than trusted: a backwards range would make every containment test
            // in the queries silently false, and the entry would vanish from every day including
            // its own.
            endEpochDay = endEpochDay?.takeIf { it > epochDay },
            text = text.trim(),
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            fromPhoto = fromPhoto,
            systemEventId = systemEventId,
            imagePath = imagePath,
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
                // One row per series, not per instance. See [DayEntryEntity.rrule].
                rrule = event.rrule,
                exDays = Recurrence.formatExDays(event.exDays),
                location = event.location,
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
        const val KEY_LAT = "home_lat"
        const val KEY_LON = "home_lon"
        const val KEY_DAYLIGHT = "show_daylight"
        const val KEY_ZONE = "calendar_zone"
        const val DEFAULT_LEAD = 10
    }
}
