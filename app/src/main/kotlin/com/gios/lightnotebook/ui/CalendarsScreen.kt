package com.gios.lightnotebook.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.data.CalendarEntity
import com.gios.lightnotebook.data.DeviceCalendar
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp

/**
 * Calendars: what is on the grid, and where it came from.
 *
 * Imports are snapshots, and re-importing the same source replaces its events rather than
 * doubling them. Hiding a calendar takes it out of every screen at the query, so there is
 * no per-screen filter to keep in step.
 */
@Composable
fun CalendarsScreen(
    vm: NotebookViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val status by vm.importStatus.collectAsStateWithLifecycle()

    var actionsFor by remember { mutableStateOf<CalendarEntity?>(null) }
    var renaming by remember { mutableStateOf<CalendarEntity?>(null) }
    var devicePicker by remember { mutableStateOf<List<DeviceCalendar>?>(null) }

    // Any document type: exporters hand out text/calendar, application/ics, octet-stream
    // and sometimes nothing at all, and refusing the file for its MIME type would be a
    // dead end the user cannot fix.
    val pickIcs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importIcs(uri) }

    val askCalendarRead = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { devicePicker = vm.deviceCalendars() }

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "CALENDARS",
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                LightListRow(
                    title = "Notebook",
                    sub = "What you type here. Always shown.",
                    leading = LightIcons.Compose,
                )
                LightRule()
            }
            if (calendars.isNotEmpty()) item { LightSectionLabel("IMPORTED") }
            items(calendars, key = { it.id }) { calendar ->
                LightListRow(
                    title = calendar.label,
                    sub = when (calendar.kind) {
                        CalendarEntity.KIND_ICS -> "From a file"
                        CalendarEntity.KIND_DEVICE -> "From the phone"
                        else -> null
                    },
                    detail = if (calendar.visible) null else "HIDDEN",
                    leading = if (calendar.visible) LightIcons.SelectOn else LightIcons.SelectOff,
                    lighten = !calendar.visible,
                    onClick = { vm.setCalendarVisible(calendar, !calendar.visible) },
                    onLongClick = { actionsFor = calendar },
                )
                LightRule()
            }
            item {
                LightSectionLabel("ADD")
                LightListRow(
                    title = "Import a .ics file",
                    sub = "An export or an invite, from anywhere",
                    leading = LightIcons.Add,
                    onClick = { pickIcs.launch(arrayOf("*/*")) },
                )
                LightRule()
                LightListRow(
                    title = "Import from this phone",
                    sub = "Whatever LightOS already syncs",
                    leading = LightIcons.Calendar,
                    onClick = {
                        if (SystemCalendar.hasPermission(context)) {
                            devicePicker = vm.deviceCalendars()
                        } else {
                            askCalendarRead.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        }
                    },
                )
                LightRule()
            }
            if (status != null) {
                item {
                    LightText(
                        text = status.orEmpty(),
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

    devicePicker?.let { available ->
        LightActionSheet(
            heading = if (available.isEmpty()) "NO CALENDARS FOUND" else "IMPORT WHICH",
            onDismiss = { devicePicker = null },
        ) {
            if (available.isEmpty()) {
                LightText(
                    text = "This phone has no calendar accounts to read.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = lightInset(),
                        vertical = 1f.verticalGridUnitsAsDp(),
                    ),
                )
            }
            available.forEach { device ->
                LightSheetAction(label = device.label, sub = device.account) {
                    vm.importDeviceCalendar(device)
                    devicePicker = null
                }
            }
        }
    }

    actionsFor?.let { calendar ->
        LightActionSheet(
            heading = calendar.label.uppercase(),
            onDismiss = { actionsFor = null },
        ) {
            LightSheetAction(if (calendar.visible) "Hide from the grid" else "Show on the grid") {
                vm.setCalendarVisible(calendar, !calendar.visible)
                actionsFor = null
            }
            LightSheetAction("Rename") {
                renaming = calendar
                actionsFor = null
            }
            LightSheetAction("Remove", sub = "Its events go with it") {
                vm.deleteCalendar(calendar)
                actionsFor = null
            }
        }
    }

    renaming?.let { calendar ->
        LightNameSheet(
            title = "RENAME CALENDAR",
            initial = calendar.label,
            onConfirm = { vm.renameCalendar(calendar, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}
