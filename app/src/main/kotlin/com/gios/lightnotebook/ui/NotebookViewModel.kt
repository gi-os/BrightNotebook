package com.gios.lightnotebook.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightnotebook.ai.ParsedEvent
import com.gios.lightnotebook.ai.ReadMode
import com.gios.lightnotebook.ai.Vision
import com.gios.lightnotebook.ai.VisionParser
import com.gios.lightnotebook.data.CalendarEntity
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.DeviceCalendar
import com.gios.lightnotebook.data.DeviceCalendars
import com.gios.lightnotebook.data.FolderEntity
import com.gios.lightnotebook.data.ImportResult
import com.gios.lightnotebook.data.LightPassBridge
import com.gios.lightnotebook.data.NoteEntity
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.PassShowing
import com.gios.lightnotebook.data.Sync
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.notify.SyncAlarm
import com.gios.lightnotebook.util.Agenda
import com.gios.lightnotebook.util.AgendaRow
import com.gios.lightnotebook.util.IcsParser
import com.gios.lightnotebook.util.ImageUtils
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.NoteMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

/** Where a photographed page ends up. */
sealed interface CaptureState {
    data object Idle : CaptureState
    data object Reading : CaptureState
    data class NoteRead(
        val title: String,
        val body: String,
        val imagePath: String,
    ) : CaptureState

    /** Events as read, still editable — the model gets dates and times wrong sometimes. */
    data class EventsRead(
        val events: List<ParsedEvent>,
        val imagePath: String,
    ) : CaptureState

    data class Failed(val message: String) : CaptureState
}

class NotebookViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NotebookRepository(app)

    /* ---------------- notes ---------------- */

    val folders: StateFlow<List<FolderEntity>> = repo.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _folderFilter = MutableStateFlow<String?>(null)

    /** null means All Notes. */
    val folderFilter: StateFlow<String?> = _folderFilter.asStateFlow()

    private val allNotes: StateFlow<List<NoteEntity>> = repo.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<NoteEntity>> =
        combine(allNotes, _folderFilter) { list, folder ->
            if (folder == null) list else list.filter { it.folderId == folder }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every note regardless of filter — the append-to-note picker needs all of them. */
    val notesUnfiltered: StateFlow<List<NoteEntity>> = allNotes

    val noteCounts: StateFlow<Map<String?, Int>> = allNotes
        .map { list -> list.groupingBy { it.folderId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun selectFolder(id: String?) {
        _folderFilter.value = id
    }

    fun observeNote(id: String) = repo.observeNote(id)

    fun createNote(onCreated: (String) -> Unit) = viewModelScope.launch {
        onCreated(repo.createNote(folderId = _folderFilter.value))
    }

    /**
     * Saved on a short delay so a burst of typing is one write, then flushed outright
     * when the editor closes — see [flushNote].
     */
    private var saveJob: Job? = null

    fun saveNote(note: NoteEntity) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            repo.saveNote(note)
        }
    }

    fun flushNote(note: NoteEntity) {
        saveJob?.cancel()
        viewModelScope.launch { repo.saveNote(note) }
    }

    fun togglePinned(note: NoteEntity) = viewModelScope.launch {
        repo.setPinned(note.id, !note.pinned)
    }

    fun moveNote(noteId: String, folderId: String?) = viewModelScope.launch {
        repo.setFolder(noteId, folderId)
    }

    fun deleteNote(id: String) = viewModelScope.launch {
        val image = repo.getNote(id)?.imagePath
        repo.deleteNote(id)
        // The photograph goes only once nothing else points at it.
        withContext(Dispatchers.IO) { repo.forgetCapture(image) }
    }

    /* ---------------- folders ---------------- */

    fun createFolder(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.createFolder(name)
    }

    fun renameFolder(folder: FolderEntity, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.renameFolder(folder, name)
    }

    fun deleteFolder(id: String) = viewModelScope.launch {
        if (_folderFilter.value == id) _folderFilter.value = null
        repo.deleteFolder(id)
    }

    /* ---------------- calendar ---------------- */

    private val _month = MutableStateFlow(NoteDates.monthOf(NoteDates.today()))
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    /* ---- films, read out of LightPass ---- */

    private val _showings = MutableStateFlow<List<PassShowing>>(emptyList())

    /** Screenings from LightPass. Empty when it isn't installed, which is not an error. */
    val showings: StateFlow<List<PassShowing>> = _showings.asStateFlow()

    /**
     * Re-read on every visit to a calendar screen rather than observed: tickets change when
     * the user is in the other app, so there is no moment here worth watching for, and the
     * shelf is a handful of rows.
     */
    fun refreshShowings() = viewModelScope.launch {
        _showings.value = withContext(Dispatchers.IO) {
            LightPassBridge.showings(getApplication())
        }
    }

    fun openPass(passId: String) {
        LightPassBridge.openPass(getApplication(), passId)
    }

    private val _selectedDay = MutableStateFlow(NoteDates.today())
    val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entryCounts: StateFlow<Map<Long, Int>> = _month
        .flatMapLatest { m ->
            val from = m.atDay(1).toEpochDay()
            val to = m.atEndOfMonth().toEpochDay()
            repo.observeDayCounts(from, to).map { rows ->
                rows.associate { it.epochDay to it.entries }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Dots on the grid count films too, or a day with only a ticket on it looks empty. */
    val dayCounts: StateFlow<Map<Long, Int>> =
        combine(entryCounts, _showings) { counts, showings ->
            val merged = counts.toMutableMap()
            showings.forEach { merged[it.epochDay] = (merged[it.epochDay] ?: 0) + 1 }
            merged
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayEntries: StateFlow<List<DayEntryEntity>> = _selectedDay
        .flatMapLatest { repo.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Films on the open day, earliest first. */
    val dayShowings: StateFlow<List<PassShowing>> =
        combine(_selectedDay, _showings) { day, showings ->
            showings.filter { it.epochDay == day }.sortedBy { it.startMinutes ?: -1 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The open day as rows, with tickets folded into the calendar entries that describe the
     * same plan. A film in the calendar and a ticket for it are one thing you are doing, and
     * showing both was how the day ended up saying "Dune" twice.
     */
    val dayRows: StateFlow<List<AgendaRow>> =
        combine(dayEntries, dayShowings, repo.observeCalendars()) { entries, showings, calendars ->
            Agenda.collapse(
                entries = entries.map { it.toAgendaRow(calendars) },
                films = showings.map { it.toAgendaRow() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Everything ahead as rows, folded the same way, for the agenda screen.
     *
     * Reads the repository rather than the [upcoming] flow below it: a property initialiser
     * cannot see one declared later, and Kotlin only says so at compile time on a good day.
     */
    val agendaRows: StateFlow<List<AgendaRow>> =
        combine(
            repo.observeUpcoming(NoteDates.today(), UPCOMING_LIMIT),
            _showings,
            repo.observeCalendars(),
        ) { entries, showings, calendars ->
            val today = NoteDates.today()
            Agenda.collapse(
                entries = entries.map { it.toAgendaRow(calendars) },
                films = showings.filter { it.epochDay >= today }.map { it.toAgendaRow() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /* ---- the zoomable planner's window ---- */

    private val _canvasWindow = MutableStateFlow(NoteDates.today()..NoteDates.today())

    /**
     * Rows for the days the planner can currently see, keyed by day.
     *
     * Windowed rather than "everything": the surface is endless, and the alternative is
     * holding every entry ever written to draw six visible weeks. The canvas reports what it
     * needs as it is panned.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val canvasRows: StateFlow<Map<Long, List<AgendaRow>>> = _canvasWindow
        .flatMapLatest { window ->
            combine(
                repo.observeRange(window.first, window.last),
                _showings,
                repo.observeCalendars(),
            ) { entries, showings, calendars ->
                val films = showings.filter { it.epochDay in window }
                Agenda.collapse(
                    entries = entries.map { it.toAgendaRow(calendars) },
                    films = films.map { it.toAgendaRow() },
                ).groupBy { it.epochDay }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Called as the planner is panned. Ignores anything that isn't actually a new span. */
    fun setCanvasWindow(from: Long, to: Long) {
        val next = from..to
        if (_canvasWindow.value != next) _canvasWindow.value = next
    }

    /** Looks an entry back up for its own sheet, since a row only carries the id. */
    fun entryById(id: String?): DayEntryEntity? =
        id?.let { wanted -> dayEntries.value.firstOrNull { it.id == wanted } }

    /** Moves the open day by a day at a time, for swiping. */
    fun stepDay(delta: Long) {
        selectDay(_selectedDay.value + delta)
    }

    /**
     * Everything ahead, for the agenda screen. Sixty rows rather than a handful: it is a
     * whole screen now, and scrolling it is the point.
     */
    val upcoming: StateFlow<List<DayEntryEntity>> =
        repo.observeUpcoming(NoteDates.today(), UPCOMING_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun stepMonth(delta: Long) {
        _month.value = _month.value.plusMonths(delta)
    }

    fun jumpToToday() {
        val today = NoteDates.today()
        _month.value = NoteDates.monthOf(today)
        _selectedDay.value = today
    }

    fun selectDay(epochDay: Long) {
        _selectedDay.value = epochDay
        _month.value = NoteDates.monthOf(epochDay)
    }

    fun addDayEntry(epochDay: Long, text: String, startMinutes: Int? = null) =
        viewModelScope.launch {
            if (text.isBlank()) return@launch
            val eventId = mirror(text, epochDay, startMinutes, null)
            // A timed entry gets the default lead automatically. Being told about something
            // you wrote a time on is the point of writing the time.
            val entry = repo.addDayEntry(
                epochDay = epochDay,
                text = text,
                startMinutes = startMinutes,
                systemEventId = eventId,
                reminderMinutes = repo.defaultReminderMinutes(),
            )
            Reminders.schedule(getApplication(), entry)
        }

    fun updateDayEntry(entry: DayEntryEntity, text: String) = viewModelScope.launch {
        val updated = repo.updateDayEntry(entry.copy(text = text.trim()))
        Reminders.schedule(getApplication(), updated)
    }

    /** Changing the lead re-arms the alarm; null takes it away. */
    fun setEntryReminder(entry: DayEntryEntity, minutes: Int?) = viewModelScope.launch {
        val updated = repo.updateDayEntry(entry.copy(reminderMinutes = minutes))
        if (minutes == null) {
            Reminders.cancel(getApplication(), updated.id)
        } else {
            Reminders.schedule(getApplication(), updated)
        }
    }

    /** Moves an entry to another day — the correction a misread date needs. */
    fun setEntryDay(entry: DayEntryEntity, epochDay: Long) = viewModelScope.launch {
        val updated = repo.updateDayEntry(entry.copy(epochDay = epochDay))
        Reminders.schedule(getApplication(), updated)
        selectDay(epochDay)
    }

    /**
     * Turns a note into an event on a day, leaving the note alone.
     *
     * The note keeps its photograph and the event borrows it, so a film title read off a
     * ticket stub can still be checked from either side.
     */
    fun noteToEvent(
        note: NoteEntity,
        epochDay: Long,
        startMinutes: Int?,
        onDone: (Long) -> Unit,
    ) = viewModelScope.launch {
        val text = note.title.ifBlank { NoteMarkdown.firstLine(note.body) }.ifBlank { "Note" }
        val eventId = mirror(text, epochDay, startMinutes, null)
        val entry = repo.addDayEntry(
            epochDay = epochDay,
            text = text,
            startMinutes = startMinutes,
            systemEventId = eventId,
            reminderMinutes = repo.defaultReminderMinutes(),
            imagePath = note.imagePath,
        )
        Reminders.schedule(getApplication(), entry)
        selectDay(epochDay)
        onDone(epochDay)
    }

    fun setEntryTime(entry: DayEntryEntity, startMinutes: Int?) = viewModelScope.launch {
        val updated = repo.updateDayEntry(
            entry.copy(
                startMinutes = startMinutes,
                // A reminder with nothing to count back from is dropped rather than kept
                // as a surprise for the next time a time is set.
                reminderMinutes = if (startMinutes == null) null else entry.reminderMinutes,
            ),
        )
        if (updated.reminderMinutes == null) {
            Reminders.cancel(getApplication(), updated.id)
        } else {
            Reminders.schedule(getApplication(), updated)
        }
    }

    /** Removing an entry removes the copy in the phone's calendar too, if there is one. */
    fun deleteDayEntry(entry: DayEntryEntity) = viewModelScope.launch {
        entry.systemEventId?.let { id ->
            withContext(Dispatchers.IO) { SystemCalendar.delete(getApplication(), id) }
        }
        Reminders.cancel(getApplication(), entry.id)
        repo.deleteDayEntry(entry.id)
        withContext(Dispatchers.IO) { repo.forgetCapture(entry.imagePath) }
    }

    /**
     * Re-arms every future reminder. Called at launch as well as from boot: a force-stop
     * clears an app's alarms, and nothing tells the app that happened.
     */
    fun rearmReminders() = viewModelScope.launch {
        val entries = repo.entriesWithReminders(NoteDates.today())
        withContext(Dispatchers.IO) { Reminders.rearmAll(getApplication(), entries) }
    }

    /* ---------------- calendars and importing ---------------- */

    val calendars: StateFlow<List<CalendarEntity>> = repo.observeCalendars()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importStatus = MutableStateFlow<String?>(null)

    /** One line about the last import, shown on the calendars screen and then cleared. */
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    fun clearImportStatus() {
        _importStatus.value = null
    }

    fun calendarLabel(calendarId: String?): String? =
        calendarId?.let { id -> calendars.value.firstOrNull { it.id == id }?.label }

    fun setCalendarVisible(calendar: CalendarEntity, visible: Boolean) = viewModelScope.launch {
        repo.setCalendarVisible(calendar, visible)
    }

    fun renameCalendar(calendar: CalendarEntity, label: String) = viewModelScope.launch {
        if (label.isNotBlank()) repo.renameCalendar(calendar, label)
    }

    fun deleteCalendar(calendar: CalendarEntity) = viewModelScope.launch {
        // Take the alarms down before the rows go, or they fire for events that don't exist.
        val doomed = repo.entriesWithReminders(0L).filter { it.calendarId == calendar.id }
        doomed.forEach { Reminders.cancel(getApplication(), it.id) }
        repo.deleteCalendar(calendar.id)
    }

    /** Reads an .ics the document picker handed over. */
    fun importIcs(uri: Uri) = viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            val text = repo.readText(uri)
            when {
                text == null -> null to "Could not open that file."
                !IcsParser.looksLikeIcs(text) -> null to "That is not a calendar file."
                else -> {
                    val events = IcsParser.parse(text)
                    if (events.isEmpty()) {
                        null to "No events in that file."
                    } else {
                        repo.importEvents(
                            label = repo.displayName(uri),
                            kind = CalendarEntity.KIND_ICS,
                            sourceRef = uri.toString(),
                            events = events,
                            reminderMinutes = repo.defaultReminderMinutes(),
                        ) to null
                    }
                }
            }
        }
        finishImport(outcome.first, outcome.second)
    }

    fun deviceCalendars(): List<DeviceCalendar> = DeviceCalendars.available(getApplication())

    /** Copies a window of one phone calendar in, replacing whatever a previous run left. */
    fun importDeviceCalendar(calendar: DeviceCalendar) = viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            val events = DeviceCalendars.events(getApplication(), calendar.id)
            if (events.isEmpty()) {
                null to "Nothing in ${calendar.label} to import."
            } else {
                repo.importEvents(
                    label = calendar.label,
                    kind = CalendarEntity.KIND_DEVICE,
                    sourceRef = calendar.id.toString(),
                    events = events,
                    reminderMinutes = repo.defaultReminderMinutes(),
                ) to null
            }
        }
        finishImport(outcome.first, outcome.second)
    }

    /** The SYNC NOW button. The same pass the hourly alarm runs. */
    fun syncNow() = viewModelScope.launch {
        _importStatus.value = "Syncing…"
        val result = withContext(Dispatchers.IO) { Sync.run(getApplication()) }
        refreshShowings()
        _importStatus.value = when {
            result.nothingToDo -> "Nothing imported yet — add a calendar first."
            result.failed > 0 && result.calendars == 0 ->
                "Could not reach any calendar. The file may have moved."
            result.failed > 0 ->
                "Refreshed ${result.calendars}, could not reach ${result.failed}."
            else -> "Refreshed ${result.calendars} calendar(s), ${result.events} event(s)."
        }
    }

    /** Arms the hourly refresh. Called at launch; boot does it too. */
    fun scheduleSync() {
        SyncAlarm.schedule(getApplication())
    }

    private suspend fun finishImport(result: ImportResult?, error: String?) {
        if (result == null) {
            _importStatus.value = error ?: "Nothing imported."
            return
        }
        withContext(Dispatchers.IO) {
            Reminders.rearmAll(getApplication(), result.entries.filter { it.epochDay >= NoteDates.today() })
        }
        refreshShowings()
        _importStatus.value = buildString {
            append(if (result.replaced) "Refreshed " else "Imported ")
            append("${result.entries.size} event")
            if (result.entries.size != 1) append("s")
            append(" into ${result.calendar.label}.")
        }
    }

    /* ---------------- settings ---------------- */

    private val _apiKey = MutableStateFlow(repo.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _mirrorEvents = MutableStateFlow(repo.mirrorToSystemCalendar())
    val mirrorEvents: StateFlow<Boolean> = _mirrorEvents.asStateFlow()

    private val _defaultLead = MutableStateFlow(repo.defaultReminderMinutes())

    /** Lead time a new timed entry is given, in minutes. Null is no reminder. */
    val defaultLead: StateFlow<Int?> = _defaultLead.asStateFlow()

    fun setDefaultLead(minutes: Int?) {
        repo.setDefaultReminderMinutes(minutes)
        _defaultLead.value = minutes
    }

    fun setApiKey(key: String) {
        repo.setApiKey(key)
        _apiKey.value = repo.getApiKey()
    }

    fun setMirrorEvents(enabled: Boolean) {
        repo.setMirrorToSystemCalendar(enabled)
        _mirrorEvents.value = enabled
    }

    fun systemCalendarName(): String? = SystemCalendar.writableCalendarName(getApplication())

    /* ---------------- camera ---------------- */

    private val _capture = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    /** Set when the camera was opened from the calendar tab, which biases the read. */
    private var pendingMode: ReadMode = ReadMode.AUTO

    fun newCaptureFile(): File = repo.newCaptureFile()

    fun setReadMode(mode: ReadMode) {
        pendingMode = mode
    }

    fun readCapture(file: File) {
        _capture.value = CaptureState.Reading
        val key = _apiKey.value
        val mode = pendingMode
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ImageUtils.prepareForUpload(file)
                VisionParser.read(file, key, mode)
            }
            // The photograph is kept, not deleted: whatever it produced can be checked against
            // it later, which is the only way to settle a misread word.
            val path = file.absolutePath
            _capture.value = when (result) {
                is Vision.Note -> CaptureState.NoteRead(result.title, result.body, path)
                is Vision.Events -> CaptureState.EventsRead(result.events, path)
                is Vision.Failed -> {
                    withContext(Dispatchers.IO) { file.delete() }
                    CaptureState.Failed(result.reason)
                }
            }
        }
    }

    fun clearCapture() {
        _capture.value = CaptureState.Idle
        pendingMode = ReadMode.AUTO
    }

    /** Photographed page becomes a new note, carrying the photograph with it. */
    fun captureToNewNote(
        title: String,
        body: String,
        imagePath: String?,
        onCreated: (String) -> Unit,
    ) = viewModelScope.launch {
        val id = repo.createNote(
            title = title,
            body = body,
            folderId = _folderFilter.value,
            imagePath = imagePath,
        )
        clearCapture()
        onCreated(id)
    }

    /** Photographed page is appended to the bottom of an existing note. */
    fun captureToExistingNote(
        noteId: String,
        body: String,
        imagePath: String?,
        onDone: (String) -> Unit,
    ) = viewModelScope.launch {
        repo.appendToNote(noteId, body, imagePath)
        clearCapture()
        onDone(noteId)
    }

    /**
     * Commits the events the user kept. Each one becomes a day entry, and — unless the
     * mirror is switched off — a real event in the phone's calendar as well.
     */
    fun commitEvents(
        events: List<ParsedEvent>,
        imagePath: String?,
        onDone: (Int, Boolean) -> Unit,
    ) =
        viewModelScope.launch {
            var mirrored = 0
            val lead = repo.defaultReminderMinutes()
            events.forEach { event ->
                val eventId = mirror(
                    event.title,
                    event.epochDay,
                    event.startMinutes,
                    event.endMinutes,
                )
                if (eventId != null) mirrored++
                val entry = repo.addDayEntry(
                    epochDay = event.epochDay,
                    text = event.title,
                    startMinutes = event.startMinutes,
                    endMinutes = event.endMinutes,
                    fromPhoto = true,
                    systemEventId = eventId,
                    reminderMinutes = lead,
                    imagePath = imagePath,
                )
                Reminders.schedule(getApplication(), entry)
            }
            events.minByOrNull { it.epochDay }?.let { selectDay(it.epochDay) }
            clearCapture()
            onDone(events.size, mirrored > 0)
        }

    /* ---------------- mapping to agenda rows ---------------- */

    private fun DayEntryEntity.toAgendaRow(calendars: List<CalendarEntity>) = AgendaRow(
        // Namespaced so an entry and a ticket can never collide on a list key.
        id = "entry:$id",
        epochDay = epochDay,
        minutes = startMinutes,
        title = text,
        label = calendars.firstOrNull { it.id == calendarId }?.label,
        reminderMinutes = reminderMinutes,
        entryId = id,
    )

    private fun PassShowing.toAgendaRow() = AgendaRow(
        id = "pass:$passId",
        epochDay = epochDay,
        minutes = startMinutes,
        title = title,
        label = where,
        passId = passId,
    )

    private suspend fun mirror(
        title: String,
        epochDay: Long,
        startMinutes: Int?,
        endMinutes: Int?,
    ): Long? {
        if (!_mirrorEvents.value) return null
        return withContext(Dispatchers.IO) {
            SystemCalendar.insert(getApplication(), title, epochDay, startMinutes, endMinutes)
        }
    }

    private companion object {
        /** Enough to scroll for a while without holding a year of rows in memory. */
        const val UPCOMING_LIMIT = 60
    }
}
