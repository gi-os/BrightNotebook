package com.gios.lightnotebook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.gios.lightnotebook.hw.WheelScroll
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightDayGestures
import com.gios.lightnotebook.ui.theme.lightTextStyle
import com.gios.lightnotebook.util.AgendaRow
import com.gios.lightnotebook.util.CanvasMath
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.Pt
import com.gios.lightnotebook.util.ZoomLevel
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The calendar as one endless wall planner: seven columns, weeks running downwards, pinch to
 * zoom and drag to go anywhere in time.
 *
 * Drawn in a single [Canvas] rather than as a grid of composables. Six visible weeks is
 * forty-two cells, each with a number and up to three lines of text, and recomposing that
 * lot on every frame of a drag is not something this phone will do smoothly. One draw pass
 * against a [TextMeasurer] will.
 *
 * Zoom has three stops (see [ZoomLevel]). Month is the floor and the home position; Week
 * adds the text of what is on each day; reaching Day hands over to the day screen itself,
 * which is a real screen with a keyboard and swiping between days — a canvas cell is the
 * wrong place to type.
 */
@Composable
fun CalendarCanvas(
    rows: Map<Long, List<AgendaRow>>,
    today: Long,
    anchorDay: Long,
    /** Told which day the surface has opened, so the day pane knows what to show. */
    onOpenDay: (Long) -> Unit,
    /** The day currently open, which the pane's own sliding moves. */
    selectedDay: Long,
    /** Bumped to ask for a spring back home even when [anchorDay] has not changed. */
    homeRequest: Int = 0,
    /**
     * The day itself, drawn into the cell and grown out of it. Given how far the zoom has
     * gone (0 at the week stop, 1 filling the screen) and a way to close.
     */
    dayPane: @Composable (progress: Float, gestures: Modifier, onClose: () -> Unit) -> Unit,
    onWindowChanged: (Long, Long) -> Unit,
    onFocusDayChanged: (Long) -> Unit,
    /**
     * Whether a day is currently grown out of the surface. The canvas owns this — it is the
     * thing that opens and closes days — and the screen hides its floating bars from it.
     * Pinching out closes a day without going through the screen at all, which is how the bars
     * came back missing when it tried to track this itself.
     */
    onDayOpenChanged: (Boolean) -> Unit = {},
    /** Height of the bar floating over the canvas, so home starts clear of it. */
    topInset: Float = 0f,
    /** Height of the footer under it, so a jump to today never lands behind NEXT UP. */
    bottomInset: Float = 0f,
    /** A sideways swipe at the month stop, where there is nowhere to pan: -1 back, +1 on. */
    onSwipePage: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // The origin week is fixed for the life of the screen: it is the zero of the vertical
    // axis, and moving it would teleport the surface under the user's finger.
    val originWeekStart = remember { CanvasMath.weekStart(anchorDay) }

    val dayNumberStyle = lightTextStyle(LightTextVariant.Detail)
    val entryStyle = lightTextStyle(LightTextVariant.Superfine)
    val monthStyle = lightTextStyle(LightTextVariant.Micro)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportWidth = with(density) { maxWidth.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }
        val cellWidth = viewportWidth / 7f
        // Square cells: at the month stop six rows then fill the height available, which is
        // what makes the home position look like the grid it replaces.
        val cellHeight = cellWidth

        val home = remember(anchorDay, cellHeight, topInset, bottomInset, viewportHeight) {
            CanvasMath.homeOffset(
                anchorDay = anchorDay,
                cellHeight = cellHeight,
                originWeekStart = originWeekStart,
                topInset = topInset,
                bottomInset = bottomInset,
                viewportHeight = viewportHeight,
            )
        }

        // Whether the surface has grown into a day. While it has, the pane is on top and the
        // canvas has handed its gestures over to it — the pane's own sideways slide moves
        // [selectedDay], which is what the cell geometry below follows.
        var dayOpen by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(ZoomLevel.Month.scale) }
        var offset by remember {
            mutableStateOf(
                Offset(
                    CanvasMath.clampOffsetX(home.x, ZoomLevel.Month.scale, viewportWidth, cellWidth),
                    home.y,
                ),
            )
        }

        fun place(nextScale: Float, nextOffset: Offset) {
            val clamped = CanvasMath.clampScale(nextScale)
            scale = clamped
            offset = Offset(
                CanvasMath.clampOffsetX(nextOffset.x, clamped, viewportWidth, cellWidth),
                nextOffset.y,
            )
        }

        /**
         * The wheel, panning the planner.
         *
         * There is no scroller here to hand [WheelScroll] — the planner is one Canvas with a
         * transform of its own — so the vertical offset is dressed up as a [ScrollableState]
         * instead. The surface is endless downwards, so every notch is consumed and there is
         * no edge to run out at. Zoom is left to the fingers: a wheel with two directions
         * cannot both pan and zoom, and panning is the thing you do constantly.
         *
         * Off while a day is open, because the pane on top of the surface has its own list.
         */
        val surface = remember(viewportWidth, cellWidth) {
            ScrollableState { delta ->
                place(scale, Offset(offset.x, offset.y - delta))
                delta
            }
        }
        WheelScroll(surface, active = !dayOpen)

        /** Animates to a scale and offset — the snap, the spring home, and the hand-over. */
        suspend fun animateTo(
            targetScale: Float,
            targetOffset: Offset,
            durationMs: Int = SNAP_MS,
        ) {
            val fromScale = scale
            val fromOffset = offset
            val progress = Animatable(0f)
            // Eased rather than linear: a snap that decelerates reads as the surface settling,
            // where a linear one reads as a jump cut.
            progress.animateTo(1f, tween(durationMillis = durationMs, easing = FastOutSlowInEasing)) {
                val t = value
                place(
                    fromScale + (targetScale - fromScale) * t,
                    Offset(
                        fromOffset.x + (targetOffset.x - fromOffset.x) * t,
                        fromOffset.y + (targetOffset.y - fromOffset.y) * t,
                    ),
                )
            }
        }

        /**
         * Opens a day by growing its own cell into the screen. No navigation, no second
         * screen: the rectangle you were pinching *is* the day, and it keeps being it.
         */
        fun openDay(day: Long) {
            onOpenDay(day)
            dayOpen = true
            onDayOpenChanged(true)
            scope.launch {
                val end = ZoomLevel.Day.scale
                animateTo(
                    targetScale = end,
                    // The column is aligned to the viewport rather than centred, because at this
                    // scale a cell *is* the viewport — that alignment is what makes a slide of
                    // one screen a slide of exactly one day.
                    targetOffset = Offset(
                        CanvasMath.offsetXForColumn(CanvasMath.columnOf(day), cellWidth * end),
                        viewportHeight / 2f -
                            (CanvasMath.weekIndexOf(day, originWeekStart) + 0.5f) *
                            cellHeight * end,
                    ),
                    durationMs = HANDOVER_MS,
                )
            }
        }

        /**
         * A day open and being slid sideways.
         *
         * At the day stop a cell is exactly a screen wide, so the pan is applied to the surface
         * untouched: the open day slides off, its neighbour's own square slides in behind it,
         * and the pane rides along because it is anchored to the cell. Off the end of the week,
         * the surface goes diagonally to the row above or below, which is where that day
         * actually lives on a wall planner.
         */
        fun slideDay(dx: Float) {
            if (!dayOpen) return
            place(scale, Offset(offset.x + dx, offset.y))
        }

        fun settleSlide() {
            if (!dayOpen) return
            val span = cellWidth * scale
            val week = CanvasMath.weekIndexOf(selectedDay, originWeekStart)
            val (column, wrappedWeek, day) = CanvasMath.wrapSlide(
                column = CanvasMath.openColumn(offset.x, span),
                week = week,
                originWeekStart = originWeekStart,
            )
            onOpenDay(day)
            scope.launch {
                animateTo(
                    targetScale = scale,
                    targetOffset = Offset(
                        CanvasMath.offsetXForColumn(column, span),
                        viewportHeight / 2f - (wrappedWeek + 0.5f) * cellHeight * scale,
                    ),
                    durationMs = SLIDE_SETTLE_MS,
                )
            }
        }

        /** Shrinks the day back into its square on the planner. */
        fun closeDay() {
            if (!dayOpen) return
            val day = selectedDay
            // Announced up front, not after the animation: the bars belong to the planner, and
            // they should be back on their way in rather than appearing once it has finished.
            onDayOpenChanged(false)
            scope.launch {
                val centre = Pt(
                    x = (CanvasMath.columnOf(day) + 0.5f) * cellWidth,
                    y = (CanvasMath.weekIndexOf(day, originWeekStart) + 0.5f) * cellHeight,
                )
                animateTo(
                    targetScale = ZoomLevel.Week.scale,
                    targetOffset = Offset(
                        viewportWidth / 2f - centre.x * ZoomLevel.Week.scale,
                        viewportHeight / 2f - centre.y * ZoomLevel.Week.scale,
                    ),
                    durationMs = HANDOVER_MS,
                )
                dayOpen = false
            }
        }

        /** Runs when the fingers leave: snap to a stop, spring home, or hand over to a day. */
        fun settle() {
            val target = CanvasMath.snapTarget(scale)
            val pt = Pt(offset.x, offset.y)
            when {
                CanvasMath.isNearHome(target, pt, home, cellWidth) -> scope.launch {
                    animateTo(ZoomLevel.Month.scale, Offset(home.x, home.y))
                }

                target >= ZoomLevel.Day.scale -> openDay(
                    CanvasMath.focusedDay(
                        offset = pt,
                        scale = scale,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        originWeekStart = originWeekStart,
                    ),
                )

                target != scale -> scope.launch {
                    val focus = Offset(viewportWidth / 2f, viewportHeight / 2f)
                    val moved = CanvasMath.offsetForZoom(
                        Pt(offset.x, offset.y),
                        scale,
                        target,
                        Pt(focus.x, focus.y),
                    )
                    animateTo(target, Offset(moved.x, moved.y))
                }
            }
        }

        // TODAY re-anchors home and pulls the surface back to it.
        //
        // Keyed on [anchorDay], which changes *only* when somebody asks to go somewhere —
        // not when a day is opened. It used to be the selected day, and since opening a day
        // selects it, zooming in fired this and threw the view straight back out to the month.
        LaunchedEffect(anchorDay, homeRequest) {
            dayOpen = false
            onDayOpenChanged(false)
            animateTo(ZoomLevel.Month.scale, Offset(home.x, home.y))
        }

        // Report the window and the month under the middle, both throttled by only firing
        // when the derived value actually changes.
        LaunchedEffect(viewportHeight, cellHeight) {
            snapshotFlow { scale to offset }.collect { (currentScale, currentOffset) ->
                val weeks = CanvasMath.visibleWeeks(
                    offsetY = currentOffset.y,
                    scale = currentScale,
                    viewportHeight = viewportHeight,
                    cellHeight = cellHeight,
                )
                onWindowChanged(
                    CanvasMath.dayAt(0, weeks.first, originWeekStart),
                    CanvasMath.dayAt(6, weeks.last, originWeekStart),
                )
                onFocusDayChanged(
                    CanvasMath.focusedDay(
                        offset = Pt(currentOffset.x, currentOffset.y),
                        scale = currentScale,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        originWeekStart = originWeekStart,
                    ),
                )
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                // Keyed on dayOpen so the handlers are torn down while the pane has the
                // gestures, rather than both fighting over the same fingers.
                .pointerInput(dayOpen) {
                    if (dayOpen) return@pointerInput
                    detectTapGestures(
                        onTap = { position ->
                            // Tapping a day zooms into it as well, rather than cutting: same
                            // gesture, same thread of where you are.
                            CanvasMath.dayAtScreen(
                                point = Pt(position.x, position.y),
                                offset = Pt(offset.x, offset.y),
                                scale = scale,
                                cellWidth = cellWidth,
                                cellHeight = cellHeight,
                                originWeekStart = originWeekStart,
                            )?.let { openDay(it) }
                        },
                        onDoubleTap = { position ->
                            // Double tap zooms one stop in about the point tapped, which is
                            // the quick way through to a day without a two-finger stretch.
                            val next = when (CanvasMath.levelFor(scale)) {
                                ZoomLevel.Month -> ZoomLevel.Week.scale
                                else -> ZoomLevel.Day.scale
                            }
                            val moved = CanvasMath.offsetForZoom(
                                Pt(offset.x, offset.y),
                                scale,
                                next,
                                Pt(position.x, position.y),
                            )
                            place(next, Offset(moved.x, moved.y))
                            settle()
                        },
                    )
                }
                .pointerInput(dayOpen) {
                    if (dayOpen) return@pointerInput
                    // Hand-rolled rather than detectTransformGestures, which has no
                    // end-of-gesture hook — and the snap is the whole feel of this thing.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var pagingX = 0f
                        var paged = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                // At the month stop the seven columns already fill the width, so
                                // a sideways drag has nowhere to pan — it changes page instead.
                                val atMonth = scale <= ZoomLevel.Month.scale + 0.01f
                                val sideways = abs(pan.x) > abs(pan.y) * 1.5f
                                if (atMonth && zoom == 1f && sideways) {
                                    pagingX += pan.x
                                    if (!paged && abs(pagingX) > viewportWidth * PAGE_FRACTION) {
                                        paged = true
                                        onSwipePage(if (pagingX < 0f) 1 else -1)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else {
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val nextScale = CanvasMath.clampScale(scale * zoom)
                                    val zoomed = CanvasMath.offsetForZoom(
                                        Pt(offset.x, offset.y),
                                        scale,
                                        nextScale,
                                        Pt(centroid.x, centroid.y),
                                    )
                                    place(nextScale, Offset(zoomed.x + pan.x, zoomed.y + pan.y))
                                    moved = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (moved && !paged) settle()
                    }
                },
        ) {
            val level = CanvasMath.levelFor(scale)
            val weeks = CanvasMath.visibleWeeks(offset.y, scale, viewportHeight, cellHeight)
            val cellW = cellWidth * scale
            val cellH = cellHeight * scale

            // The month the middle of the screen is in. Everything outside it is drawn down in
            // the secondary colour, so a wall planner that runs on forever still reads as
            // "this is August" rather than as one undifferentiated ladder of numbers.
            val focusMonth = YearMonth.from(
                LocalDate.ofEpochDay(
                    CanvasMath.focusedDay(
                        offset = Pt(offset.x, offset.y),
                        scale = scale,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        originWeekStart = originWeekStart,
                    ),
                ),
            )

            for (week in weeks) {
                for (column in 0..6) {
                    val day = CanvasMath.dayAt(column, week, originWeekStart)
                    val left = column * cellW + offset.x
                    val top = week * cellH + offset.y
                    if (top > viewportHeight || top + cellH < 0f) continue

                    drawDay(
                        day = day,
                        rows = rows[day].orEmpty(),
                        isToday = day == today,
                        inFocusMonth = YearMonth.from(LocalDate.ofEpochDay(day)) == focusMonth,
                        left = left,
                        top = top,
                        width = cellW,
                        height = cellH,
                        level = level,
                        scale = scale,
                        measurer = measurer,
                        dayNumberStyle = dayNumberStyle,
                        entryStyle = entryStyle,
                        monthStyle = monthStyle,
                        content = colors.content,
                        // contentFaint, not contentSecondary: half the luminance of #BBBBBB, so
                        // the neighbouring months sit clearly behind the one you are reading
                        // instead of competing with it.
                        dimmed = colors.contentFaint,
                        background = colors.background,
                        rule = colors.rule,
                    )
                }
            }
        }

        // The day, drawn as the cell it came out of.
        //
        // The pane is always composed at full size and then mapped onto the cell's rectangle
        // with a layer transform, so what grows into the screen is the day itself rather than
        // a screen that replaced it. At progress 1 the transform is the identity and this is
        // simply the day view.
        val progress = ((scale - ZoomLevel.Week.scale) /
            (ZoomLevel.Day.scale - ZoomLevel.Week.scale)).coerceIn(0f, 1f)
        if (dayOpen && progress > 0f) {
            val eased = FastOutSlowInEasing.transform(progress)
            val cell = CanvasMath.cellTopLeft(
                epochDay = selectedDay,
                offset = Pt(offset.x, offset.y),
                scale = scale,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                originWeekStart = originWeekStart,
            )
            val fromWidth = cellWidth * scale
            val fromHeight = cellHeight * scale
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = lerp(fromWidth / viewportWidth, 1f, eased)
                        scaleY = lerp(fromHeight / viewportHeight, 1f, eased)
                        // The x stays glued to the cell even when fully open: at the day stop a
                        // cell is a screen wide, so this is what carries the pane off-screen as
                        // the planner slides and lets the next day's square arrive behind it.
                        translationX = cell.x
                        translationY = lerp(cell.y, 0f, eased)
                        alpha = lerp(0.35f, 1f, eased)
                    },
            ) {
                dayPane(
                    progress,
                    Modifier.lightDayGestures(
                        onSlide = { dx -> slideDay(dx) },
                        onSlideEnd = { settleSlide() },
                        onPinchOut = { closeDay() },
                    ),
                ) { closeDay() }
            }
        }
    }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private const val SNAP_MS = 220
