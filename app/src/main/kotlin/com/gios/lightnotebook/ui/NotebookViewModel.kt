package com.gios.lightnotebook.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.gios.lightnotebook.ai.ParsedEvent
import com.gios.lightnotebook.ai.ReadMode
import com.gios.lightnotebook.ai.Vision
import com.gios.lightnotebook.ai.VisionParser
import com.gios.lightnotebook.data.CalendarEntity
import com.gios.lightnotebook.data.RecorderLink
import com.gios.lightnotebook.data.CalendarFeed
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.DeviceCalendar
import com.gios.lightnotebook.data.DeviceCalendars
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.DayBridges
import com.gios.lightnotebook.data.LightDocs
import com.gios.lightnotebook.data.DayWeather
import com.gios.lightnotebook.data.DeviceUse
import com.gios.lightnotebook.data.FolderEntity
import com.gios.lightnotebook.data.ImportResult
import com.gios.lightnotebook.data.LightPassBridge
import com.gios.lightnotebook.data.NoteEntity
import com.gios.lightnotebook.data.NotebookRepository
import com.gios.lightnotebook.data.PassShowing
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.lightnotebook.data.Places
import com.gios.lightnotebook.data.RollStars
import com.gios.lightnotebook.data.StepStore
import com.gios.lightnotebook.data.Sync
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.data.Weather
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.report.Trouble
import com.gios.lightnotebook.notify.CalendarSyncWorker
import com.gios.lightnotebook.notify.SyncAlarm
import com.gios.lightnotebook.notify.WeatherArchiveWorker
import com.gios.lightnotebook.data.CallHistory
import com.gios.lightnotebook.data.ChargeStore
import com.gios.lightnotebook.util.AppUse
import com.gios.lightnotebook.util.Charging
import com.gios.lightnotebook.util.Agenda
import com.gios.lightnotebook.util.AgendaRow
import com.gios.lightnotebook.util.Recurrence
import com.gios.lightnotebook.util.DayTimeline
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.Daylight
import com.gios.lightnotebook.util.CalendarUrl
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

    /**
     * Every repeating entry, whatever day it started on.
     *
     * Held whole rather than queried per screen because the day queries cannot find a series:
     * they match on the stored `epochDay`, which is only ever the *first* occurrence. These rows
     * are expanded into days as each screen asks, bounded by that screen's own window.
     */
    private val recurringEntries: StateFlow<List<DayEntryEntity>> = repo.observeRecurring()
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
            recurringEntries,
            dayShowings,
            repo.observeCalendars(),
            _selectedDay,
        ) { entries, repeats, showings, calendars, day ->
            Agenda.merge(
                Agenda.collapse(
                    // Clipped to the one day, so a span becomes exactly one row saying which day of
                    // it this is rather than one row per day of the trip. A series is dropped from
                    // the plain list and put back by expansion, which is what produces its first
                    // occurrence too — otherwise the day it starts on would show it twice.
                    entries = entries.filterNot { it.repeats }.acrossDays(day, day, calendars) +
                        repeats.occurrencesIn(day, day, calendars),
                    films = showings.map { it.toAgendaRow() },
                ),
                Agenda.holidayRows(day, day),
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
            recurringEntries,
            _showings,
            repo.observeCalendars(),
        ) { entries, repeats, showings, calendars ->
            val today = NoteDates.today()
            Agenda.merge(
                Agenda.collapse(
                    entries = entries.filterNot { it.repeats }.map { it.toAgendaRow(calendars) } +
                        // A horizon in days, not in rows: `observeUpcoming` cannot see a series at
                        // all, since its stored day is usually behind us, and expanding "sixty
                        // rows" out of a rule is not a question the rule can answer.
                        repeats.occurrencesIn(today, today + REPEAT_HORIZON_DAYS, calendars),
                    films = showings.filter { it.epochDay >= today }.map { it.toAgendaRow() },
                ),
                // The same horizon the agenda already shows, so a holiday appears exactly when
                // the entries around it do.
                Agenda.holidayRows(today, today + AGENDA_HORIZON_DAYS),
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
                recurringEntries,
                _showings,
                repo.observeCalendars(),
            ) { entries, repeats, showings, calendars ->
                val films = showings.filter { it.epochDay in window }
                // Holidays are merged after the collapse rather than inside it: collapse folds
                // a ticket onto the entry describing the same plan, and a holiday is never a
                // duplicate of anything.
                Agenda.merge(
                    Agenda.collapse(
                        entries = entries.filterNot { it.repeats }
                            .acrossDays(window.first, window.last, calendars) +
                            repeats.occurrencesIn(window.first, window.last, calendars),
                        films = films.map { it.toAgendaRow() },
                    ),
                    Agenda.holidayRows(window.first, window.last),
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
                    withContext(Dispatchers.IO) {
                        PhotoLibrary.photosOn(
                            getApplication(),
                            day,
                            starred = RollStars.names(getApplication()),
                        )
                    }
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

    /**
     * Fetch the weather now, because someone asked.
     *
     * The only place in the app that starts a network fetch from a tap. It still goes through the
     * worker rather than doing it here: the work is identical, and a job survives the screen being
     * closed halfway through a hundred days of archive.
     */
    fun fetchWeather(everything: Boolean) {
        WeatherArchiveWorker.runNow(getApplication(), refetchEverything = everything)
    }

    /**
     * What the by-hand fetch is doing, in words, so the button is not a no-op with a background job
     * behind it.
     *
     * Watched through WorkManager rather than tracked here, because the job outlives this view model
     * — leaving Settings mid-fetch and coming back should still show it running, and a flag in
     * memory could not.
     */
    val weatherStatus: StateFlow<String?> =
        WorkManager.getInstance(getApplication<Application>())
            .getWorkInfosForUniqueWorkFlow(WeatherArchiveWorker.NOW_NAME)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> "WAITING FOR A NETWORK"
                    WorkInfo.State.RUNNING -> "FETCHING…"
                    WorkInfo.State.SUCCEEDED -> {
                        val added = info.outputData.getInt(WeatherArchiveWorker.KEY_DAYS_ADDED, 0)
                        val named = info.outputData.getInt(WeatherArchiveWorker.KEY_PLACES_NAMED, 0)
                        val parts = buildList {
                            if (added > 0) add("$added DAYS")
                            if (named > 0) add("$named PLACES NAMED")
                        }
                        when {
                            parts.isNotEmpty() -> "ADDED " + parts.joinToString(", ")
                            // Nothing added is the common and correct outcome of asking twice, and
                            // saying so is the difference between "done" and "did that work?".
                            else -> "NOTHING MISSING"
                        }
                    }
                    // Retrying, not broken: no network yet, and it will try again on its own.
                    WorkInfo.State.FAILED -> "COULDN'T REACH THE SERVICE"
                    WorkInfo.State.BLOCKED -> "WAITING"
                    WorkInfo.State.CANCELLED -> null
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Re-read everything another app owns, and ask for any weather still missing.
     *
     * What pull-to-refresh on a day does. The bridges are a handful of file reads; the weather goes
     * through the worker because it is a network call and should survive the screen closing.
     */
    fun refreshEverything() {
        refreshPhotos()
        refreshShowings()
        sampleSteps()
        fetchWeather(everything = false)
    }

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
                    val places = Places(getApplication())
                    DayBridges.stays(getApplication(), day, zone).map { stay ->
                        DayTimeline.Item.Place(
                            startMinutes = JournalDay.minutesInto(stay.startMs, day, zone),
                            endMinutes = JournalDay.minutesInto(stay.endMs, day, zone),
                            latitude = stay.latitude,
                            longitude = stay.longitude,
                            // From the cache only. Naming a coordinate needs a network and a screen
                            // never waits on one — the nightly job fills these in, so a stay reads
                            // "Somewhere" until it has been looked at once.
                            name = places.cached(stay.latitude, stay.longitude),
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

    /**
     * Going home, going to work.
     *
     * From LightFog, which records that you arrived at a place you had named without recording where
     * that place is — see its privacy zones. So this has a time and a word and nothing else, which
     * is the whole of what a journal needs from it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayArrivals: StateFlow<List<DayTimeline.Item.Arrived>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.arrivals(getApplication(), day, zone).map {
                        DayTimeline.Item.Arrived(
                            minutes = JournalDay.minutesInto(it.atMs, day, zone),
                            zone = it.name,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Calls on the open day, from the system call log.
     *
     * Queried per day rather than kept: the provider holds weeks, so nothing has to have been
     * running. Empty and silent without the grant, like every other bridge here.
     */
    /**
     * What you recorded, from BrightRecorder.
     *
     * Read on arrival like every other bridge: a recording is made while this app is closed, so
     * there is no moment in this process worth watching for. The nudge that re-reads photographs
     * re-reads these too, which is what makes a clip recorded a minute ago appear when you come
     * back to the day.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayRecordings: StateFlow<List<DayTimeline.Item.Recorded>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.recordings(getApplication(), day, zone).map { clip ->
                        DayTimeline.Item.Recorded(
                            minutes = JournalDay.minutesInto(clip.startedAt, day, zone),
                            title = clip.title,
                            // The place is the name you typed on the tape, and the title already
                            // contains it plus the date — so the row shows the place and lets the
                            // timeline supply the time it is already sitting next to.
                            place = clip.place,
                            seconds = clip.seconds,
                            tapeDir = clip.tapeDir,
                            file = clip.file,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Light's own notes and voice notes, if this app has been pointed at `Documents/`.
     *
     * Read on arrival, like every other source that lives outside this app. Empty and silent
     * without the folder grant — a feature nobody has switched on should look like a feature
     * nobody has switched on, not like a broken one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayLightNotes: StateFlow<List<DayTimeline.Item.LightNote>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val tree = repo.lightDocsTree()?.let { android.net.Uri.parse(it) }
                    LightDocs.docs(getApplication(), tree, day, zone)
                        .filter { it.kind != LightDocs.Kind.Other }
                        .map { doc ->
                            DayTimeline.Item.LightNote(
                                minutes = JournalDay.minutesInto(doc.atMs, day, zone),
                                name = doc.name,
                                voice = doc.kind == LightDocs.Kind.Voice,
                                uri = doc.uri.toString(),
                            )
                        }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Where Light's own notes are, once somebody has pointed at the folder. */
    private val _lightDocs = MutableStateFlow(repo.lightDocsTree())
    val lightDocsTree: StateFlow<String?> = _lightDocs.asStateFlow()

    /**
     * Open one of Light's own files in whatever handles it.
     *
     * The URI is re-parsed rather than carried as one: the row survives a process death and a
     * `Uri` does not, and a row that cannot be tapped after the app was backgrounded is a row that
     * looks broken.
     */
    fun openLightDoc(context: android.content.Context, item: DayTimeline.Item.LightNote) {
        val uri = runCatching { android.net.Uri.parse(item.uri) }.getOrNull() ?: return
        LightDocs.open(
            context,
            LightDocs.Doc(
                name = item.name,
                atMs = 0L,
                kind = if (item.voice) LightDocs.Kind.Voice else LightDocs.Kind.Note,
                uri = uri,
            ),
        )
    }

    /**
     * Back to the recording itself.
     *
     * A row on the day is a fact about half past two; the clip is a thing you can listen to, and it
     * lives in the recorder. See [RecorderLink] for the link, and for what happens on a phone whose
     * recorder is too old to answer it.
     */
    fun openRecording(context: android.content.Context, item: DayTimeline.Item.Recorded) {
        RecorderLink.openClip(context, tapeDir = item.tapeDir, fileName = item.file)
    }

    /** Called with whatever the folder picker returned. */
    fun setLightDocsTree(uri: android.net.Uri?) {
        if (uri == null) {
            repo.setLightDocsTree(null)
            _lightDocs.value = null
            return
        }
        LightDocs.remember(getApplication(), uri)
        repo.setLightDocsTree(uri.toString())
        _lightDocs.value = uri.toString()
        // The day is redrawn off the same nudge photographs use, so the notes appear without
        // leaving the screen.
        refreshPhotos()
    }

    /** Where you went, from BrightWay. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayWent: StateFlow<List<DayTimeline.Item.Went>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.trips(getApplication(), day, zone).map { trip ->
                        DayTimeline.Item.Went(
                            minutes = JournalDay.minutesInto(trip.startedMs, day, zone),
                            place = trip.name,
                            walking = trip.walking,
                            tookMinutes = trip.minutes,
                            arrived = trip.arrived,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What you read, from LightBooks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayRead: StateFlow<List<DayTimeline.Item.Read>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.reading(getApplication(), day, zone).map { sitting ->
                        DayTimeline.Item.Read(
                            minutes = JournalDay.minutesInto(sitting.startedMs, day, zone),
                            title = sitting.title,
                            author = sitting.author,
                            advanced = sitting.advanced,
                            pages = sitting.pages,
                            tookMinutes = sitting.minutes,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayCalls: StateFlow<List<DayTimeline.Item.Called>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val window = JournalDay.windowMs(day, zone)
                    CallHistory.forWindow(getApplication(), window.first, window.last).map { call ->
                        DayTimeline.Item.Called(
                            minutes = JournalDay.minutesInto(call.atMs, day, zone),
                            call = call,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Time on the charger for the open day.
     *
     * The only recorded thing on this screen — Android keeps no history of plug and unplug, so
     * [ChargeReceiver] writes them as they happen and this reads them back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayCharges: StateFlow<List<DayTimeline.Item.Charged>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val window = JournalDay.windowMs(day, zone)
                    val store = ChargeStore(getApplication())
                    Charging.spansIn(
                        events = store.eventsAround(window.first, window.last),
                        windowStartMs = window.first,
                        windowEndMs = window.last,
                    ).map { span ->
                        DayTimeline.Item.Charged(
                            minutes = JournalDay.minutesInto(span.fromMs, day, zone),
                            untilMinutes = JournalDay.minutesInto(span.untilMs, day, zone),
                            startedEarlier = span.startedEarlier,
                            stillGoing = span.stillGoing,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Who you talked to on the open day. Names only; LightChat serves no message text. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dayTalked: StateFlow<List<DayTimeline.Item.Talked>> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    DayBridges.talked(getApplication(), day, zone).map { talked ->
                        DayTimeline.Item.Talked(
                            minutes = JournalDay.minutesInto(talked.firstMs, day, zone),
                            untilMinutes = JournalDay.minutesInto(talked.lastMs, day, zone),
                            name = talked.name,
                            isGroup = talked.isGroup,
                            messages = talked.messages,
                            theyReplied = talked.theyReplied,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val weather = Weather(getApplication())

    /**
     * The weather on the open day, from the archive alone.
     *
     * No network here, ever — see [Weather.cached] and the nightly worker. A day with nothing
     * archived says nothing about the weather, which is the right answer for a day nobody has
     * prepared yet.
     */
    val dayWeather: StateFlow<DayWeather?> = _selectedDay
        .map { day -> weather.cached(day, day)[day] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** And for every visible day on the planner. Also cache-only. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val weatherByDay: StateFlow<Map<Long, DayWeather>> = _canvasWindow
        .mapLatest { window ->
            withContext(Dispatchers.IO) { weather.cached(window.first, window.last) }
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
        /**
         * Where the screen time went, biggest first, already named.
         *
         * Named here rather than in the composable because resolving a label is a
         * `PackageManager` call, and doing eleven of them on the main thread while a day is
         * being drawn is exactly the sort of thing this screen is careful about elsewhere.
         */
        val apps: List<String> = emptyList(),
        /**
         * The same query in long form: every app worth a minute, for the section at the end of a
         * day. [apps] is the three-word version that rides along with the day's own numbers.
         */
        val appTime: List<AppUse.Slice> = emptyList(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayStats: StateFlow<DayStats> =
        combine(_selectedDay, _photoNudge) { day, _ -> day }
            .mapLatest { day ->
                withContext(Dispatchers.IO) {
                    val zone = ZoneId.systemDefault()
                    val window = PhotoDays.windowMs(day, day, zone)
                    // One walk over the usage events answers screen time, pickups and where the
                    // time went; it used to be three queries over the same window.
                    val use = DeviceUse.dayUse(getApplication(), window.first, window.last + 1)
                    DayStats(
                        steps = steps.stepsOn(day),
                        stepHours = steps.hoursOn(day),
                        use = use.screen,
                        pickupMinutes = use.pickupsMs.map { JournalDay.minutesInto(it, day, zone) },
                        usageGranted = DeviceUse.granted(getApplication()),
                        apps = AppUse.summary(
                            totals = use.apps,
                            nameOf = { DeviceUse.labelFor(getApplication(), it) },
                        ),
                        appTime = AppUse.breakdown(
                            totals = use.apps,
                            nameOf = { DeviceUse.labelFor(getApplication(), it) },
                        ),
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

    /**
     * Looks an entry back up for its own sheet, since a row only carries the id.
     *
     * The repeating rows are searched too: a series' fourth Tuesday is drawn on a day the day
     * query knows nothing about, and without this every occurrence but the first would be a row
     * that could not be tapped.
     */
    fun entryById(id: String?): DayEntryEntity? = id?.let { wanted ->
        dayEntries.value.firstOrNull { it.id == wanted }
            ?: recurringEntries.value.firstOrNull { it.id == wanted }
    }

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

    /**
     * Where an entry is, as words.
     *
     * Blank clears it, the same as every other optional field here. Nothing is parsed or checked:
     * "moms" is a location, and the only thing downstream of this is a maps search which is built
     * for whatever somebody types.
     */
    fun setEntryLocation(entry: DayEntryEntity, location: String?) = viewModelScope.launch {
        val trimmed = location?.trim()?.takeIf { it.isNotBlank() }
        repo.updateDayEntry(entry.copy(location = trimmed))
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

    /**
     * When it ends, on the same day.
     *
     * Refused when it is not after the start, rather than stored and left to every downstream
     * reader to notice: an end before its own beginning makes a length negative, and the timeline
     * draws lengths.
     */
    fun setEntryEnd(entry: DayEntryEntity, endMinutes: Int?) = viewModelScope.launch {
        val start = entry.startMinutes ?: return@launch
        repo.updateDayEntry(entry.copy(endMinutes = endMinutes?.takeIf { it > start }))
    }

    /**
     * One entry, watched.
     *
     * The editor needs to redraw as each row writes through, and it holds an id rather than an
     * entity for exactly that reason — an entity captured when the screen opened would be one edit
     * stale from the first row anybody touched. Null once the entry is gone, which the screen reads
     * as "leave".
     */
    fun entryFlow(id: String): StateFlow<DayEntryEntity?> =
        entryFlows.getOrPut(id) {
            repo.dayEntryFlow(id)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    private val entryFlows = HashMap<String, StateFlow<DayEntryEntity?>>()

    /**
     * A new event, made for the editor rather than for the day.
     *
     * Created and saved immediately, because the editor works on a real entry — see
     * [com.gios.lightnotebook.ui.EventEditorScreen]. An untitled event abandoned in there is
     * deleted by its own Delete row, which is where anybody would look for it.
     */
    fun startEvent(epochDay: Long, text: String, onReady: (String) -> Unit) =
        viewModelScope.launch {
            val entry = repo.addDayEntry(
                epochDay = epochDay,
                text = text.trim().ifBlank { "New event" },
            )
            onReady(entry.id)
        }

    /**
     * Sets or clears an entry's repeat rule, and re-arms its reminder against the new schedule.
     */
    fun setEntryRepeat(entry: DayEntryEntity, rrule: String?) = viewModelScope.launch {
        val updated = repo.setRepeat(entry, rrule)
        Reminders.schedule(getApplication(), updated)
    }

    /**
     * "Delete just this one": one occurrence is taken out and the series carries on.
     *
     * The copy in the phone's own calendar is left alone. This app mirrors a single event, never
     * a rule, so there is nothing over there that knows what an occurrence is; deleting the
     * mirrored event would remove the whole series from the other calendar to hide one Tuesday
     * here.
     */
    fun skipOccurrence(entry: DayEntryEntity, epochDay: Long) = viewModelScope.launch {
        val updated = repo.skipOccurrence(entry, epochDay)
        Reminders.schedule(getApplication(), updated)
    }

    /** "Edit just this one": the occurrence leaves the series, and [onDetached] gets the copy. */
    fun detachOccurrence(
        entry: DayEntryEntity,
        epochDay: Long,
        onDetached: (DayEntryEntity) -> Unit,
    ) = viewModelScope.launch {
        val detached = repo.detachOccurrence(entry, epochDay)
        Reminders.schedule(getApplication(), detached)
        onDetached(detached)
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
                    val events = IcsParser.parse(text, repo.calendarZone())
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

    /**
     * Subscribes to a calendar feed URL — the work-calendar path: something else holds the
     * account and publishes an .ics, and this fetches it now and every hour after.
     *
     * The first fetch happens here rather than being left to the alarm, so a wrong URL says
     * so while the scanner is still in your hand instead of failing silently overnight.
     */
    fun importUrl(payload: String) = viewModelScope.launch {
        val url = CalendarUrl.feedIn(payload)
        if (url == null) {
            _importStatus.value = "That doesn't look like a calendar address."
            return@launch
        }
        _importStatus.value = "Fetching\u2026"
        val outcome = withContext(Dispatchers.IO) {
            val text = CalendarFeed.fetch(url)
            when {
                text == null -> null to "Could not reach that address."
                !IcsParser.looksLikeIcs(text) -> null to "That address is not a calendar."
                else -> {
                    val events = IcsParser.parse(text, repo.calendarZone())
                    if (events.isEmpty()) {
                        null to "That calendar has no events."
                    } else {
                        repo.importEvents(
                            // A feed has no filename to borrow, so it names itself if it can.
                            label = IcsParser.calendarName(text) ?: CalendarUrl.labelFor(url),
                            kind = CalendarEntity.KIND_URL,
                            sourceRef = url,
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
            result.failed > 0 && result.calendars == 0 -> {
                Trouble.record("reach any calendar", "${result.failed} of them, none answered")
                "Could not reach any calendar. The file may have moved."
            }
            result.failed > 0 ->
                "Refreshed ${result.calendars}, could not reach ${result.failed}."
            else -> "Refreshed ${result.calendars} calendar(s), ${result.events} event(s)."
        }
    }

    /** Arms the hourly refresh. Called at launch; boot does it too. */
    fun scheduleSync() {
        // Work, not an alarm: the old hourly `setAndAllowWhileIdle` woke the phone even with no
        // network and no calendars imported. Cancelling the alarm here retires it on upgrade —
        // an alarm nobody cancels outlives the code that armed it.
        SyncAlarm.cancel(getApplication())
        CalendarSyncWorker.schedule(getApplication())
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

    private val _calendarZone = MutableStateFlow(repo.calendarZoneId())

    /** The chosen zone for imported times, or null while the phone's own is trusted. */
    val calendarZone: StateFlow<String?> = _calendarZone.asStateFlow()

    /** What the phone says it is, which is worth showing because it is sometimes wrong. */
    val deviceZone: String get() = ZoneId.systemDefault().id

    /** Zones offered before typing one: where he is, where the company is, and the neutral one. */
    val zoneChoices: List<String> = listOf(
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles",
        "Europe/Paris",
        "Europe/London",
        "UTC",
    )

    /**
     * Sets the zone and re-reads every subscribed calendar, because the stored rows were
     * converted with the old one.
     *
     * Without the re-sync the setting would appear to do nothing until the next hourly pass —
     * which is exactly when somebody would decide it was broken and change it again. False when
     * the id is not a zone this platform knows.
     */
    fun setCalendarZone(id: String?): Boolean {
        if (!repo.setCalendarZone(id)) return false
        _calendarZone.value = repo.calendarZoneId()
        syncNow()
        return true
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
                    // The screen says so too; this is what turns "it didn't work" into a
                    // report with the model's own reason attached.
                    Trouble.record("read that page", result.reason)
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
            location = location,
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

    /**
     * A window's repeating entries, as one row per occurrence per day covered.
     *
     * The window is widened backwards by the length of each series' span before the rule is
     * expanded, or a fortnight's repeating trip would be invisible on every day but its first —
     * the same reasoning `observeRange` uses overlap rather than containment for.
     */
    private fun List<DayEntryEntity>.occurrencesIn(
        from: Long,
        to: Long,
        calendars: List<CalendarEntity>,
    ): List<AgendaRow> = flatMap { master ->
        val spanLength = master.lastDay - master.epochDay
        Recurrence.expand(
            rrule = master.rrule,
            startDay = master.epochDay,
            from = from - spanLength,
            to = to,
            exDays = Recurrence.parseExDays(master.exDays),
        ).flatMap { occurrence ->
            val first = maxOf(occurrence, from)
            val last = minOf(occurrence + spanLength, to)
            if (last < first) {
                emptyList()
            } else {
                (first..last).map { day ->
                    master.toOccurrenceRow(calendars, occurrence, day, spanLength)
                }
            }
        }
    }

    /**
     * One occurrence of a series, on one of the days it covers.
     *
     * Keyed by the occurrence and not by the entry: a `LazyColumn` throws on a repeated key, and
     * a weekly meeting drawn across six weeks of planner is six rows with one entry id behind
     * them. The occurrence day travels on the row so the action sheet knows which Tuesday it is
     * being asked about.
     */
    private fun DayEntryEntity.toOccurrenceRow(
        calendars: List<CalendarEntity>,
        occurrence: Long,
        onDay: Long,
        spanLength: Long,
    ) = AgendaRow(
        id = "entry:$id@$occurrence" + if (onDay == occurrence) "" else "+$onDay",
        epochDay = onDay,
        minutes = if (onDay == occurrence) startMinutes else null,
        title = text,
        label = calendars.firstOrNull { it.id == calendarId }?.label,
        location = location,
        reminderMinutes = if (onDay == occurrence) reminderMinutes else null,
        entryId = id,
        dayOfSpan = (onDay - occurrence).toInt() + 1,
        spanDays = spanLength.toInt() + 1,
        occurrenceDay = occurrence,
    )

    private fun PassShowing.toAgendaRow() = AgendaRow(
        id = "pass:$passId",
        epochDay = epochDay,
        minutes = startMinutes,
        title = title,
        label = where,
        // A cinema is a location like any other, so a ticket gets directions for free — the venue
        // LightPass already knows is exactly the string a maps search wants.
        location = where,
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

        /**
         * How far ahead the agenda lists holidays.
         *
         * [UPCOMING_LIMIT] is a count of rows, which says nothing about how many days they
         * cover, so holidays need a horizon of their own. A year means the agenda always knows
         * about the next Christmas without ever computing more than eleven dates.
         */
        const val AGENDA_HORIZON_DAYS = 365L

        /**
         * How far ahead the agenda expands a repeating entry.
         *
         * Shorter than the holiday horizon deliberately: a daily rule over a year is 365 rows
         * built to show perhaps ten, and the agenda is a list of what is next rather than a
         * printout of the year. Four months is far enough that a monthly series always has its
         * next few occurrences in it.
         */
        const val REPEAT_HORIZON_DAYS = 120L
    }
}
