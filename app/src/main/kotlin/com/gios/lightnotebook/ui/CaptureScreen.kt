package com.gios.lightnotebook.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.ai.ParsedEvent
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.hw.WheelScroll
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.NoteMarkdown

/**
 * What happens after the shutter. The photo has already been classified by then, so this
 * screen is one of four things: waiting, a page of text looking for a home, a list of
 * dates to confirm, or an apology.
 */
@Composable
fun CaptureScreen(
    vm: NotebookViewModel,
    onOpenNote: (String) -> Unit,
    onOpenDay: (Long) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by vm.capture.collectAsStateWithLifecycle()

    when (val s = state) {
        is CaptureState.Idle, is CaptureState.Reading -> ReadingView(onCancel)
        is CaptureState.NoteRead -> NoteDestinationView(vm, s, onOpenNote, onCancel)
        is CaptureState.EventsRead -> EventReviewView(vm, s.events, onOpenDay, onCancel)
        is CaptureState.Failed -> FailedView(s.message, onRetry, onCancel)
    }
}

@Composable
private fun ReadingView(onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LightTopBar(title = "READING")
        LightRule()
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText("Reading the page…", LightTextVariant.Copy)
                LightText(
                    text = "Claude is transcribing it.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
                )
            }
        }
        LightRule()
        LightBottomBar(
            listOf(
                LightBarItem.Icon(LightIcons.Close, sizeUnits = 1.9f, onClick = onCancel),
                null,
                null,
            ),
        )
    }
}

/** A page of prose can start a new note or land at the bottom of one you already have. */
@Composable
private fun NoteDestinationView(
    vm: NotebookViewModel,
    read: CaptureState.NoteRead,
    onOpenNote: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val notes by vm.notesUnfiltered.collectAsStateWithLifecycle()
    val colors = LightThemeTokens.colors

    // Two scrollers share this screen, so the wheel reads the page first and then runs on
    // into the list of places to put it. Checking the transcription is what you came here
    // to do; once the page has bottomed out there is nothing else the notch could mean.
    val transcript = rememberScrollState()
    val destinations = rememberLazyListState()
    val readingPage = transcript.value < transcript.maxValue
    WheelScroll(transcript, active = readingPage)
    WheelScroll(destinations, active = !readingPage)

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "READ",
            left = LightBarItem.Icon(LightIcons.Close, sizeUnits = 1.6f, onClick = onCancel),
        )
        LightRule()

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = lightInset()),
        ) {
            if (read.title.isNotBlank()) {
                LightText(
                    text = read.title,
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.padding(top = 0.8f.verticalGridUnitsAsDp()),
                )
            }
            Box(Modifier.weight(1f).verticalScroll(transcript)) {
                LightText(
                    text = styledNote(read.body, colors.contentFaint, colors.content),
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(vertical = 0.6f.verticalGridUnitsAsDp()),
                )
            }
        }

        LightRule()
        LightSectionLabel("PUT IT")
        LazyColumn(Modifier.fillMaxWidth().weight(0.9f), state = destinations) {
            item {
                LightListRow(
                    title = "In a new note",
                    sub = read.title.ifBlank { NoteMarkdown.firstLine(read.body, 40) },
                    leading = LightIcons.Compose,
                    onClick = {
                        vm.captureToNewNote(read.title, read.body) { id -> onOpenNote(id) }
                    },
                )
                LightRule()
                if (notes.isNotEmpty()) LightSectionLabel("OR AT THE END OF")
            }
            items(notes, key = { it.id }) { note ->
                LightListRow(
                    title = note.title.ifBlank { NoteMarkdown.firstLine(note.body) }
                        .ifBlank { "Untitled" },
                    sub = NoteMarkdown.preview(note.body, 60),
                    onClick = {
                        vm.captureToExistingNote(note.id, read.body) { id -> onOpenNote(id) }
                    },
                )
                LightRule()
            }
        }
    }
}

/**
 * A photographed calendar. Everything is kept by default — the point is to not have to
 * type it — but each line can be dropped, because a photo of a planner picks up other
 * people's dentist appointments too.
 */
@Composable
private fun EventReviewView(
    vm: NotebookViewModel,
    events: List<ParsedEvent>,
    onOpenDay: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val mirror by vm.mirrorEvents.collectAsStateWithLifecycle()
    var dropped by remember(events) { mutableStateOf(setOf<Int>()) }
    var calendarName by remember { mutableStateOf(vm.systemCalendarName()) }

    // Ask for the calendar only once there is something to write into it.
    val askCalendar = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { calendarName = vm.systemCalendarName() }
    LaunchedEffect(mirror) {
        if (mirror && !SystemCalendar.hasPermission(context)) {
            askCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        }
    }

    val kept = events.filterIndexed { i, _ -> i !in dropped }
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "${events.size} FOUND",
            left = LightBarItem.Icon(LightIcons.Close, sizeUnits = 1.6f, onClick = onCancel),
        )
        LightRule()

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            itemsIndexed(events) { index, event ->
                val on = index !in dropped
                LightListRow(
                    title = event.title,
                    sub = NoteDates.dayTitle(event.epochDay).lowercase()
                        .replaceFirstChar { it.uppercase() },
                    detail = NoteDates.clock(event.startMinutes) ?: "All day",
                    trailing = if (on) LightIcons.SelectOn else LightIcons.SelectOff,
                    lighten = !on,
                    onClick = {
                        dropped = if (on) dropped + index else dropped - index
                    },
                )
                LightRule()
            }
        }

        LightText(
            text = if (mirror && calendarName != null) {
                "Also going into $calendarName."
            } else if (mirror) {
                "No phone calendar to write to — keeping these in Notebook."
            } else {
                "Keeping these in Notebook only."
            },
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(
                horizontal = lightInset(),
                vertical = 0.5f.verticalGridUnitsAsDp(),
            ),
        )
        LightWideButton(
            label = if (kept.isEmpty()) "NOTHING SELECTED" else "ADD ${kept.size}",
            enabled = kept.isNotEmpty(),
            modifier = Modifier.padding(
                horizontal = lightInset(),
                vertical = 0.5f.verticalGridUnitsAsDp(),
            ),
            onClick = {
                val first = kept.minByOrNull { it.epochDay }?.epochDay
                vm.commitEvents(kept) { _, _ -> if (first != null) onOpenDay(first) }
            },
        )
    }
}

@Composable
private fun FailedView(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LightTopBar(title = "NO LUCK")
        LightRule()
        LightEmptyState(message, Modifier.weight(1f))
        LightRule()
        LightBottomBar(
            items = listOf(
                LightBarItem.Icon(LightIcons.Close, sizeUnits = 1.9f, onClick = onCancel),
                LightBarItem.Icon(LightIcons.Refresh, sizeUnits = 1.9f, onClick = onRetry),
                null,
            ),
        )
    }
}