private const val HANDOVER_MS = 190

/** Short: the finger has already done most of the travel, this only tidies the last of it. */
private const val SLIDE_SETTLE_MS = 130

/** How far sideways counts as turning the page rather than a stray drag. */
private const val PAGE_FRACTION = 0.16f

/** Below this cell width there is no room for words, whatever the zoom says. */
private const val TEXT_MIN_CELL_PX = 150f
private const val MAX_LINES_PER_CELL = 4

private fun DrawScope.drawDay(
    day: Long,
    rows: List<AgendaRow>,
    isToday: Boolean,
    inFocusMonth: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    level: ZoomLevel,
    scale: Float,
    measurer: TextMeasurer,
    dayNumberStyle: TextStyle,
    entryStyle: TextStyle,
    monthStyle: TextStyle,
    content: androidx.compose.ui.graphics.Color,
    dimmed: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    rule: androidx.compose.ui.graphics.Color,
) {
    val date = LocalDate.ofEpochDay(day)
    val inset = width * 0.06f
    val zoomedIn = level != ZoomLevel.Month

    // Cell borders instead of a background: at any zoom the grid has to stay a grid. Zoomed
    // in they brighten, because once a cell is the size of a card the thing you need to see
    // at a glance is where one day stops and the next starts.
    drawRect(
        color = if (zoomedIn) rule.copy(alpha = 1f) else rule,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (zoomedIn) 2f else 1f),
    )

    if (isToday) {
        drawRect(color = content, topLeft = Offset(left, top), size = Size(width, height))
    }

    // Days spilling in from the months either side are drawn down rather than hidden: they
    // are still real days you can write on, just not the one you are reading.
    val ink = when {
        isToday -> background
        inFocusMonth -> content
        else -> dimmed
    }

    // The 1st carries its month, so panning through a year never loses the place.
    val heading = if (date.dayOfMonth == 1) {
        "1 ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)}"
    } else {
        date.dayOfMonth.toString()
    }
    val numberSize = (dayNumberStyle.fontSize.value * scale).coerceAtMost(MAX_NUMBER_SP)
    val number = measurer.measure(
        text = heading,
        style = dayNumberStyle.copy(color = ink, fontSize = numberSize.sp),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
    drawText(number, topLeft = Offset(left + inset, top + inset))

    if (level == ZoomLevel.Month || width < TEXT_MIN_CELL_PX) {
        // Just a dot: something is written here.
        if (rows.isNotEmpty()) {
            drawCircle(
                color = ink,
                radius = (width * 0.035f).coerceAtLeast(1.5f),
                center = Offset(left + width / 2f, top + height - inset - width * 0.05f),
            )
        }
        return
    }

    // A rule under the number turns the cell into a little page with a heading — the clearest
    // way to say "this is a different day" once there is text in both of them.
    val headerY = top + inset + number.size.height + inset * 0.4f
    drawLine(
        color = if (isToday) background else rule,
        start = Offset(left + inset, headerY),
        end = Offset(left + width - inset, headerY),
        strokeWidth = 2f,
    )

    if (rows.isEmpty()) return

    var y = headerY + inset * 0.6f
    val available = top + height - inset
    val textSize = (entryStyle.fontSize.value * scale * 0.75f)
        .coerceIn(MIN_ENTRY_SP, MAX_ENTRY_SP)

    rows.take(MAX_LINES_PER_CELL).forEach { row ->
        if (y >= available) return@forEach
        val clock = NoteDates.clock(row.minutes)
        val line = listOfNotNull(clock, row.title).joinToString(" ")
        val measured = measurer.measure(
            text = line,
            style = entryStyle.copy(color = ink, fontSize = textSize.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (width - inset * 2).roundToInt().coerceAtLeast(1)),
        )
        if (y + measured.size.height > available) return@forEach
        drawText(measured, topLeft = Offset(left + inset, y))
        y += measured.size.height * 1.1f
    }

    val hidden = rows.size - MAX_LINES_PER_CELL
    if (hidden > 0 && y < available) {
        val more = measurer.measure(
            text = "+$hidden",
            style = monthStyle.copy(color = ink, fontSize = textSize.sp),
            maxLines = 1,
        )
        drawText(more, topLeft = Offset(left + inset, y))
    }
}

private const val MAX_NUMBER_SP = 34f
private const val MIN_ENTRY_SP = 8f
private const val MAX_ENTRY_SP = 15f
