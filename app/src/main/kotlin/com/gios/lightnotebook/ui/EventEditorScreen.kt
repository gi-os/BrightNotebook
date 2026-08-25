package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.light.common.hw.WheelScroll
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.data.Directions
import com.gios.lightnotebook.data.IcsShare
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.Repeats

/**
 * One event, in full: **when**, **where**, how it **repeats**, and what it **alerts**.
 *
 * ### Why a page and not a longer sheet
 *
 * The day's inline field is still the fast path and always will be — typing "9:30 standup" and
 * pressing go is the shortest route to a plan on any phone. What was missing is everything an
 * invite from Outlook already carries and this app could not say back: a location, a rule, an
 * alarm. That does not fit in a sheet you swipe up over a day, so it gets a page, the way Light's
 * own calendar does.
 *
 * ### Why four tabs
 *
 * Because the four questions are independent, and a single scroll of eleven rows makes you read all
 * of them to answer one. The strip borrows the bottom bar's treatment — a tracked label, and a rule
 * under the one you are on — because the SDK has no tab component and inventing a pill here would
 * be inventing a widget vocabulary for one screen.
 *
 * ### There is no draft
 *
 * The editor always works on a real entry, saved as you go. Every row writes through the same view
 * model setter the day's action sheet uses, so there is one code path per field rather than two,
 * and no "save" that can be lost by pressing home. An event created for the editor and abandoned
 * is deleted by the Delete row, which is where somebody would look for it anyway.
 */
