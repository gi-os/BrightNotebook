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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
    onOpenAgenda: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val counts by vm.dayCounts.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val showings by vm.showings.collectAsStateWithLifecycle()
    val today = NoteDates.today()

    // Tickets are added in the other app, so re-read them on arrival here.
    LaunchedEffect(Unit) { vm.refreshShowings() }

    val ahead = upcoming.size + showings.count { it.epochDay >= today }
    val next = upcoming.firstOrNull()

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
                        modifier = Modifier.weight(1f),
                        onClick = { if (day != null) onOpenDay(day) },
                    )
                }
            }
        }

        Spacer(Modifier.height(0.8f.verticalGridUnitsAsDp()))
        LightRule()

        // NEXT UP is a door, not a list. Squeezed under the grid it was three rows of small
        // type fighting a 42-cell grid for attention and losing.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = lightInset(), vertical = 1f.verticalGridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.6f.verticalGridUnitsAsDp()),
        ) {
            LightWideButton(
                label = if (ahead > 0) "NEXT UP · $ahead" else "NEXT UP",
                filled = ahead > 0,
                onClick = onOpenAgenda,
            )
            if (next != null) {
                // One line of what is actually next, so the button is not a mystery box.
                LightText(
                    text = listOfNotNull(
                        NoteDates.clock(next.startMinutes),
                        next.text,
                    ).joinToString(" · "),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                LightText(
                    text = NoteDates.dayTitle(next.epochDay),
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
            }
        }
    }
}

/**
 * One square.
 *
 * Today is inverted; every other day is left plain. There was a version of this that also
 * boxed the day you last opened, and it was noise — the box outlived the visit and said
 * nothing you needed on the way back.
 */
@Composable
private fun DayCell(
    epochDay: Long?,
    entries: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val ink = if (isToday) colors.background else colors.content
    Box(
        modifier
            .height(3.4f.verticalGridUnitsAsDp())
            .padding(0.1f.gridUnitsAsDp())
            .background(if (isToday) colors.content else colors.background)
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
