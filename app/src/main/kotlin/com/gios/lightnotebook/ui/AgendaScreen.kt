package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.hw.WheelScroll
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.util.Agenda
import com.gios.lightnotebook.util.NoteDates

/**
 * What's next, on its own screen.
 *
 * This started as a strip under the month grid and was unreadable there — a few rows of
 * small type competing with a 42-cell grid. Given the whole screen it can be what it should
 * be: a day at a time, in order, with the times and the labels legible.
 */
@Composable
fun AgendaScreen(
    vm: NotebookViewModel,
    onOpenDay: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val rows by vm.agendaRows.collectAsStateWithLifecycle()
    val today = NoteDates.today()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    LaunchedEffect(Unit) { vm.refreshShowings() }

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "NEXT UP",
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()

        if (rows.isEmpty()) {
            LightEmptyState("Nothing ahead.\nTap a day to write on it.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                // One heading per day, so the eye gets the date once instead of on every row.
                var lastDay: Long? = null
                rows.forEach { row ->
                    if (row.epochDay != lastDay) {
                        lastDay = row.epochDay
                        item(key = "day-${row.epochDay}") {
                            LightSectionLabel(Agenda.heading(row.epochDay, today))
                        }
                    }
                    item(key = row.id) {
                        LightListRow(
                            title = row.title,
                            sub = row.subtitle,
                            detail = NoteDates.clock(row.minutes) ?: "All day",
                            leading = if (row.passId != null) LightIcons.Ticket else null,
                            trailing = if (row.reminderMinutes != null) LightIcons.Alarm else null,
                            onClick = {
                                // A ticket goes to its stub, because that is where the
                                // barcode is; anything else opens its day.
                                val pass = row.passId
                                if (pass != null) vm.openPass(pass) else onOpenDay(row.epochDay)
                            },
                        )
                        LightRule()
                    }
                }
            }
        }
    }
}
