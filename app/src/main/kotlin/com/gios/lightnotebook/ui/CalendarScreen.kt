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
import androidx.compose.runtime.getValue
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
    val today = NoteDates.today()

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

        if (upcoming.isEmpty()) {
            LightEmptyState(
                message = "Nothing on the calendar.\nTap a day to write on it, or ADD\nto photograph a paper one.",
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                LightSectionLabel("NEXT UP")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(upcoming, key = { it.id }) { entry ->
                        LightListRow(
                            title = entry.text,
                            sub = NoteDates.dayTitle(entry.epochDay).lowercase()
                                .replaceFirstChar { it.uppercase() },
                            detail = NoteDates.clock(entry.startMinutes),
                            onClick = { onOpenDay(entry.epochDay) },
                        )
                        LightRule()
                    }
                }
            }
        }
    }
}

/**
 * One square. Today is inverted, the selected day is outlined, and a day with anything
 * written on it carries a dot — three states that all survive a greyscale matte panel.
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
    Box(
        modifier
            .height(3.4f.verticalGridUnitsAsDp())
            .padding(0.1f.gridUnitsAsDp())
            .background(if (isToday) colors.content else colors.background)
            .border(
                width = if (isSelected && !isToday) 1.dp else 0.dp,
                color = if (isSelected && !isToday) colors.content else colors.background,
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
                color = if (isToday) colors.background else colors.content,
            )
            Spacer(Modifier.height(0.15f.verticalGridUnitsAsDp()))
            Box(
                Modifier.height(0.3f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                if (entries > 0) {
                    Box(
                        Modifier
                            .size(0.24f.gridUnitsAsDp())
                            .background(
                                if (isToday) colors.background else colors.content,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
