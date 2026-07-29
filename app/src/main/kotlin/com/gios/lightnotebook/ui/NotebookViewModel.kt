package com.gios.lightnotebook.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightnotebook.ai.ParsedEvent
import com.gios.lightnotebook.ai.ReadMode
import com.gios.lightnotebook.ai.Vision
import com.gios.lightnotebook.ai.VisionParser
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.FolderEntity
import com.gios.lightnotebook.data.LightPassBridge
import com.gios.lightnotebook.data.NoteEntity
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.PassShowing
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.util.ImageUtils
import com.gios.lightnotebook.util.NoteDates
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
    data class NoteRead(val title: String, val body: String) : CaptureState
    data class EventsRead(val events: List<ParsedEvent>) : CaptureState
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

    fun deleteNote(id: String) = viewModelScope.launch { repo.deleteNote(id) }

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

    val upcoming: StateFlow<List<DayEntryEntity>> = repo.observeUpcoming(NoteDates.today(), 8)
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
            repo.addDayEntry(
                epochDay = epochDay,
                text = text,
                startMinutes = startMinutes,
                systemEventId = eventId,
            )
        }

    fun updateDayEntry(entry: DayEntryEntity, text: String) = viewModelScope.launch {
        repo.updateDayEntry(entry.copy(text = text.trim()))
    }

    /** Removing an entry removes the copy in the phone's calendar too, if there is one. */
    fun deleteDayEntry(entry: DayEntryEntity) = viewModelScope.launch {
        entry.systemEventId?.let { id ->
            withContext(Dispatchers.IO) { SystemCalendar.delete(getApplication(), id) }
        }
        repo.deleteDayEntry(entry.id)
    }

    /* ---------------- settings ---------------- */

    private val _apiKey = MutableStateFlow(repo.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _mirrorEvents = MutableStateFlow(repo.mirrorToSystemCalendar())
    val mirrorEvents: StateFlow<Boolean> = _mirrorEvents.asStateFlow()

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
            file.delete()
            _capture.value = when (result) {
                is Vision.Note -> CaptureState.NoteRead(result.title, result.body)
                is Vision.Events -> CaptureState.EventsRead(result.events)
                is Vision.Failed -> CaptureState.Failed(result.reason)
            }
        }
    }

    fun clearCapture() {
        _capture.value = CaptureState.Idle
        pendingMode = ReadMode.AUTO
    }

    /** Photographed page becomes a new note. */
    fun captureToNewNote(title: String, body: String, onCreated: (String) -> Unit) =
        viewModelScope.launch {
            val id = repo.createNote(
                title = title,
                body = body,
                folderId = _folderFilter.value,
            )
            clearCapture()
            onCreated(id)
        }

    /** Photographed page is appended to the bottom of an existing note. */
    fun captureToExistingNote(noteId: String, body: String, onDone: (String) -> Unit) =
        viewModelScope.launch {
            repo.appendToNote(noteId, body)
            clearCapture()
            onDone(noteId)
        }

    /**
     * Commits the events the user kept. Each one becomes a day entry, and — unless the
     * mirror is switched off — a real event in the phone's calendar as well.
     */
    fun commitEvents(events: List<ParsedEvent>, onDone: (Int, Boolean) -> Unit) =
        viewModelScope.launch {
            var mirrored = 0
            events.forEach { event ->
                val eventId = mirror(
                    event.title,
                    event.epochDay,
                    event.startMinutes,
                    event.endMinutes,
                )
                if (eventId != null) mirrored++
                repo.addDayEntry(
                    epochDay = event.epochDay,
                    text = event.title,
                    startMinutes = event.startMinutes,
                    endMinutes = event.endMinutes,
                    fromPhoto = true,
                    systemEventId = eventId,
                )
            }
            events.minByOrNull { it.epochDay }?.let { selectDay(it.epochDay) }
            clearCapture()
            onDone(events.size, mirrored > 0)
        }

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
}
