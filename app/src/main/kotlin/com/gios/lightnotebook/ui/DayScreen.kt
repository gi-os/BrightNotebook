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
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.lightnotebook.hw.WheelScroll
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
import com.gios.lightnotebook.util.DayTimeline
import com.gios.lightnotebook.util.NoteDates
import kotlinx.coroutines.delay
import java.time.ZoneId

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
) {
    // Opened from a reminder rather than from the planner, so there is no surface to slide:
    // a horizontal drag steps the day once it passes half the screen.
    var slid by remember { mutableStateOf(0f) }
    val step = with(LocalDensity.current) { STANDALONE_STEP_DP.dp.toPx() }
    DayPane(
        vm = vm,
        onClose = onBack,
        onOpenNote = onOpenNote,
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
@Composable
fun DayPane(
    vm: NotebookViewModel,
    onClose: () -> Unit,
    /** A note written or returned to on this day opens it, the same as from the notes list. */
    onOpenNote: (String) -> Unit,
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
    var remindingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var timingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var movingFor by remember { mutableStateOf<DayEntryEntity?>(null) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    var attaching by remember { mutableStateOf<DevicePhoto?>(null) }

    val photos by vm.dayPhotos.collectAsStateWithLifecycle()
    val dayNotes by vm.dayNotes.collectAsStateWithLifecycle()
    val past by vm.onThisDay.collectAsStateWithLifecycle()
    val stats by vm.dayStats.collectAsStateWithLifecycle()
    val photosGranted by vm.photosGranted.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    WheelScroll(listState)

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
    val items = remember(rows, photos, dayNotes, epochDay, today, nowMinutes) {
        DayTimeline.build(
            rows = rows,
            photos = photos.map { DayTimeline.PhotoAt(it.id, it.minutesOfDay(ZoneId.systemDefault())) },
            notes = dayNotes,
            epochDay = epochDay,
            today = today,
            nowMinutes = nowMinutes,
        )
    }
    val nowLineIndex = remember(items, epochDay, today, nowMinutes) {
        DayTimeline.nowLineIndex(items, DayTimeline.nowLine(epochDay, today, nowMinutes))
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
        LightTopBar(
            title = NoteDates.dayTitle(epochDay),
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onClose),
            right = if (epochDay != today) {
                LightBarItem.Text("TODAY", onClick = { vm.jumpToToday() })
            } else {
                null
            },
        )
        LightRule()

        val bookends = remember(items) { DayTimeline.bookends(items) }
        DayOpening(minutes = bookends?.firstMinutes)
        DayShape(stats = stats)

        if (!photosGranted) {
            PhotoPermissionRow(onAsk = { askPhotos.launch(PhotoLibrary.permission) })
            LightRule()
        }

        val body = Modifier.weight(1f).fillMaxWidth()

        if (items.isEmpty()) {
            Column(body) {
                LightEmptyState(
                    // A day that has gone and a day still to come are empty in different ways.
                    if (epochDay < today) "Nothing was written on this day." else "Nothing on this day yet.",
                    Modifier.weight(1f).fillMaxWidth(),
                )
                // Worth showing even here: a day you wrote nothing on is exactly the day whose
                // only record is what it sat on top of.
                OnThisDayRow(past = past, onOpen = { photoFor = it.uri.toString() })
            }
        } else {
            LazyColumn(body, state = listState) {
                itemsIndexed(
                    items,
                    // Keyed on what the item *is*, never on its position: the list reorders as
                    // the clock passes an entry, and a positional key would recycle a
                    // photograph's loaded bitmap into whatever row took its place.
                    key = { _, item ->
                        when (item) {
                            is DayTimeline.Item.Entry -> "row-" + item.row.id
                            is DayTimeline.Item.Photos -> "photos-" + item.photos.first().id
                            is DayTimeline.Item.Note -> "note-" + item.noteId
                        }
                    },
                ) { index, item ->
                    if (index == nowLineIndex) NowLine()

                    when (item) {
                        is DayTimeline.Item.Photos -> TimelinePhotos(
                            item = item,
                            photosById = photosById,
                            onOpen = { photoFor = it.uri.toString() },
                            onAttach = { attaching = it },
                        )

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
                                        entry?.let { actionsFor = it }
                                    }
                                },
                                onLongClick = entry?.let { { actionsFor = it } },
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
                if (stats.stepHours.any { it > 0 }) {
                    item(key = "steps") {
                        LightRule()
                        StepGraph(hours = stats.stepHours, total = stats.steps)
                    }
                } else if (!stats.usageGranted || !stats.stepsGranted) {
                    item(key = "stats-grant") {
                        LightRule()
                        StatsGrantRow(onCopy = { askSteps.launch(Manifest.permission.ACTIVITY_RECOGNITION) })
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
                        OnThisDayRow(past = past, onOpen = { photoFor = it.uri.toString() })
                    }
                }
            }
        }

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
            if (!DayTimeline.behind(entry.epochDay, entry.startMinutes, today, nowMinutes)) {
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
            if (entry.imagePath != null) {
                LightSheetAction("See the photo", sub = "The page this was read off") {
                    photoFor = entry.imagePath
                    actionsFor = null
                }
            }
            if (!entry.isImported) {
                LightSheetAction("Edit text") {
                    editing = entry
                    actionsFor = null
                }
            }
            LightSheetAction(
                label = "Delete",
                sub = when {
                    entry.systemEventId != null -> "Also removes it from the phone's calendar"
                    entry.isImported -> "Comes back if you import again"
                    else -> null
                },
            ) {
                vm.deleteDayEntry(entry)
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

    if (photoFor != null) {
        PhotoSheet(path = photoFor, onDismiss = { photoFor = null })
    }
}
