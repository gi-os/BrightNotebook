package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightTopBar
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
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val showings by vm.showings.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val today = NoteDates.today()

    LaunchedEffect(Unit) { vm.refreshShowings() }

    val rows = remember(upcoming, showings, calendars, today) {
        val fromEntries = upcoming.map { entry ->
            AgendaItem(
                epochDay = entry.epochDay,
                minutes = entry.startMinutes,
                title = entry.text,
                label = calendars.firstOrNull { it.id == entry.calendarId }?.label,
                reminderMinutes = entry.reminderMinutes,
                passId = null,
            )
        }
        val fromFilms = showings.filter { it.epochDay >= today }.map { showing ->
            AgendaItem(
                epochDay = showing.epochDay,
                minutes = showing.startMinutes,
                title = showing.title,
                label = showing.where ?: "Movie Tickets",
                reminderMinutes = null,
                passId = showing.passId,
            )
        }
        (fromEntries + fromFilms).sortedWith(compareBy({ it.epochDay }, { it.minutes ?: -1 }))
    }

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = "NEXT UP",
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()

        if (rows.isEmpty()) {
            LightEmptyState("Nothing ahead.\nTap a day to write on it.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                // One heading per day, so the eye gets the date once instead of on every row.
                var lastDay: Long? = null
                rows.forEach { row ->
                    if (row.epochDay != lastDay) {
                        lastDay = row.epochDay
                        item(key = "day-${row.epochDay}") {
                            LightSectionLabel(
                                when (row.epochDay) {
                                    today -> "TODAY · ${NoteDates.dayTitle(row.epochDay)}"
                                    today + 1 -> "TOMORROW · ${NoteDates.dayTitle(row.epochDay)}"
                                    else -> NoteDates.dayTitle(row.epochDay)
                                },
                            )
                        }
                    }
                    item(key = row.key) {
                        LightListRow(
                            title = row.title,
                            sub = row.subtitle(),
                            detail = NoteDates.clock(row.minutes) ?: "All day",
                            leading = if (row.passId != null) LightIcons.Ticket else null,
                            trailing = if (row.reminderMinutes != null) LightIcons.Alarm else null,
                            onClick = {
                                if (row.passId != null) {
                                    vm.openPass(row.passId)
                                } else {
                                    onOpenDay(row.epochDay)
                                }
                            },
                        )
                        LightRule()
                    }
                }
            }
        }
    }
}

/** A line on the agenda: an entry, or a film from LightPass. */
private data class AgendaItem(
    val epochDay: Long,
    val minutes: Int?,
    val title: String,
    val label: String?,
    val reminderMinutes: Int?,
    val passId: String?,
) {
    val key: String get() = passId?.let { "pass:$it" } ?: "$epochDay:$minutes:$title"

    fun subtitle(): String? {
        val remind = reminderMinutes?.let { if (it <= 0) "at the time" else "$it min before" }
        return listOfNotNull(label, remind).joinToString(" · ").takeIf { it.isNotBlank() }
    }
}
