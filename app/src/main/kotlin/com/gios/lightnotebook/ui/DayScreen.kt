package com.gios.lightnotebook.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.Directions
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.light.common.hw.WheelScroll
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightDayGestures
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.DayLayout
import com.gios.lightnotebook.util.DayTimeline
import com.gios.lightnotebook.util.Charging
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.ChromeScroll
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.Recurrence
import kotlinx.coroutines.delay
import java.time.ZoneId
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * One day. Anything can go on it: a line of text, or a line of text with a time in
 * front of it — typing "9:30 dentist" is enough, so there is no time picker to wade
 * through, and a timed entry is given the default reminder without being asked.
 */
@Composable
fun DayScreen(
    vm: NotebookViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    // Opened from a reminder rather than from the planner, so there is no surface to slide:
    // a horizontal drag steps the day once it passes half the screen.
    var slid by remember { mutableStateOf(0f) }
    val step = with(LocalDensity.current) { STANDALONE_STEP_DP.dp.toPx() }
    DayPane(
        vm = vm,
        onClose = onBack,
        onOpenNote = onOpenNote,
        onOpenEvent = onOpenEvent,
        gestures = Modifier.lightDayGestures(
            onSlide = { dx ->
                slid += dx
                if (slid <= -step) {
                    vm.stepDay(1)
                    slid = 0f
                } else if (slid >= step) {
                    vm.stepDay(-1)
                    slid = 0f
                }
            },
            onSlideEnd = { slid = 0f },
            onPinchOut = onBack,
        ),
    )
}

private const val STANDALONE_STEP_DP = 72

