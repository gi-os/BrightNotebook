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
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.DayBridges
import com.gios.lightnotebook.data.DeviceUse
import com.gios.lightnotebook.data.FolderEntity
import com.gios.lightnotebook.data.ImportResult
import com.gios.lightnotebook.data.LightPassBridge
import com.gios.lightnotebook.data.NoteEntity
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.PassShowing
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.lightnotebook.data.RollStars
import com.gios.lightnotebook.data.StepStore
import com.gios.lightnotebook.data.Sync
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.notify.SyncAlarm
import com.gios.lightnotebook.util.Agenda
import com.gios.lightnotebook.util.AgendaRow
import com.gios.lightnotebook.util.DayTimeline
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.Daylight
import com.gios.lightnotebook.util.IcsParser
import com.gios.lightnotebook.util.ImageUtils
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.OnThisDay
import com.gios.lightnotebook.util.PhotoDays
import com.gios.lightnotebook.util.ScreenUse
import com.gios.lightnotebook.util.Steps
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.time.ZoneId

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
     * The note another app keeps here under [key], made now if this is the first ask.
     *
     * The one entry point for `lightnotebook://note/<key>`. Suspending rather than taking a
     * callback, unlike [createNote]: the caller is a `LaunchedEffect`, and awaiting it there
     * means an activity recreated during the lookup cancels the wait instead of navigating a
     * `NavController` that has since been disposed. Null when the key is blank or the write
     * failed, which leaves the app where it was rather than on an editor for a note that
     * does not exist.
     */
    suspend fun noteFor(key: String, title: String): String? = repo.noteForExternalKey(key, title)

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
        combine(
            dayEntries,
            dayShowings,
            repo.observeCalendars(),
            _selectedDay,
        ) { entries, showings, calendars, day ->
            Agenda.collapse(
                // Clipped to the one day, so a span becomes exactly one row saying which day of it
                // this is rather than one row per day of the trip.
                entries = entries.acrossDays(day, day, calendars),
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
                    entries = entries.acrossDays(window.first, window.last, calendars),
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

    /* ---- photographs, read out of MediaStore ---- */

    /**
     * A nudge, because MediaStore is asked rather than observed.
     *
     * A content observer would fire for every thumbnail the system generates and every
     * unrelated download, and the answer is only looked at when a calendar screen is on the
     * panel. So it is re-read on arrival, exactly like [refreshShowings] — photographs are
     * taken while the user is in Roll, so there is no moment in this process worth watching.
     */
    private val _photoNudge = MutableStateFlow(0)

    private val _photosGranted = MutableStateFlow(PhotoLibrary.granted(getApplication()))

    /** Whether the library can be read at all. The day screen offers to ask when it can't. */
    val photosGranted: StateFlow<Boolean> = _photosGranted.asStateFlow()

    /**
     * Per visible day: the photograph to draw behind the cell, and when that day's photographs
     * started and stopped — which is half of the day's activity span on the planner.
     *
     * Keyed off the planner's own window, so panning a year does not read a year: the same
     * reasoning as [canvasRows], and the same window drives both.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photoSummaries: StateFlow<Map<Long, PhotoLibrary.DaySummary>> =
        combine(_canvasWindow, _photoNudge, _photosGranted) { window, _, granted -> window to granted }
            .mapLatest { (window, granted) ->
                if (!granted) {
                    emptyMap()
                } else {
                    withContext(Dispatchers.IO) {
                        // Re-read on each window: a star is toggled in Roll while this app is in the
                        // background, so there is no moment here worth watching for.
                        val starred = RollStars.names(getApplication())
                        PhotoLibrary.summaries(
                            getApplication(),
                            window.first,
                            window.last,
                            starred = starred,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The notes written or returned to on the open day.
     *
     * Observed rather than nudged, unlike the photographs: these are this app's own rows, so
     * Room tells us the moment one changes — and writing a note *is* the thing being recorded,
     * so the day has to reflect it immediately rather than on the next visit.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayNotes: StateFlow<List<DayTimeline.Item.Note>> = _selectedDay
        .flatMapLatest { day ->
            val window = PhotoDays.windowMs(day, day, ZoneId.systemDefault())
            val from = window.first
            val to = window.last + 1
            repo.observeNotesTouched(from, to).map { notes ->
                notes.mapNotNull { note ->
                    DayTimeline.noteActivity(
                        noteId = note.id,
                        title = note.title.ifBlank { NoteMarkdown.firstLine(note.body) },
                        createdAtMs = note.createdAt,
                        updatedAtMs = note.updatedAt,
                        dayStartMs = from,
                        dayEndExclusiveMs = to,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The open day's photographs, earliest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayPhotos: StateFlow<List<DevicePhoto>> =
        combine(_selectedDay, _photoNudge, _photosGranted) { day, _, granted -> day to granted }
            .mapLatest { (day, granted) ->
                if (!granted) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) { PhotoLibrary.photosOn(getApplication(), day) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * When it got light and when it got dark on the open day.
     *
     * Computed, not fetched — no network, no model, no permission, and it works for any date in
     * either direction. See [Daylight]. Null when the setting is off, and a polar result is a
     * different shape rather than a missing one.
     */
    private val _daylightSettings = MutableStateFlow(
        Triple(repo.showDaylight(), repo.homeLatitude(), repo.homeLongitude()),
    )

    /** Where sunrise is computed for, so Settings can show it. */
    val home: StateFlow<Pair<Double, Double>> = _daylightSettings
        .map { it.second to it.third }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            repo.homeLatitude() to repo.homeLongitude(),
        )

    val daylightShown: StateFlow<Boolean> = _daylightSettings
        .map { it.first }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.showDaylight())

    /**
     * Daylight for every day the planner can see, so a cell can draw its own.
     *
     * Keyed off the canvas window rather than the open day, because this is drawn *on the calendar*
     * — panning a year should show the day length breathing, which is the thing a wall planner can
     * say that a single day screen cannot. Computed off the main thread on each new window: the
     * trigonometry is cheap but a year of it per frame would not be.
     *
     * Combined with the settings rather than reading prefs inside the map, or the toggle only takes
     * effect the next time the window changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val daylightByDay: StateFlow<Map<Long, Daylight.Result>> =
        combine(_canvasWindow, _daylightSettings) { window, settings -> window to settings }
            .mapLatest { (window, settings) ->
                val (on, lat, lon) = settings
                if (!on) {
                    emptyMap()
                } else {
                    withContext(Dispatchers.Default) {
                        val zone = ZoneId.systemDefault()
                        (window.first..window.last).associateWith { Daylight.of(it, lat, lon, zone) }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setShowDaylight(enabled: Boolean) {
        repo.setShowDaylight(enabled)
        _daylightSettings.value = _daylightSettings.value.copy(first = enabled)
    }

    /** False when the coordinates are nonsense, so the sheet can say so instead of storing them. */
    fun setHome(latitude: Double, longitude: Double): Boolean {
        if (!repo.setHome(latitude, longitude)) return false
        _daylightSettings.value = Triple(repo.showDaylight(), latitude, longitude)
        return true
    }

    /**
     * The same date in previous years, with a photograph from each that has one.
     *
     * Ten small queries rather than one clever one: a day is a day, `covers` already answers
     * exactly this question for a single one, and ten of them off the main thread once per day
     * opened is nothing. Years with no photograph are dropped rather than shown empty — a row of
     * blank frames says "this feature is broken", not "you took no pictures in 2021".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val onThisDay: StateFlow<List<Pair<OnThisDay.PastDay, DevicePhoto>>> =
        combine(_selectedDay, _photoNudge, _photosGranted) { day, _, granted -> day to granted }
            .mapLatest { (day, granted) ->
                if (!granted) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) {
                        OnThisDay.priorYears(day).mapNotNull { past ->
                            PhotoLibrary
                                .summaries(getApplication(), past.epochDay, past.epochDay)[past.epochDay]
                                ?.cover
                                ?.let { past to it }
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the bars are out of the way.
     *
     * Held here rather than in the day screen because the two bars live in different places: the
     * day owns its top bar, and the bottom bar belongs to the shell that is still composed
     * underneath it. Scrolling a day has to move both, so the state is hoisted to the one thing
     * both can see.
     */
    private val _chromeHidden = MutableStateFlow(false)
    val chromeHidden: StateFlow<Boolean> = _chromeHidden.asStateFlow()

    fun setChromeHidden(hidden: Boolean) {
        if (_chromeHidden.value != hidden) _chromeHidden.value = hidden
    }

    /**
     * Where you were and what you had on, from the two apps that know.
     *
     * Asked on arrival like everything else read across an app boundary: both are written while you
     * are somewhere else entirely, so there is no moment in this process worth watching for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayPlaces: StateFlow<List<DayTimeline.Item.Place>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.stays(getApplication(), day, zone).map { stay ->
                        DayTimeline.Item.Place(
                            startMinutes = JournalDay.minutesInto(stay.startMs, day, zone),
                            endMinutes = JournalDay.minutesInto(stay.endMs, day, zone),
                            latitude = stay.latitude,
                            longitude = stay.longitude,
                            // Naming a coordinate has no offline source on this phone; the nightly
                            // lookup fills this in later.
                            name = null,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayListening: StateFlow<List<DayTimeline.Item.Listening>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val plays = DayBridges.plays(getApplication(), day, zone).map { play ->
                        JournalDay.minutesInto(play.atMs, day, zone) to
                            play.artist.ifBlank { play.title }
                    }
                    DayTimeline.listening(plays)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which visible days the other apps have evidence for, and between what times.
     *
     * The planner needs to know a day *happened* without needing to know what happened on it. Fed
     * into the activity line, so a day you went somewhere and put a record on but wrote nothing down
     * stops looking like an empty square.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val bridgeSpans: StateFlow<Map<Long, IntRange>> =
        combine(_canvasWindow, _photoNudge) { window, _ -> window }
            .mapLatest { window ->
                withContext(Dispatchers.IO) {
                    DayBridges.spans(getApplication(), window.first, window.last)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /* ---- what the phone itself noticed ---- */

    private val steps = StepStore(getApplication())

    /**
     * The day's own numbers.
     *
     * Steps are nullable and screen use is not, and the difference is honest rather than untidy: the
     * usage events are **retroactive**, so any past day can be answered, while the step counter
     * remembers nothing and a day before this app was installed is permanently unknowable. A zero
     * there would read as "you did not move".
     */
    data class DayStats(
        val steps: Int?,
        /** Steps by hour since local midnight — a walk is a spike, not a total. */
        val stepHours: List<Int>,
        val use: ScreenUse.Result,
        val usageGranted: Boolean,
        val stepsGranted: Boolean,
        val stepsEverRecorded: Boolean,
        /** When the phone was picked up, as minutes into the journal day. */
        val pickupMinutes: List<Int> = emptyList(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayStats: StateFlow<DayStats> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val window = PhotoDays.windowMs(day, day, zone)
                    DayStats(
                        steps = steps.stepsOn(day),
                        stepHours = steps.hoursOn(day),
                        use = DeviceUse.forDay(getApplication(), window.first, window.last + 1),
                        pickupMinutes = DeviceUse
                            .pickupsForDay(getApplication(), window.first, window.last + 1)
                            .map { JournalDay.minutesInto(it, day, zone) },
                        usageGranted = DeviceUse.granted(getApplication()),
                        stepsGranted = steps.granted(),
                        stepsEverRecorded = steps.everRecorded(),
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DayStats(null, emptyList(), ScreenUse.EMPTY, false, false, false, emptyList()),
            )

    /**
     * Read the step counter and fold in whatever has happened since the last reading.
     *
     * Called on arrival, because the counter is cumulative: one sample carries everything since the
     * last one, so opening the app is enough to keep the days filled in without a service running.
     */
    fun sampleSteps() = steps.sample { _photoNudge.value += 1 }

    /**
     * Re-read the library.
     *
     * Clears the thumbnail cache as well, so a photograph deleted in Roll stops being drawn
     * here — a cached bitmap outlives the row it came from, and a filmstrip full of pictures
     * that no longer exist is worse than one that is briefly empty.
     */
    fun refreshPhotos() {
        _photosGranted.value = PhotoLibrary.granted(getApplication())
        PhotoLibrary.clearCache()
        // The bridges are cached per day; the same nudge that re-reads photographs re-reads them.
        DayBridges.forget()
        _photoNudge.value += 1
    }

    /**
     * Files a photograph against a day, as an entry that carries it.
     *
     * The entry is what makes it *yours* rather than merely something the phone happens to
     * hold: it can be given a time, a reminder, or moved to another day like anything else on
     * the planner. `DayEntryEntity.imagePath` already existed for a photographed page, so
     * this needed no schema change — the string is a `content://` uri here rather than a file
     * path, which is why everything that renders it has to accept both.
     */
    fun attachPhotoToDay(photo: DevicePhoto, text: String) = viewModelScope.launch {
        repo.addDayEntry(
            epochDay = photo.epochDay,
            text = text.ifBlank { "Photo" },
            startMinutes = photo.minutesOfDay(ZoneId.systemDefault()),
            fromPhoto = true,
            imagePath = photo.uri.toString(),
        )
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
            // you wrote a time on is the point of writing the time — unless it has already
            // happened, in which case there is nothing left to count back from and the entry
            // would carry an alarm glyph for a reminder that can never fire. Writing down a
            // day that has gone is a diary, not a plan.
            val behind = DayTimeline.behind(
                epochDay = epochDay,
                minutes = startMinutes,
                today = NoteDates.today(),
                nowMinutes = NoteDates.nowMinutes(),
            )
            val entry = repo.addDayEntry(
                epochDay = epochDay,
                text = text,
                startMinutes = startMinutes,
                systemEventId = eventId,
                reminderMinutes = if (behind) null else repo.defaultReminderMinutes(),
            )
            Reminders.schedule(getApplication(), entry)
        }

    /** How many days an entry covers. Null, or a day at or before the start, ends the span. */
    fun setEntrySpan(entry: DayEntryEntity, endEpochDay: Long?) = viewModelScope.launch {
        repo.setEntrySpan(entry, endEpochDay)
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

    fun readCapture(file: File, rotationDegrees: Int = 0) {
        _capture.value = CaptureState.Reading
        val key = _apiKey.value
        val mode = pendingMode
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ImageUtils.prepareForUpload(file, rotationDegrees)
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

    /**
     * One entry as a row **on a particular day**, which matters once entries can span several.
     *
     * A span appears on every day it covers, so the row's `epochDay` is the day being shown rather
     * than the day the entry began — and the id is namespaced with it, because otherwise a five-day
     * trip is five rows sharing one key and a LazyColumn throws on the duplicate.
     *
     * Only the first day keeps the time: a trip starting at 09:40 on Monday did not also start at
     * 09:40 on Tuesday, and drawing it at that position on the planner every day would be a lie.
     * The later days are all-day, which is what they are.
     */
    private fun DayEntryEntity.toAgendaRow(calendars: List<CalendarEntity>, onDay: Long = epochDay) =
        AgendaRow(
            // Namespaced so an entry and a ticket can never collide on a list key, and so the same
            // span on two days is two distinct rows.
            id = if (onDay == epochDay) "entry:$id" else "entry:$id@$onDay",
            epochDay = onDay,
            minutes = if (onDay == epochDay) startMinutes else null,
            title = text,
            label = calendars.firstOrNull { it.id == calendarId }?.label,
            reminderMinutes = if (onDay == epochDay) reminderMinutes else null,
            entryId = id,
            dayOfSpan = (onDay - epochDay).toInt() + 1,
            spanDays = (lastDay - epochDay).toInt() + 1,
        )

    /**
     * A window's entries, with a span appearing on each day it covers.
     *
     * The queries return an entry once, however many days it spans — one row is one thing that is
     * happening. Fanning it out is a view concern and belongs here, clipped to the window so a
     * fortnight's trip does not produce fourteen rows for a screen showing three of them.
     */
    private fun List<DayEntryEntity>.acrossDays(
        from: Long,
        to: Long,
        calendars: List<CalendarEntity>,
    ): List<AgendaRow> = flatMap { entry ->
        val first = maxOf(entry.epochDay, from)
        val last = minOf(entry.lastDay, to)
        if (last < first) emptyList() else (first..last).map { entry.toAgendaRow(calendars, it) }
    }

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
