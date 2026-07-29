package com.gios.lightnotebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.NoteDates

/**
 * The calendar tab: a zoomable wall planner, a weekday header pinned above it, and the way
 * through to the agenda.
 *
 * The screen owns almost nothing now — [CalendarCanvas] holds the transform, and the title
 * follows whatever is in the middle of the surface rather than a month this screen decides.
 */
@Composable
fun CalendarScreen(
    vm: NotebookViewModel,
    onOpenDay: (Long) -> Unit,
    onOpenAgenda: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by vm.canvasRows.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val showings by vm.showings.collectAsStateWithLifecycle()
    val anchor by vm.selectedDay.collectAsStateWithLifecycle()
    val today = NoteDates.today()

    // Tickets are added in the other app, so re-read them on arrival here.
    LaunchedEffect(Unit) { vm.refreshShowings() }

    var focusDay by remember { mutableStateOf(today) }
    val anythingAhead = upcoming.isNotEmpty() || showings.any { it.epochDay >= today }
    val next = upcoming.firstOrNull()

    Column(modifier.fillMaxSize()) {
        LightTopBar(
            title = NoteDates.monthTitle(NoteDates.monthOf(focusDay)),
            left = LightBarItem.Icon(LightIcons.List, sizeUnits = 1.6f, onClick = onOpenAgenda),
            // The canvas takes its home from the selected day, so this springs it back.
            right = LightBarItem.Text("TODAY", onClick = { vm.jumpToToday() }),
        )
        LightRule()

        // Pinned: the columns keep their meaning at every zoom, and it is the one thing that
        // tells you which way is Wednesday once the numbers are the size of a thumbnail.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = lightInset(),
                    end = lightInset(),
                    top = 0.3f.verticalGridUnitsAsDp(),
                    bottom = 0.3f.verticalGridUnitsAsDp(),
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

        CalendarCanvas(
            rows = rows,
            today = today,
            anchorDay = anchor,
            onOpenDay = onOpenDay,
            onWindowChanged = { from, to -> vm.setCanvasWindow(from, to) },
            onFocusDayChanged = { focusDay = it },
            modifier = Modifier.weight(1f),
        )

        LightRule()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = lightInset(), vertical = 0.6f.verticalGridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.4f.verticalGridUnitsAsDp()),
        ) {
            LightWideButton(
                label = "NEXT UP",
                filled = anythingAhead,
                onClick = onOpenAgenda,
            )
            if (next != null) {
                // One line of what is actually next, so the button is not a mystery box.
                LightText(
                    text = listOfNotNull(NoteDates.clock(next.startMinutes), next.text)
                        .joinToString(" · "),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