@Composable
fun EventEditorScreen(
    vm: NotebookViewModel,
    entryId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val entry by vm.entryFlow(entryId).collectAsStateWithLifecycle()

    // The entry has gone — deleted from here, or by a calendar re-import while this was open. A
    // screen with nothing behind it is a screen to leave, not one to draw empty.
    val current = entry
    LaunchedEffect(current, entryId) { if (current == null) onBack() }
    if (current == null) return

    var tab by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var spanning by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var navigating by remember { mutableStateOf<String?>(null) }
    var repeating by remember { mutableStateOf(false) }
    var alerting by remember { mutableStateOf(false) }
    var untilling by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = if (current.isImported) "EVENT · IMPORTED" else "EVENT",
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()
        EventTabs(tab) { tab = it }
        LightRule()

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            when (tab) {
                0 -> item {
                    Column {
                        LightListRow(
                            title = current.text.ifBlank { "Untitled" },
                            sub = "What it is",
                            leading = LightIcons.Compose,
                            onClick = { naming = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        LightListRow(
                            title = NoteDates.dayTitle(current.epochDay),
                            sub = "Which day",
                            leading = LightIcons.Calendar,
                            onClick = { moving = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        LightListRow(
                            title = NoteDates.clock(current.startMinutes) ?: "All day",
                            sub = "Starts",
                            leading = LightIcons.Alarm,
                            onClick = { timing = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        LightListRow(
                            title = NoteDates.clock(current.endMinutes) ?: "Not set",
                            sub = if (current.startMinutes == null) {
                                "An all-day event has no end time"
                            } else {
                                "Ends"
                            },
                            leading = LightIcons.Alarm,
                            lighten = current.startMinutes == null,
                            onClick = { ending = true }
                                .takeIf { !current.isImported && current.startMinutes != null },
                        )
                        LightRule()
                        LightListRow(
                            title = current.endEpochDay?.let { NoteDates.dayTitle(it) } ?: "One day",
                            sub = "Runs until",
                            leading = LightIcons.Calendar,
                            onClick = { spanning = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        EventFooter(current, onSend = { IcsShare.send(context, current) }) {
                            vm.deleteDayEntry(current)
                        }
                    }
                }

                1 -> item {
                    Column {
                        LightListRow(
                            title = current.location?.takeIf { it.isNotBlank() } ?: "Nowhere set",
                            sub = "Where it is",
                            leading = LightIcons.Pin,
                            lighten = current.location.isNullOrBlank(),
                            onClick = { locating = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        current.location?.takeIf { it.isNotBlank() }?.let { where ->
                            LightListRow(
                                title = "Directions",
                                sub = "Open it in whatever navigates on this phone",
                                leading = LightIcons.Forward,
                                onClick = { navigating = where },
                            )
                            LightRule()
                        }
                        LightText(
                            text = "An address, a room, a place name — whatever you would tell " +
                                "somebody. It is handed to the maps app as written.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(
                                horizontal = lightInset(),
                                vertical = 1f.verticalGridUnitsAsDp(),
                            ),
                        )
                    }
                }

                2 -> item {
                    Column {
                        LightListRow(
                            title = Repeats.describe(current.rrule),
                            sub = "How often",
                            leading = LightIcons.Refresh,
                            onClick = { repeating = true }.takeIf { !current.isImported },
                        )
                        LightRule()
                        LightListRow(
                            title = Repeats.describeEnd(current.rrule),
                            sub = "Until when",
                            leading = LightIcons.Calendar,
                            lighten = current.rrule.isNullOrBlank(),
                            onClick = { untilling = true }
                                .takeIf { !current.isImported && !current.rrule.isNullOrBlank() },
                        )
                        LightRule()
                        LightText(
                            text = "Repeats are stored as the calendar's own rule, so an event " +
                                "made here exports and re-imports as the same series.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(
                                horizontal = lightInset(),
                                vertical = 1f.verticalGridUnitsAsDp(),
                            ),
                        )
                    }
                }

                else -> item {
                    Column {
                        LightListRow(
                            title = alertLabel(current.reminderMinutes),
                            sub = if (current.startMinutes == null) {
                                "Needs a start time to count back from"
                            } else {
                                "Alert"
                            },
                            leading = LightIcons.Alarm,
                            lighten = current.startMinutes == null,
                            onClick = { alerting = true }.takeIf { current.startMinutes != null },
                        )
                        LightRule()
                        LightText(
                            text = "One alert per event, and it goes out as a VALARM — so a " +
                                "calendar you send this to keeps the same reminder.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(
                                horizontal = lightInset(),
                                vertical = 1f.verticalGridUnitsAsDp(),
                            ),
                        )
                    }
                }
            }
        }
    }

    if (naming) {
        LightNameSheet(
            title = "WHAT IT IS",
            initial = current.text,
            onConfirm = { vm.updateDayEntry(current, it); naming = false },
            onDismiss = { naming = false },
        )
    }

    if (timing) {
        LightNameSheet(
            title = "STARTS · 9:30, 9PM, OR BLANK FOR ALL DAY",
            initial = NoteDates.clock(current.startMinutes).orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { vm.setEntryTime(current, NoteDates.parseClock(it)); timing = false },
            onDismiss = { timing = false },
        )
    }

    if (ending) {
        LightNameSheet(
            title = "ENDS · 10:30, 10PM, OR BLANK",
            initial = NoteDates.clock(current.endMinutes).orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { vm.setEntryEnd(current, NoteDates.parseClock(it)); ending = false },
            onDismiss = { ending = false },
        )
    }

    if (moving) {
        LightNameSheet(
            title = "WHICH DAY · YYYY-MM-DD",
            initial = NoteDates.isoDate(current.epochDay),
            confirmLabel = "MOVE",
            onConfirm = { typed ->
                NoteDates.parseIsoDate(typed)?.let { vm.setEntryDay(current, it) }
                moving = false
            },
            onDismiss = { moving = false },
        )
    }

    if (spanning) {
        LightNameSheet(
            title = "RUNS UNTIL · YYYY-MM-DD, OR BLANK",
            initial = current.endEpochDay?.let { NoteDates.isoDate(it) }.orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { typed ->
                vm.setEntrySpan(current, NoteDates.parseIsoDate(typed))
                spanning = false
            },
            onDismiss = { spanning = false },
        )
    }

    if (locating) {
        LightNameSheet(
            title = "LOCATION · AN ADDRESS, A PLACE, OR BLANK",
            initial = current.location.orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { vm.setEntryLocation(current, it); locating = false },
            onDismiss = { locating = false },
        )
    }

    if (repeating) {
        LightActionSheet(heading = "HOW OFTEN", onDismiss = { repeating = false }) {
            Column {
                Repeats.presets(current.epochDay).forEach { preset ->
                    LightSheetAction(label = preset.label) {
                        vm.setEntryRepeat(current, Repeats.withEnd(preset.rrule, current.rrule))
                        repeating = false
                    }
                }
            }
        }
    }

    if (untilling) {
        LightNameSheet(
            title = "REPEATS UNTIL · YYYY-MM-DD, OR BLANK FOR FOREVER",
            initial = Repeats.untilDay(current.rrule)?.let { NoteDates.isoDate(it) }.orEmpty(),
            confirmLabel = "SET",
            allowBlank = true,
            onConfirm = { typed ->
                vm.setEntryRepeat(
                    current,
                    Repeats.endingOn(current.rrule, NoteDates.parseIsoDate(typed)),
                )
                untilling = false
            },
            onDismiss = { untilling = false },
        )
    }

    if (alerting) {
        LightActionSheet(heading = "ALERT", onDismiss = { alerting = false }) {
            Column {
                ALERTS.forEach { minutes ->
                    LightSheetAction(label = alertLabel(minutes)) {
                        vm.setEntryReminder(current, minutes)
                        alerting = false
                    }
                }
            }
        }
    }

    navigating?.let { where ->
        val targets = remember(where) { Directions.targetsFor(context, where) }
        LaunchedEffect(where, targets) {
            if (targets.size <= 1) {
                targets.firstOrNull()?.let { Directions.go(context, it) }
                navigating = null
            }
        }
        if (targets.size > 1) {
            LightActionSheet(heading = "OPEN IN", onDismiss = { navigating = null }) {
                Column {
                    targets.forEach { target ->
                        LightSheetAction(label = target.label) {
                            Directions.go(context, target)
                            navigating = null
                        }
                    }
                }
            }
        }
    }
}

/**
 * The four questions, as a strip.
 *
 * The bottom bar's treatment, not a new one: a tracked label per tab and a rule under the one you
 * are on. Four words is what fits across a 27-unit grid at this size, which is also why there are
 * four tabs and not six.
 */
@Composable
private fun EventTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = lightInset())) {
        TABS.forEachIndexed { index, label ->
            Column(Modifier.weight(1f)) {
                LightText(
                    text = label,
                    variant = LightTextVariant.Superfine,
                    lighten = index != selected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { onSelect(index) }
                        .padding(vertical = 0.8f.verticalGridUnitsAsDp()),
                )
                if (index == selected) LightRule()
            }
        }
    }
}

/** Sending it out, and deleting it. Both belong at the bottom of the first tab. */
@Composable
private fun EventFooter(
    entry: DayEntryEntity,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        LightListRow(
            title = "Send as invite",
            sub = "An .ics file — Google, Outlook and Apple all take one",
            leading = LightIcons.Forward,
            onClick = onSend,
        )
        LightRule()
        LightListRow(
            title = "Delete",
            sub = if (entry.isImported) "Removed until the next import brings it back" else null,
            leading = LightIcons.Trash,
            onClick = onDelete,
        )
        LightRule()
    }
}

private fun alertLabel(minutes: Int?): String = when {
    minutes == null -> "None"
    minutes <= 0 -> "At the time"
    minutes % 1440 == 0 -> if (minutes == 1440) "1 day before" else "${minutes / 1440} days before"
    minutes % 60 == 0 -> if (minutes == 60) "1 hour before" else "${minutes / 60} hours before"
    else -> "$minutes minutes before"
}

private val TABS = listOf("WHEN", "WHERE", "REPEAT", "ALERT")

/** The offsets worth a row. Anything else can be typed into the day's own reminder sheet. */
private val ALERTS = listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440)
