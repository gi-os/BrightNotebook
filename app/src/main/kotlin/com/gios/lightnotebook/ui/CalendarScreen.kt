package com.gios.lightnotebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates

/**
 * A month at a time, seven squares wide. Days from the neighbouring months are left
 * blank rather than greyed: on this panel a dimmed number reads as tappable, and it
 * isn't.
 */
@Composable
fun CalendarScreen(
    vm: NotebookViewModel,
    onOpenDay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val counts by vm.dayCounts.collectAsStateWithLifecycle()
    val selected by vm.selectedDay.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val showings by vm.showings.collectAsStateWithLifecycle()
    val today = NoteDates.today()

    // Tickets are added in the other app, so re-read them on arrival here.
    LaunchedEffect(Unit) { vm.refreshShowings() }

    val agenda = remember(upcoming, showings, today) {
        val fromEntries = upcoming.map {
            AgendaRow(it.epochDay, it.startMinutes, it.text, null, null)
        }
        val fromFilms = showings
            .filter { it.epochDay >= today }
            .map { AgendaRow(it.epochDay, it.startMinutes, it.title, it.where, it.passId) }
        (fromEntries + fromFilms)
            .sortedWith(compareBy({ it.epochDay }, { it.minutes ?: -1 }))
            .take(8)
    }

    Column(modifier.fillMaxSize()) {
        LightTopBar(
            title = NoteDates.monthTitle(month),
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f) { vm.stepMonth(-1) },
            right = LightBarItem.Icon(LightIcons.Forward, sizeUnits = 1.6f) { vm.stepMonth(1) },
        )
        LightRule()

        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = lightInset(),
                    end = lightInset(),
                    top = 0.5f.verticalGridUnitsAsDp(),
                ),
        ) {
            NoteDates.weekdayInitials.forEach { initial ->
                LightText(
                    text = initial,
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        NoteDates.weeks(month).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = lightInset())) {
                week.forEach { day ->
                    DayCell(
                        epochDay = day,
                        entries = day?.let { counts[it] } ?: 0,
                        isToday = day == today,
                        isSelected = day == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { if (day != null) onOpenDay(day) },
                    )
                }
            }
        }

        Spacer(Modifier.height(0.6f.verticalGridUnitsAsDp()))
        LightRule()

        if (agenda.isEmpty()) {
            LightEmptyState(
                message = "Nothing on the calendar.\nTap a day to write on it, or ADD\nto photograph a paper one.",
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                LightSectionLabel("NEXT UP")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(agenda, key = { it.key }) { row ->
                        val day = NoteDates.dayTitle(row.epochDay).lowercase()
                            .replaceFirstChar { it.uppercase() }
                        LightListRow(
                            title = row.title,
                            sub = listOfNotNull(day, row.sub).joinToString(" · "),
                            detail = NoteDates.clock(row.minutes),
                            leading = if (row.passId != null) LightIcons.Ticket else null,
                            onClick = {
                                // A film goes to its stub; anything else to its day.
                                if (row.passId != null) vm.openPass(row.passId) else onOpenDay(row.epochDay)
                            },
                        )
                        LightRule()
                    }
                }
            }
        }
    }
}

/**
 * A line in NEXT UP. Notebook entries and LightPass films end up in the same list, because
 * "what is coming" is one question — but a film keeps its ticket id so the tap can go
 * where the barcode is.
 */
private data class AgendaRow(
    val epochDay: Long,
    val minutes: Int?,
    val title: String,
    val sub: String?,
    val passId: String?,
) {
    val key: String get() = passId?.let { "pass:$it" } ?: "$epochDay:$minutes:$title"
}

/**
 * One square.
 *
 * The day you are looking at is **inverted** — a filled block with the number knocked out
 * — because that is the one piece of state you need to find without hunting. Today is
 * outlined instead: worth marking, but you already know what today is. A day with
 * anything written on it carries a dot. All three read on a matte greyscale panel, and no
 * two of them are the same kind of mark, so they can stack on the same square.
 */
@Composable
private fun DayCell(
    epochDay: Long?,
    entries: Int,
    isToday: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val ink = if (isSelected) colors.background else colors.content
    Box(
        modifier
            .height(3.4f.verticalGridUnitsAsDp())
            .padding(0.1f.gridUnitsAsDp())
            .background(if (isSelected) colors.content else colors.background)
            .border(
                width = if (isToday && !isSelected) 1.dp else 0.dp,
                color = if (isToday && !isSelected) colors.content else colors.background,
            )
            .let { if (epochDay != null) it.lightClickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (epochDay == null) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LightText(
                text = NoteDates.of(epochDay).dayOfMonth.toString(),
                variant = LightTextVariant.Detail,
                color = ink,
            )
            Spacer(Modifier.height(0.15f.verticalGridUnitsAsDp()))
            Box(
                Modifier.height(0.3f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                if (entries > 0) {
                    Box(Modifier.size(0.24f.gridUnitsAsDp()).background(ink, CircleShape))
                }
            }
        }
    }
}
