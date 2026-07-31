package com.gios.lightnotebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    onOpenAgenda: () -> Unit,
    onSwipePage: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val rows by vm.canvasRows.collectAsStateWithLifecycle()
    val photoCovers by vm.photoCovers.collectAsStateWithLifecycle()
    val anchor by vm.selectedDay.collectAsStateWithLifecycle()
    val today = NoteDates.today()

    // Tickets are added in Movie Tickets and photographs are taken in Roll, so both are
    // re-read on arrival here rather than observed.
    LaunchedEffect(Unit) {
        vm.refreshShowings()
        vm.refreshPhotos()
    }

    var focusDay by remember { mutableStateOf(today) }
    // What "home" means, and the only thing that springs the planner back. Kept apart from
    // the selected day on purpose: opening a day selects it, and if that moved home the
    // zoom-in would immediately be undone.
    var homeAnchor by remember { mutableStateOf(today) }
    var homeRequest by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // The bars float over the planner, so the canvas needs their heights to know where the
    // clear band is. Measured rather than assumed: the bars are sized in grid units.
    val topBarHeight = 3f.gridUnitsAsDp()

    Box(modifier.fillMaxSize()) {
        var dayOpen by remember { mutableStateOf(false) }

        CalendarCanvas(
            rows = rows,
            photoCovers = photoCovers,
            today = today,
            anchorDay = homeAnchor,
            homeRequest = homeRequest,
            selectedDay = anchor,
            // Opening a day is no longer navigation — the cell becomes the day in place, so all
            // that changes is which day the view model has selected.
            onOpenDay = { day -> vm.selectDay(day) },
            onDayOpenChanged = { dayOpen = it },
            dayPane = { _, gestures, close ->
                DayPane(vm = vm, onClose = close, gestures = gestures)
            },
            onWindowChanged = { from, to -> vm.setCanvasWindow(from, to) },
            onFocusDayChanged = { focusDay = it },
            topInset = with(density) { topBarHeight.toPx() },
            // Nothing floats over the bottom any more, so nothing to keep clear of.
            bottomInset = 0f,
            onSwipePage = onSwipePage,
            modifier = Modifier.fillMaxSize(),
        )

        // Over the top, not above it: the planner slides underneath, which is what makes the
        // surface feel like it carries on past the chrome. Translucent rather than solid so
        // you can see it doing it. Gone entirely once a day has grown out of the surface —
        // the day carries its own header, and this one would sit on top of it.
        if (!dayOpen) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(LightThemeTokens.colors.background.copy(alpha = BAR_ALPHA)),
            ) {
                LightTopBar(
                    title = NoteDates.monthTitle(NoteDates.monthOf(focusDay)),
                    left = LightBarItem.Icon(
                        icon = LightIcons.List,
                        sizeUnits = 1.6f,
                        onClick = onOpenAgenda,
                    ),
                    right = LightBarItem.Text("TODAY") {
                        vm.jumpToToday()
                        // Moving home is what springs the planner back; nothing else does.
                        // The nonce is here because tapping TODAY while already anchored on
                        // today has to work too, and the day alone wouldn't have changed.
                        homeAnchor = NoteDates.today()
                        homeRequest++
                        dayOpen = false
                    },
                )
                // The weekday letters are the canvas's own — drawn in the transform so they line
                // up with the columns and grow with them, which a fixed seven-way Row cannot do
                // once a single day is wider than the screen.
                LightRule()
            }

        }
    }
}

/** Enough to read the bars against a busy planner, little enough to see it move under them. */
private const val BAR_ALPHA = 0.82f
