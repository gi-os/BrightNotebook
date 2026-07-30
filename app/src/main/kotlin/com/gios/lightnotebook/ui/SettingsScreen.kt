package com.gios.lightnotebook.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.data.SystemCalendar
import com.gios.lightnotebook.hw.WheelScroll
import com.gios.lightnotebook.notify.Reminders
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp

@Composable
fun SettingsScreen(
    vm: NotebookViewModel,
    onScanQr: () -> Unit,
    onCalendars: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val saved by vm.apiKey.collectAsStateWithLifecycle()
    val mirror by vm.mirrorEvents.collectAsStateWithLifecycle()
    val lead by vm.defaultLead.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val status by vm.importStatus.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var calendarName by remember { mutableStateOf(vm.systemCalendarName()) }
    var leadSheet by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val askCalendar = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { calendarName = vm.systemCalendarName() }

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "SETTINGS",
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = lightInset()),
        ) {
            LightText(
                text = "ANTHROPIC API KEY",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.2f.verticalGridUnitsAsDp()),
            )
            LightInlineField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "sk-ant-…",
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
                onDone = { vm.setApiKey(draft) },
            )
            LightWideButton(
                label = if (draft == saved && saved.isNotBlank()) "SAVED" else "SAVE KEY",
                filled = draft != saved,
                enabled = draft != saved,
                modifier = Modifier.padding(top = 0.8f.verticalGridUnitsAsDp()),
                onClick = { vm.setApiKey(draft) },
            )
            LightWideButton(
                label = "SCAN QR",
                filled = false,
                modifier = Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
                onClick = onScanQr,
            )
            LightText(
                text = "Only the camera needs a key — it reads a photographed page with " +
                    "Claude Haiku, a fraction of a cent a page. Typing and the calendar work " +
                    "offline. The key never leaves this phone.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.8f.verticalGridUnitsAsDp()),
            )

            LightText(
                text = "CALENDARS",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.6f.verticalGridUnitsAsDp()),
            )
            LightWideButton(
                label = if (calendars.isEmpty()) {
                    "IMPORT A CALENDAR"
                } else {
                    "CALENDARS · ${calendars.size}"
                },
                filled = false,
                modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
                onClick = onCalendars,
            )
            LightWideButton(
                label = "SYNC NOW",
                filled = false,
                enabled = calendars.isNotEmpty(),
                modifier = Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
                onClick = { vm.syncNow() },
            )
            LightText(
                text = status ?: "Imported calendars refresh themselves about once an hour.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
            )

            LightText(
                text = "REMIND ME",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.6f.verticalGridUnitsAsDp()),
            )
            LightWideButton(
                label = lead?.let {
                    if (it <= 0) "AT THE TIME" else "$it MINUTES BEFORE"
                } ?: "NEVER",
                filled = lead != null,
                modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
                onClick = { leadSheet = true },
            )
            LightText(
                text = "What a new entry with a time on it gets. Change any single one from " +
                    "its own row on the day.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
            )

            LightText(
                text = "PHONE CALENDAR",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.6f.verticalGridUnitsAsDp()),
            )
            LightWideButton(
                label = if (mirror) "MIRRORING: ON" else "MIRRORING: OFF",
                filled = mirror,
                modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
                onClick = {
                    val next = !mirror
                    vm.setMirrorEvents(next)
                    if (next && !SystemCalendar.hasPermission(context)) {
                        askCalendar.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    } else {
                        calendarName = vm.systemCalendarName()
                    }
                },
            )
            LightText(
                text = when {
                    !mirror -> "Dates stay in Notebook only."
                    calendarName != null -> "Dates are also written to $calendarName."
                    else -> "No writable calendar found on this phone yet — dates stay here."
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(
                    top = 0.5f.verticalGridUnitsAsDp(),
                    bottom = 1.6f.verticalGridUnitsAsDp(),
                ),
            )
        }
    }

    if (leadSheet) {
        LightActionSheet(heading = "REMIND ME", onDismiss = { leadSheet = false }) {
            LightSheetAction("Never") {
                vm.setDefaultLead(null)
                leadSheet = false
            }
            Reminders.LEAD_CHOICES.forEach { minutes ->
                LightSheetAction(
                    if (minutes <= 0) "At the time" else "$minutes minutes before",
                ) {
                    vm.setDefaultLead(minutes)
                    leadSheet = false
                }
            }
        }
    }
}