/**
 * The day itself, as a pane rather than a screen.
 *
 * The same composable serves two callers: the route a tapped reminder opens, and the
 * zoomed-in cell on the planner, where it is drawn *into the cell* and grown to fill the
 * screen. That is why it takes no bar of its own beyond a header — it may be a cell one
 * moment and the whole display the next.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPane(
    vm: NotebookViewModel,
    onClose: () -> Unit,
    /** A note written or returned to on this day opens it, the same as from the notes list. */
    onOpenNote: (String) -> Unit,
    /**
     * An entry opened in full: where, repeats, alert. A page, because four tabs of rows is not
     * something to swipe up over a day. See [EventEditorScreen].
     */
    onOpenEvent: (String) -> Unit,
    /**
     * The gestures that move between days. Supplied by the planner when the pane is a cell on
     * it, so that sliding pans the actual surface; the standalone route passes its own.
     */
    gestures: Modifier = Modifier,
) {
    // The open day comes from the view model, not from a route argument: sliding moves it, and
    // pushing a screen on the stack for every day you flick past would be absurd.
    val epochDay by vm.selectedDay.collectAsStateWithLifecycle()
    val rows by vm.dayRows.collectAsStateWithLifecycle()
    var draft by remember(epochDay) { mutableStateOf("") }
    var editing by remember { mutableStateOf<DayEntryEntity?>(null) }
    var actionsFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    // Which occurrence of a series the open sheet is about. A series is one row in the database
    // and many on screen, so the entry alone cannot say which Tuesday was tapped.
    var actionsOn by remember { mutableStateOf<Long?>(null) }
    var repeatingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    // A scope question waiting for an answer: this one occurrence, or the whole series?
    var scopeFor by remember { mutableStateOf<Pair<DayEntryEntity, SeriesAction>?>(null) }
    var remindingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var timingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var movingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var spanningFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    var attaching by remember { mutableStateOf<DevicePhoto?>(null) }
    var viewing by remember { mutableStateOf<DevicePhoto?>(null) }

    val photos by vm.dayPhotos.collectAsStateWithLifecycle()
    val dayNotes by vm.dayNotes.collectAsStateWithLifecycle()
    val past by vm.onThisDay.collectAsStateWithLifecycle()
    val stats by vm.dayStats.collectAsStateWithLifecycle()
    val places by vm.dayPlaces.collectAsStateWithLifecycle()
    val listening by vm.dayListening.collectAsStateWithLifecycle()
    val weather by vm.dayWeather.collectAsStateWithLifecycle()
    val talked by vm.dayTalked.collectAsStateWithLifecycle()
    val arrivals by vm.dayArrivals.collectAsStateWithLifecycle()
    val calls by vm.dayCalls.collectAsStateWithLifecycle()
    val charges by vm.dayCharges.collectAsStateWithLifecycle()
    val recordings by vm.dayRecordings.collectAsStateWithLifecycle()
    val caught by vm.dayCaught.collectAsStateWithLifecycle()
    val lightNotes by vm.dayLightNotes.collectAsStateWithLifecycle()
    val went by vm.dayWent.collectAsStateWithLifecycle()
    val read by vm.dayRead.collectAsStateWithLifecycle()

    // Where an entry is, and where to send it. The sheet appears when more than one thing on the
    // phone can navigate; with exactly one, the tap goes straight there — a chooser with a single
    // row is a question with one answer.
    val context = androidx.compose.ui.platform.LocalContext.current
    var locatingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var navigatingTo by remember { mutableStateOf<String?>(null) }
    val photosGranted by vm.photosGranted.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    WheelScroll(listState)

    // The bars get out of the way as you read down the day and come back the moment you reach for
    // them by scrolling up. The rule, including why the last pixel of the list is an exception to
    // it, lives in ChromeScroll where it can be tested.
    var last by remember {
        mutableStateOf(ChromeScroll.Position(index = 0, offset = 0, canScrollForward = true))
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            ChromeScroll.Position(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                canScrollForward = listState.canScrollForward,
            )
        }.collect { now ->
            ChromeScroll.hidden(last, now)?.let { vm.setChromeHidden(it) }
            if (ChromeScroll.advanced(last, now)) last = now
        }
    }
    // Leaving a day must not leave the shell without its bar.
    DisposableEffect(Unit) { onDispose { vm.setChromeHidden(false) } }

    val chromeHidden by vm.chromeHidden.collectAsStateWithLifecycle()

    // Tickets and photographs both change while this app is in the background — a ticket in
    // Movie Tickets, a photograph in Roll — so both are re-read on arrival rather than
    // observed. Same reasoning, same place.
    LaunchedEffect(epochDay) {
        vm.refreshShowings()
        vm.refreshPhotos()
        // The step counter is cumulative, so opening the app is enough to fold in everything since
        // the last reading — no service, no listener held open all day.
        vm.sampleSteps()
    }

    // The clock, for the line between what has happened and what has not.
    //
    // Ticked only while today is open: any other day is entirely on one side of the line, so
    // there is nothing for a tick to change, and a minute timer running on a phone this size is
    // not free. The delay is to the top of the next minute rather than a flat 60s, or the line
    // sits up to a minute stale for the whole time the screen is up.
    val today = NoteDates.today()
    var nowMinutes by remember { mutableIntStateOf(NoteDates.nowMinutes()) }
    LaunchedEffect(epochDay, today) {
        if (epochDay != today) return@LaunchedEffect
        while (true) {
            nowMinutes = NoteDates.nowMinutes()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    // Built here rather than in the view model: it is a pure function of three flows and a
    // clock, and putting it in the view model would mean the clock lived there too.
    val photosById = remember(photos) { photos.associateBy { it.id } }
    val pickups = remember(stats) { DayTimeline.pickups(stats.pickupMinutes) }
    val items = remember(
        rows, photos, dayNotes, places, listening, pickups, talked, arrivals, calls, charges,
        recordings, lightNotes, went, read, caught, epochDay, today, nowMinutes,
    ) {
        DayTimeline.build(
            rows = rows,
            photos = photos.map { DayTimeline.PhotoAt(it.id, it.minutesOfDay(ZoneId.systemDefault())) },
            notes = dayNotes,
            places = places,
            listening = listening,
            pickups = pickups,
            talked = talked,
            arrivals = arrivals,
            calls = calls,
            charges = charges,
            recordings = recordings,
            caught = caught,
            lightNotes = lightNotes,
            went = went,
            read = read,
            epochDay = epochDay,
            today = today,
            nowMinutes = nowMinutes,
        )
    }
    // **All-day things are not moments.** They have no time to be placed at, so putting them at the
    // head of a timeline makes them look like the first thing that happened — they belong with the
    // date, which is the other thing that describes the whole day rather than a point in it.
    val allDay = remember(items) {
        items.filterIsInstance<DayTimeline.Item.Entry>().filter { it.row.minutes == null }
    }
    val moments = remember(items) { items.filter { it.minutes != null } }

    // The emptiness between moments, so a morning and an afternoon are not drawn adjacent.
    val gaps = remember(moments) { DayLayout.gaps(moments.map { it.minutes }) }
    val nowLineIndex = remember(moments, epochDay, today, nowMinutes) {
        DayTimeline.nowLineIndex(moments, DayTimeline.nowLine(epochDay, today, nowMinutes))
    }

    // Asked for from the day itself, so the reason is on screen when the dialog appears.
    val askPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refreshPhotos() }

    // Steps are a runtime permission and can be asked for here. Screen time is an appop with no
    // dialog at all, and Settings carries the adb command for it.
    val askSteps = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.sampleSteps() }

    // Asked for on the way in, not at first launch: a reminder is the only thing here that
    // needs it, and this screen is where reminders come from.
    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun commit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val (minutes, rest) = NoteDates.splitLeadingTime(text)
        vm.addDayEntry(epochDay, rest, minutes)
        draft = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .imePadding()
            .then(gestures),
    ) {
        // See the note on the shell's own bar: height only, never alpha.
        AnimatedVisibility(
            visible = !chromeHidden,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
        LightTopBar(
            title = NoteDates.dayTitle(epochDay),
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onClose),
            right = if (epochDay != today) {
                LightBarItem.Text("TODAY", onClick = { vm.jumpToToday() })
            } else {
                null
            },
        )
        }

        DayWeatherLine(weather = weather, unfinished = epochDay >= today)


        // Picking the phone up counts as something happening: the first time you looked at it is
        // very often the real start of a day, earlier than anything you wrote down.
        val bookends = remember(moments) { DayTimeline.bookends(moments) }

        if (!photosGranted) {
            PhotoPermissionRow(onAsk = { askPhotos.launch(PhotoLibrary.permission) })
            LightRule()
        }

        val body = Modifier.weight(1f).fillMaxWidth()

        // **Pull the day down to fetch.** Everything a day shows is prepared in advance — the
        // bridges are cached, the weather is archived overnight — which is right, and it leaves
        // nowhere obvious to say "go and look again". This is that place: one gesture that re-reads
        // every other app and asks for any weather still missing, as far back as there is data.
        var refreshing by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) {
            if (!refreshing) return@LaunchedEffect
            vm.refreshEverything()
            // Held briefly rather than cleared at once: the work is a handful of file reads and
            // finishes faster than the spinner appears, and a refresh that never visibly happened
            // reads as a gesture that did nothing.
            delay(600)
            refreshing = false
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = body,
        ) {
        // All-day things count as a day having something on it. Before, they lived above this
        // branch and so were drawn either way; now that they scroll with the list, a day with a
        // birthday on it and nothing else would have said "nothing on this day yet" over the top
        // of the birthday.
        if (moments.isEmpty() && allDay.isEmpty()) {
            Column(Modifier.fillMaxSize()) {
                LightEmptyState(
                    // A day that has gone and a day still to come are empty in different ways.
                    if (epochDay < today) "Nothing was written on this day." else "Nothing on this day yet.",
                    Modifier.weight(1f).fillMaxWidth(),
                )
                // Worth showing even here: a day you wrote nothing on is exactly the day whose
                // only record is what it sat on top of.
                OnThisDayRow(past = past, onOpen = { viewing = it })
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // Air under the last moment of the day, so it clears the composer and the bar
                // instead of hiding behind them.
                contentPadding = PaddingValues(bottom = 4f.verticalGridUnitsAsDp()),
            ) {
                // Inside the list, not pinned above it: it is the day's first line, and a line that
                // stays put while the day scrolls under it stops being the beginning of anything.
                // First in the list, and *in* the list: an all-day thing frames the day, but a
                // frame nailed above the scroll costs a row of screen on every day that has none
                // and cannot be read past on a day that has four. It scrolls with everything else.
                if (allDay.isNotEmpty()) {
                    item(key = "all-day") {
                        AllDaySection(
                            entries = allDay,
                            onOpen = { row ->
                                vm.entryById(row.entryId)?.let {
                                    actionsFor = it
                                    actionsOn = row.occurrenceDay ?: row.epochDay
                                }
                            },
                        )
                    }
                }

                item(key = "day-opened") {
                    DayOpening(minutes = bookends?.firstMinutes)
                }

                itemsIndexed(
                    moments,
                    // Keyed on what the item *is*, never on its position: the list reorders as
                    // the clock passes an entry, and a positional key would recycle a
                    // photograph's loaded bitmap into whatever row took its place.
                    // Defined in one place, because uniqueness cannot be checked from any single
                    // source: the day is built from eight of them and each knows only itself. See
                    // DayTimeline.key, which build() also dedupes on.
                    key = { _, item -> DayTimeline.key(item) },
                ) { index, item ->
                    if (index > 0) {
                        val previous = moments[index - 1].minutes
                        val current = item.minutes
                        TimeGap(
                            units = gaps.getOrElse(index - 1) { 0f },
                            gapMinutes = if (previous != null && current != null) {
                                (current - previous).coerceAtLeast(0)
                            } else {
                                0
                            },
                            fromMinutes = previous ?: 0,
                        )
                    }
                    if (index == nowLineIndex) NowLine()

                    when (item) {
                        is DayTimeline.Item.Photos -> TimelinePhotos(
                            item = item,
                            photosById = photosById,
                            onOpen = { viewing = it },
                            onAttach = { attaching = it },
                        )

                        is DayTimeline.Item.Place -> {
                            LightListRow(
                                title = item.name ?: "Somewhere",
                                sub = DayLayout.labelFor(item.endMinutes - item.startMinutes)
                                    ?: "${item.endMinutes - item.startMinutes} min",
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.startMinutes)),
                                // A pin, not a calendar glyph: this is a place, and the calendar
                                // icon means "this came from a calendar" everywhere else in the app.
                                leading = LightIcons.Pin,
                            )
                            LightRule()
                        }

                        // No rule under it, and no row: music ran alongside the day rather than
                        // interrupting it.
                        is DayTimeline.Item.Listening -> ListeningSpan(item)

                        is DayTimeline.Item.Arrived -> {
                            LightListRow(
                                title = item.phrase,
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                leading = LightIcons.Pin,
                            )
                            LightRule()
                        }

                        // A mention, not a row: see [TalkedMention]. Texting somebody is not an
                        // appointment, and no rule under it either — it did not interrupt the day.
                        is DayTimeline.Item.Talked -> TalkedMention(item)

                        is DayTimeline.Item.Went -> {
                            LightListRow(
                                // "Walked to" and "Took transit to" are different facts, and
                                // "Set off for" is a third one — a trip that ended before the last
                                // step is not a place you got to.
                                title = when {
                                    !item.arrived -> "Set off for ${item.place}"
                                    item.walking -> "Walked to ${item.place}"
                                    else -> "Went to ${item.place}"
                                },
                                sub = if (item.tookMinutes > 0) "${item.tookMinutes} min" else null,
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                // The same glyph a stay and an arrival get: from the day's point of
                                // view these are all the same kind of fact about a place.
                                leading = LightIcons.Pin,
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.Read -> {
                            LightListRow(
                                title = item.title,
                                sub = listOfNotNull(
                                    item.progress,
                                    item.tookMinutes.takeIf { it >= 1 }?.let { "$it min" },
                                    item.author.takeIf { it.isNotBlank() },
                                ).joinToString(" · ").takeIf { it.isNotBlank() },
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                leading = LightIcons.List,
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.LightNote -> {
                            LightListRow(
                                title = item.name,
                                sub = if (item.voice) "Voice note" else "Light note",
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                // A tape for a voice note, the pencil for a written one: the same
                                // two glyphs a recording and a note already use here, because from
                                // the day's point of view that is the same pair of facts.
                                leading = if (item.voice) LightIcons.Tape else LightIcons.Compose,
                                onClick = { vm.openLightDoc(context, item) },
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.Caught -> {
                            // No LightRule under it. The rules separate rows of text; a tray of
                            // cutouts is already a break in the page, and a line under it puts
                            // them back in the box this change took them out of.
                            TimelineCaught(
                                item = item,
                                onOpen = { vm.openCaught(context, it) },
                            )
                        }

                        is DayTimeline.Item.Recorded -> {
                            LightListRow(
                                // The place you typed on the tape, which is the whole of what a
                                // recording is called here. The clip's own title carries the date
                                // as well, and a row sitting next to the time it happened does not
                                // need to repeat it.
                                title = item.place.ifBlank { "Recording" },
                                sub = item.length,
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                // A cassette, drawn for this app: the SDK set has no tape and no
                                // microphone, and the alarm glyph already means a reminder here.
                                leading = LightIcons.Tape,
                                // The recorder, with this clip on the machine. A row that says you
                                // recorded something and cannot play it is the one thing a
                                // recording row should not be.
                                onClick = { vm.openRecording(context, item) },
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.Called -> {
                            LightListRow(
                                title = item.call.phrase,
                                sub = item.call.length,
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                                // The same person glyph a conversation gets: from the day's point
                                // of view a call and a thread are both somebody you spoke to.
                                leading = LightIcons.Person,
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.Charged -> {
                            LightListRow(
                                title = if (item.stillGoing) "On the charger" else "Charged",
                                // "7h 30m, until 07:10" — the length first, because that is the
                                // fact about the night; the end time only matters if it is over.
                                sub = listOfNotNull(
                                    Charging.length(item.lengthMinutes),
                                    if (item.startedEarlier) "started before this day" else null,
                                    if (item.stillGoing) {
                                        null
                                    } else {
                                        "until " + NoteDates.clock(
                                            JournalDay.clockMinutes(item.untilMinutes),
                                        )
                                    },
                                ).joinToString(" · "),
                                detail = NoteDates.clock(JournalDay.clockMinutes(item.minutes)),
                            )
                            LightRule()
                        }

                        // A mention, not a row: see [PickupsMention]. No rule under it either —
                        // it is the background of the day rather than an entry in it.
                        is DayTimeline.Item.Pickups -> PickupsMention(item)


                        is DayTimeline.Item.Note -> {
                            LightListRow(
                                title = item.title,
                                // What happened, not what the note is. "Wrote" and "Came back
                                // to" are the two things a note can have done on a day, and the
                                // difference is most of why the row is worth showing.
                                sub = if (item.wrote) "Wrote this" else "Came back to this",
                                detail = NoteDates.clock(item.minutes),
                                leading = LightIcons.Compose,
                                trailing = LightIcons.Forward,
                                onClick = { onOpenNote(item.noteId) },
                            )
                            LightRule()
                        }

                        is DayTimeline.Item.Entry -> {
                            val row = item.row
                            val entry = vm.entryById(row.entryId)
                            LightListRow(
                                title = row.title,
                                sub = row.subtitle,
                                detail = NoteDates.clock(row.minutes),
                                // The one filled row on the page. A day is mostly things the phone
                                // noticed on your behalf — where you went, what you played, how
                                // often you picked it up — and exactly one kind of thing you are
                                // expected to *be somewhere for*. Drawn in the same white as
                                // everything else it was a line among lines, and the 3pm you had to
                                // keep read no louder than a walk you happened to take. Inverted, a
                                // day answers "what do I have to do" from across the room.
                                inverted = true,
                                leading = when {
                                    row.passId != null -> LightIcons.Ticket
                                    entry?.fromPhoto == true -> LightIcons.Camera
                                    entry?.isImported == true -> LightIcons.Calendar
                                    else -> null
                                },
                                trailing = when {
                                    row.passId != null -> LightIcons.Forward
                                    // An alarm glyph on something that has already happened is
                                    // telling you about a reminder that can never fire again.
                                    row.reminderMinutes != null && !item.behind -> LightIcons.Alarm
                                    else -> null
                                },
                                // A ticket opens its stub in Movie Tickets; a plain entry opens
                                // its own actions. A row that is both takes the ticket on a tap
                                // and the entry on a long press.
                                onClick = {
                                    val pass = row.passId
                                    if (pass != null) {
                                        vm.openPass(pass)
                                    } else {
                                        entry?.let {
                                            actionsFor = it
                                            actionsOn = row.occurrenceDay ?: row.epochDay
                                        }
                                    }
                                },
                                onLongClick = entry?.let {
                                    {
                                        actionsFor = it
                                        actionsOn = row.occurrenceDay ?: row.epochDay
                                    }
                                },
                            )
                            LightRule()
                        }
                    }
                }

                // Last, and inside the list rather than pinned under it: it is the least urgent
                // thing on the screen, and a footer that never scrolls away would be claiming
                // otherwise.
                // Scroll to the end of a day and it tells you what the phone noticed: the walk
                // you took shows as a spike in the graph rather than as a number.
                // The day's own numbers, at the end of it, scrolling with everything else. There
                // is no line about a missing adb grant: an app that explains its own permissions on
                // the screen you read your diary on is an app talking about itself.
                if (stats.stepHours.any { it > 0 }) {
                    item(key = "steps") {
                        LightRule()
                        StepGraph(hours = stats.stepHours, total = stats.steps)
                    }
                }
                if (stats.usageGranted) {
                    item(key = "use") {
                        LightRule()
                        DayShape(stats = stats)
                    }
                    if (stats.appTime.isNotEmpty()) {
                        item(key = "app-time") {
                            DayAppTime(apps = stats.appTime)
                        }
                    }
                }

                item(key = "day-closed") {
                    DayClosing(
                        minutes = bookends?.lastMinutes,
                        unfinished = epochDay >= today,
                    )
                }

                if (past.isNotEmpty()) {
                    item(key = "on-this-day") {
                        LightRule()
                        OnThisDayRow(past = past, onOpen = { viewing = it })
                    }
                }
            }
        }
        }

        // Out of the way with the rest of the chrome. Reading back through a day is not writing in
        // it, and the field is the largest thing on the screen that is not the day itself.
        AnimatedVisibility(
            visible = !chromeHidden,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
        // The composer paints the page colour for the same reason the bars do: it sits over the
        // end of the list, and while it is animating there is a day sliding about behind it.
        Column(Modifier.background(LightThemeTokens.colors.background)) {
        LightRule()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = lightInset(),
                    vertical = 0.6f.verticalGridUnitsAsDp(),
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            LightInlineField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Add to this day",
                modifier = Modifier.weight(1f),
                onDone = { commit() },
            )
            LightText(
                text = "ADD",
                variant = LightTextVariant.Button,
                lighten = draft.isBlank(),
                modifier = Modifier
                    .padding(start = 0.8f.gridUnitsAsDp(), bottom = 0.3f.verticalGridUnitsAsDp())
                    .lightClickable(enabled = draft.isNotBlank()) { commit() },
            )
            // The long way round, for an event with a place and a rule and an alarm on it.
            // Whatever is typed comes along as its title, so this is never a step backwards from
            // the field — and with nothing typed it is a blank event to fill in.
            LightText(
                text = "MORE",
                variant = LightTextVariant.Button,
                modifier = Modifier
                    .padding(start = 0.8f.gridUnitsAsDp(), bottom = 0.3f.verticalGridUnitsAsDp())
                    .lightClickable {
                        val typed = draft
                        draft = ""
                        vm.startEvent(epochDay, typed) { id -> onOpenEvent(id) }
                    },
            )
        }
        }
        }
    }

    /* ---- sheets ---- */

    actionsFor?.let { entry ->
        LightActionSheet(
            heading = entry.text.take(34).uppercase(),
            onDismiss = { actionsFor = null },
        ) {
            // Not offered on something that has already happened: a reminder counts back from
            // a time, and there is nothing left to count back to. Times stay, because "we ate
            // at eight" is a real thing to write down about a day that has gone.
            // Tested against the occurrence, not against the entry: a series' stored day is
            // where it began, which for a weekly standup is always in the past, and testing that
            // would hide the reminder row on every repeating event there is.
            if (!DayTimeline.behind(
                    actionsOn ?: entry.epochDay,
                    entry.startMinutes,
                    today,
                    nowMinutes,
                )
            ) {
                LightSheetAction(
                    label = "Reminder",
                    sub = entry.reminderMinutes?.let {
                        if (it <= 0) "At the time" else "$it minutes before"
                    } ?: if (entry.startMinutes == null) "Needs a time first" else "None",
                ) {
                    remindingFor = entry
                    actionsFor = null
                }
            }
            LightSheetAction(label = "Open event", sub = "Where, repeats, alert") {
                onOpenEvent(entry.id)
                actionsFor = null
            }
            // Directions first when there is somewhere to go: on the way out of the door it is
            // the only row anybody is reaching for.
            entry.location?.takeIf { it.isNotBlank() }?.let { where ->
                LightSheetAction(label = "Directions", sub = where) {
                    navigatingTo = where
                    actionsFor = null
                }
            }
            // An imported entry's own words belong to the calendar it came from, and a location
            // typed here would be overwritten by the next sync without saying so.
            if (!entry.isImported) {
                LightSheetAction(
                    label = "Location",
                    sub = entry.location?.takeIf { it.isNotBlank() } ?: "None",
                ) {
                    locatingFor = entry
                    actionsFor = null
                }
            }
            LightSheetAction(
                label = "Time",
                sub = NoteDates.clock(entry.startMinutes) ?: "All day",
            ) {
                timingFor = entry
                actionsFor = null
            }
            LightSheetAction(label = "Day", sub = NoteDates.dayTitle(entry.epochDay)) {
                movingFor = entry
                actionsFor = null
            }
            LightSheetAction(
                label = "Runs until",
                sub = entry.endEpochDay?.let { NoteDates.dayTitle(it) } ?: "Just this day",
            ) {
                spanningFor = entry
                actionsFor = null
            }
            // Not offered on an imported entry: the feed owns the rule, and setting one here
            // would be overwritten by the next refresh without ever saying so.
            if (!entry.isImported) {
                LightSheetAction(
                    label = "Repeats",
                    sub = Recurrence.describe(entry.rrule, entry.epochDay) ?: "Never",
                ) {
                    repeatingFor = entry
                    actionsFor = null
                }
            } else if (entry.repeats) {
                LightSheetAction(
                    label = "Repeats",
                    sub = (Recurrence.describe(entry.rrule, entry.epochDay) ?: "Never") +
                        " · from the calendar it was imported from",
                ) {
                    actionsFor = null
                }
            }
            if (entry.imagePath != null) {
                LightSheetAction("See the photo", sub = "The page this was read off") {
                    photoFor = entry.imagePath
                    actionsFor = null
                }
            }
            if (!entry.isImported) {
                LightSheetAction("Edit text", sub = if (entry.repeats) "This one, or all of them" else null) {
                    if (entry.repeats) {
                        scopeFor = entry to SeriesAction.EDIT
                    } else {
                        editing = entry
                    }
                    actionsFor = null
                }
            }
            LightSheetAction(
                label = "Delete",
                sub = when {
                    entry.repeats -> "This one, or all of them"
                    entry.systemEventId != null -> "Also removes it from the phone's calendar"
                    entry.isImported -> "Comes back if you import again"
                    else -> null
                },
            ) {
                if (entry.repeats) {
                    scopeFor = entry to SeriesAction.DELETE
                } else {
                    vm.deleteDayEntry(entry)
                }
                actionsFor = null
            }
        }
    }

    remindingFor?.let { entry ->
        LightActionSheet(heading = "REMIND ME", onDismiss = { remindingFor = null }) {
            if (entry.startMinutes == null) {
                LightText(
                    text = "Give it a time first — a reminder counts back from one.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = lightInset(),
                        vertical = 1f.verticalGridUnitsAsDp(),
                    ),
                )
            } else {
                LightSheetAction("Never") {
                    vm.setEntryReminder(entry, null)
                    remindingFor = null
                }
                Reminders.LEAD_CHOICES.forEach { minutes ->
                    LightSheetAction(
                        label = if (minutes <= 0) "At the time" else "$minutes minutes before",
                        sub = NoteDates.clock(
                            (entry.startMinutes - minutes).coerceAtLeast(0),
                        ),
                    ) {
                        vm.setEntryReminder(entry, minutes)
                        remindingFor = null
                    }
                }
            }
        }
    }

    timingFor?.let { entry ->
        LightNameSheet(
            title = "TIME · 9:30, 9PM, OR BLANK FOR ALL DAY",
            initial = NoteDates.clock(entry.startMinutes).orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { typed ->
                vm.setEntryTime(entry, NoteDates.parseClock(typed))
                timingFor = null
            },
            onDismiss = { timingFor = null },
        )
    }

    editing?.let { entry ->
        LightNameSheet(
            title = "EDIT ENTRY",
            initial = entry.text,
            onConfirm = {
                vm.updateDayEntry(entry, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    spanningFor?.let { entry ->
        LightNameSheet(
            title = "RUNS UNTIL · YYYY-MM-DD, OR BLANK",
            initial = entry.endEpochDay?.let { NoteDates.isoDate(it) }.orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { typed ->
                // Blank ends the span. A date before the start is refused in the repository rather
                // than here, so every route in agrees.
                vm.setEntrySpan(entry, NoteDates.parseIsoDate(typed))
                spanningFor = null
            },
            onDismiss = { spanningFor = null },
        )
    }

    locatingFor?.let { entry ->
        LightNameSheet(
            title = "LOCATION · AN ADDRESS, A PLACE, OR BLANK",
            initial = entry.location.orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { typed ->
                vm.setEntryLocation(entry, typed)
                locatingFor = null
            },
            onDismiss = { locatingFor = null },
        )
    }

    navigatingTo?.let { where ->
        val targets = remember(where) { Directions.targetsFor(context, where) }
        LaunchedEffect(where, targets) {
            // Nothing installed that can navigate, or exactly one thing: no sheet either way. A
            // list of one is a question with one answer, and a list of none is a sheet that can
            // only say no.
            if (targets.size <= 1) {
                targets.firstOrNull()?.let { Directions.go(context, it) }
                navigatingTo = null
            }
        }
        if (targets.size > 1) {
            LightActionSheet(
                heading = where.take(34).uppercase(),
                onDismiss = { navigatingTo = null },
            ) {
                targets.forEach { target ->
                    LightSheetAction(label = target.label) {
                        Directions.go(context, target)
                        navigatingTo = null
                    }
                }
            }
        }
    }

    movingFor?.let { entry ->
        LightNameSheet(
            title = "WHICH DAY · YYYY-MM-DD",
            initial = NoteDates.isoDate(entry.epochDay),
            confirmLabel = "MOVE",
            onConfirm = { typed ->
                NoteDates.parseIsoDate(typed)?.let { vm.setEntryDay(entry, it) }
                movingFor = null
            },
            onDismiss = { movingFor = null },
        )
    }

    attaching?.let { photo ->
        LightNameSheet(
            title = "WHAT WAS THIS?",
            initial = "",
            confirmLabel = "ADD",
            allowBlank = true,
            onConfirm = { typed ->
                vm.attachPhotoToDay(photo, typed)
                attaching = null
            },
            onDismiss = { attaching = null },
        )
    }

    repeatingFor?.let { entry ->
        RepeatSheet(
            startEpochDay = entry.epochDay,
            current = entry.rrule,
            onDismiss = { repeatingFor = null },
            onSet = { rrule ->
                vm.setEntryRepeat(entry, rrule)
                repeatingFor = null
            },
        )
    }

    // One occurrence, or the whole series? Asked rather than assumed, in both directions: a
    // silent "all of them" deletes a year of standups, and a silent "just this one" leaves the
    // other fifty-one saying the wrong thing.
    scopeFor?.let { (entry, action) ->
        val occurrence = actionsOn ?: entry.epochDay
        LightActionSheet(
            heading = if (action == SeriesAction.DELETE) "DELETE" else "EDIT",
            onDismiss = { scopeFor = null },
        ) {
            LightSheetAction(
                label = "Just this one",
                sub = NoteDates.dayTitle(occurrence),
            ) {
                when (action) {
                    SeriesAction.DELETE -> vm.skipOccurrence(entry, occurrence)
                    // The occurrence leaves the series first, and the editor opens on the copy,
                    // so what gets typed lands on that day only.
                    SeriesAction.EDIT -> vm.detachOccurrence(entry, occurrence) { editing = it }
                }
                scopeFor = null
            }
            LightSheetAction(
                label = "All of them",
                sub = Recurrence.describe(entry.rrule, entry.epochDay),
            ) {
                when (action) {
                    SeriesAction.DELETE -> vm.deleteDayEntry(entry)
                    SeriesAction.EDIT -> editing = entry
                }
                scopeFor = null
            }
        }
    }

    if (photoFor != null) {
        PhotoSheet(path = photoFor, onDismiss = { photoFor = null })
    }

    // Over everything, including the bars: a photograph opened to be looked at should have the
    // whole panel, and this is the one place in the app that shows colour.
    viewing?.let { photo ->
        PhotoViewer(
            // The day's photographs, so the viewer swipes across the day rather than showing one
            // file. "On this day" opens a picture from another year, which is not in this list —
            // it stands alone rather than being spliced into today's roll.
            photos = if (photos.any { it.id == photo.id }) photos else listOf(photo),
            initial = photo,
            onDismiss = { viewing = null },
        )
    }
}

/** What a series is about to be asked to do to itself. */
private enum class SeriesAction { EDIT, DELETE }
