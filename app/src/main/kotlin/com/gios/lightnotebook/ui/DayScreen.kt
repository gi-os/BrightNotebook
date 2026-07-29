package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.data.DayEntryEntity
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates

/**
 * One day. Anything can go on it: a line of text, or a line of text with a time in
 * front of it — typing "9:30 dentist" is enough, so there is no time picker to wade
 * through.
 */
@Composable
fun DayScreen(
    vm: NotebookViewModel,
    epochDay: Long,
    onBack: () -> Unit,
) {
    val entries by vm.dayEntries.collectAsStateWithLifecycle()
    var draft by remember(epochDay) { mutableStateOf("") }
    var editing by remember { mutableStateOf<DayEntryEntity?>(null) }
    var actionsFor by remember { mutableStateOf<DayEntryEntity?>(null) }

    fun commit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val (minutes, rest) = NoteDates.splitLeadingTime(text)
        vm.addDayEntry(epochDay, rest, minutes)
        draft = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LightTopBar(
            title = NoteDates.dayTitle(epochDay),
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
            right = if (epochDay != NoteDates.today()) {
                LightBarItem.Text("TODAY", onClick = { vm.jumpToToday(); onBack() })
            } else {
                null
            },
        )
        LightRule()

        if (entries.isEmpty()) {
            LightEmptyState("Nothing on this day yet.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.id }) { entry ->
                    LightListRow(
                        title = entry.text,
                        detail = NoteDates.clock(entry.startMinutes),
                        leading = if (entry.fromPhoto) LightIcons.Camera else null,
                        onClick = { editing = entry },
                        onLongClick = { actionsFor = entry },
                    )
                    LightRule()
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

    actionsFor?.let { entry ->
        LightActionSheet(
            heading = entry.text.take(34).uppercase(),
            onDismiss = { actionsFor = null },
        ) {
            LightSheetAction("Edit text") {
                editing = entry
                actionsFor = null
            }
            LightSheetAction(
                label = "Delete",
                sub = if (entry.systemEventId != null) {
                    "Also removes it from the phone's calendar"
                } else {
                    null
                },
            ) {
                vm.deleteDayEntry(entry)
                actionsFor = null
            }
        }
    }
}

