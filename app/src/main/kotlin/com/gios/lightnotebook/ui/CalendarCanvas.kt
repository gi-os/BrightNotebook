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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.dp
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
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.PhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gios.lightnotebook.util.Daylight

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
    /**
     * One photograph per day — the earliest — for the mark in a cell and the picture behind it.
     *
     * Zoomed out this only draws a mark, because forty-two cells re-measuring text per drag
     * frame is the whole reason this is one `Canvas` instead of composables and decoding
     * bitmaps into it would undo that at the first pinch. **Once the cells carry entries the
     * arithmetic changes**: three columns of four are a dozen cells, not forty-two, and a dozen
     * small thumbnails decoded *once, off the draw path* and held as `ImageBitmap` cost nothing
     * per frame. That is the whole trick — the draw only ever reads an already-decoded map, and
     * a day whose picture has not arrived draws no picture.
     */
    photoSummaries: Map<Long, PhotoLibrary.DaySummary>,
    /**
     * Daylight per visible day, drawn as a band down each cell.
     *
     * On the planner rather than on a day screen because this is the one thing a wall calendar can
     * say that a single day cannot: pan a year and the band grows and shrinks, and you can see the
     * winter. Computed in the view model for the whole window — trigonometry is cheap, but not a
     * year of it per frame.
     */
    daylightByDay: Map<Long, Daylight.Result>,
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
    val weekdayStyle = lightTextStyle(LightTextVariant.Superfine)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportWidth = with(density) { maxWidth.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }
        val cellWidth = viewportWidth / 7f
        // Square cells: at the month stop six rows then fill the height available, which is
        // what makes the home position look like the grid it replaces.
        val cellHeight = cellWidth

        // The weekday letters are drawn by the canvas rather than laid out above it, so they
        // track the columns and grow with them. That band is part of the top inset.
        val headerHeight = with(density) { HEADER_DP.dp.toPx() }
        val home = remember(anchorDay, cellHeight, topInset, bottomInset, viewportHeight) {
            CanvasMath.homeOffset(
                anchorDay = anchorDay,
                cellHeight = cellHeight,
                originWeekStart = originWeekStart,
                topInset = topInset + headerHeight,
                bottomInset = bottomInset,
                viewportHeight = viewportHeight,
            )
        }

        // Whether the surface has grown into a day. While it has, the pane is on top and the
        // canvas has handed its gestures over to it — the pane's own sideways slide moves
        // [selectedDay], which is what the cell geometry below follows.
        var dayOpen by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(ZoomLevel.Month.scale) }

        // Decided out here and not in the draw, because loading is a coroutine and drawing is
        // not. `showsEntriesAtScale` is the same question the draw asks, answered from the scale
        // alone — the viewport cancels out of it — so the two cannot disagree about which cells
        // want a picture.
        val wantsCovers = CanvasMath.showsEntriesAtScale(scale)
        val coverBitmaps = remember { mutableStateMapOf<Long, ImageBitmap>() }
        val context = LocalContext.current
        val coverPx = with(LocalDensity.current) { COVER_REQUEST_DP.dp.roundToPx() }

        LaunchedEffect(photoSummaries, wantsCovers, coverPx) {
            if (!wantsCovers) {
                // Dropped rather than kept: zooming out is the moment there is nothing to show
                // them in, and a map of every cell a long pan crossed is a slow leak of bitmaps.
                coverBitmaps.clear()
                return@LaunchedEffect
            }
            coverBitmaps.keys.retainAll(photoSummaries.keys)
            for ((day, summary) in photoSummaries) {
                if (coverBitmaps.containsKey(day)) continue
                val bitmap = withContext(Dispatchers.IO) {
                    PhotoLibrary.thumbnail(context, summary.cover, coverPx)
                } ?: continue
                // Published one at a time, so the cells fill in as they arrive instead of the
                // whole grid waiting on the slowest thumbnail.
                coverBitmaps[day] = bitmap.asImageBitmap()
            }
        }
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
            val weeks = CanvasMath.visibleWeeks(offset.y, scale, viewportHeight, cellHeight)
            val cellW = cellWidth * scale
            val cellH = cellHeight * scale

            // Detail follows how many days are across the screen, not the zoom number: four days
            // across means four days you should be able to read.
            val across = CanvasMath.daysAcross(cellW, viewportWidth)
            val showEntries = CanvasMath.showsEntries(across)
            val showTimes = CanvasMath.showsTimes(across)

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
                        hasPhotos = day in photoSummaries,
                        cover = coverBitmaps[day],
                        daylight = daylightByDay[day],
                        // The day's activity span: earliest to latest of anything on it. Entries
                        // come from the rows already loaded, photographs from the summary, so
                        // neither costs an extra query.
                        activity = activitySpan(rows[day].orEmpty(), photoSummaries[day]),
                        // Struck through once it has gone. Not today, which is the inverted
                        // block, and not a day already carrying a photograph — a line across a
                        // picture reads as damage to the picture.
                        struck = day < today,
                        isToday = day == today,
                        inFocusMonth = YearMonth.from(LocalDate.ofEpochDay(day)) == focusMonth,
                        left = left,
                        top = top,
                        width = cellW,
                        height = cellH,
                        showEntries = showEntries,
                        showTimes = showTimes,
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

            // The weekday letters, over the top of the surface and lined up with the columns
            // underneath — so at seven days across there are seven of them, and zoomed in there
            // are two, wide apart, over the days they belong to.
            drawRect(
                color = colors.background.copy(alpha = HEADER_ALPHA),
                topLeft = Offset(0f, topInset),
                size = Size(viewportWidth, headerHeight),
            )
            val letterSize = (weekdayStyle.fontSize.value * (1f + (scale - 1f) * 0.35f))
                .coerceIn(MIN_WEEKDAY_SP, MAX_WEEKDAY_SP)
            val firstColumn = floor(-offset.x / cellW).toInt().coerceAtLeast(0)
            val lastColumn = floor((viewportWidth - offset.x) / cellW).toInt().coerceAtMost(6)
            for (column in firstColumn..lastColumn) {
                val initial = NoteDates.weekdayInitials.getOrNull(column) ?: continue
                val measured = measurer.measure(
                    text = initial,
                    style = weekdayStyle.copy(
                        color = colors.contentSecondary,
                        fontSize = letterSize.sp,
                    ),
                    maxLines = 1,
                )
                val centre = column * cellW + offset.x + cellW / 2f
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = centre - measured.size.width / 2f,
                        y = topInset + (headerHeight - measured.size.height) / 2f,
                    ),
                )
            }
            drawLine(
                color = colors.rule,
                start = Offset(0f, topInset + headerHeight),
                end = Offset(viewportWidth, topInset + headerHeight),
                strokeWidth = 2f,
            )
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

/**
 * Earliest to latest of anything on a day, in minutes from midnight, or null when there is nothing
 * or only one moment.
 *
 * A single point is not a span — drawing a one-pixel line for a day with one photograph on it says
 * less than drawing nothing, and invites the reading that you were up for a minute.
 */
private fun activitySpan(rows: List<AgendaRow>, photos: PhotoLibrary.DaySummary?): IntRange? {
    val times = buildList {
        rows.forEach { row -> row.minutes?.let { add(it) } }
        photos?.let { add(it.firstMinutes); add(it.lastMinutes) }
    }
    if (times.size < 2) return null
    val from = times.min()
    val to = times.max()
    return if (from == to) null else from..to
}

/** Both marks read vertical position as time of day, so both divide by a day's minutes. */
private const val MINUTES_IN_DAY_F = 1440f

/** Enough to see as light, faint enough that the day number stays the brightest thing in the cell. */
private const val DAYLIGHT_ALPHA = 0.22f

/** A stripe down the left of the cell, not the whole width: the text needs the rest. */
private const val DAYLIGHT_WIDTH = 0.16f

/**
 * A cell's photograph, centre-cropped to fill it.
 *
 * `loadThumbnail` returns whatever aspect the photograph was, and a cell is nearly square, so
 * the source rectangle is narrowed to the cell's shape before it is drawn — the same
 * centre-crop a thumbnail in the roll gets. Letting it stretch instead is immediately obvious
 * on faces, and letterboxing it leaves two black bands that read as a broken image.
 */
private fun DrawScope.drawCover(
    cover: ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    if (width <= 0f || height <= 0f || cover.width <= 0 || cover.height <= 0) return

    val cellAspect = width / height
    val srcAspect = cover.width.toFloat() / cover.height.toFloat()
    val srcW: Int
    val srcH: Int
    if (srcAspect > cellAspect) {
        // Source is wider than the cell: keep its full height and take a slice of the width.
        srcH = cover.height
        srcW = (cover.height * cellAspect).toInt().coerceIn(1, cover.width)
    } else {
        srcW = cover.width
        srcH = (cover.width / cellAspect).toInt().coerceIn(1, cover.height)
    }

    drawImage(
        image = cover,
        srcOffset = IntOffset((cover.width - srcW) / 2, (cover.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
    )
}

/**
 * How dark the photograph is pushed before text is drawn over it.
 *
 * Chosen high deliberately. This is a 1-bit-feeling greyscale panel read at arm's length, and
 * the failure mode is not "the picture is a bit dim" — it is a day whose entries cannot be read
 * at all, which is the actual job of the cell.
 */
private const val COVER_SCRIM_ALPHA = 0.68f

/** Light enough to read as a mark on the cell rather than as a line through the text. */
private const val STRUCK_ALPHA = 0.55f

/**
 * The pixel size asked of MediaStore for a cell background.
 *
 * A cell at the Week stop is roughly a third of a ~410dp screen, so this is deliberately a
 * little larger than it is drawn and far smaller than the panel — big enough that it does not
 * soften under the scrim, small enough that a dozen of them are nothing.
 */
private const val COVER_REQUEST_DP = 180

/** How far sideways counts as turning the page rather than a stray drag. */
private const val PAGE_FRACTION = 0.16f

/** The weekday letters' band, in dp. Part of the top inset, drawn over the surface. */
private const val HEADER_DP = 18
private const val HEADER_ALPHA = 0.82f
private const val MIN_WEEKDAY_SP = 10f
private const val MAX_WEEKDAY_SP = 22f

private fun DrawScope.drawDay(
    day: Long,
    rows: List<AgendaRow>,
    hasPhotos: Boolean,
    cover: ImageBitmap?,
    daylight: Daylight.Result?,
    activity: IntRange?,
    struck: Boolean,
    isToday: Boolean,
    inFocusMonth: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    showEntries: Boolean,
    showTimes: Boolean,
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
    val zoomedIn = showEntries

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

    // The day's first photograph, behind everything written on it.
    //
    // Never on today: today is *the inverted block*, and that is how this grid says "here" —
    // a picture in it would cost the one cell whose state has to be unmistakable.
    if (cover != null && !isToday) {
        drawCover(cover, left, top, width, height)
        // Knocked back hard, and this is the whole reason it is legible. At full brightness a
        // photograph and white text are the same luminance in patches, and on a greyscale panel
        // there is no colour left to separate them. Past this it reads as texture behind the
        // day rather than as a picture in it — which is the intent: the photograph is a
        // reminder of the day, not the content of the cell.
        drawRect(
            color = background.copy(alpha = COVER_SCRIM_ALPHA),
            topLeft = Offset(left, top),
            size = Size(width, height),
        )
    }

    // **Down each cell, the day runs from its cutover at the top to the next at the bottom** —
    // four in the morning to four in the morning, so a late night runs down the cell it belonged to
    // instead of reappearing at the top of the next one. Vertical position is time of day, which is
    // the only way a cell this size can carry a whole day's shape.
    //
    // From the middle stop onwards, never on the month grid: at three millimetres they are two more
    // marks in a square already carrying a number, a dot and a strike, and the grid stops being a
    // grid. Given a cell with room, they are the frame the entries below are positioned against.
    if (showEntries && daylight is Daylight.Result.Times) {
        val top0 = top + height * (daylight.sunriseMinutes / MINUTES_IN_DAY_F)
        val bottom0 = top + height * (daylight.sunsetMinutes / MINUTES_IN_DAY_F)
        drawRect(
            // Faint fill, not an outline: this is the *amount* of light, so it has to have area.
            color = (if (isToday) background else content).copy(alpha = DAYLIGHT_ALPHA),
            topLeft = Offset(left + inset * 0.5f, top0),
            size = Size(width * DAYLIGHT_WIDTH, (bottom0 - top0).coerceAtLeast(1f)),
        )
    }

    // The span you were up and doing things, over the light you had to do it in.
    if (showEntries && activity != null && !activity.isEmpty()) {
        val from = top + height * (activity.first / MINUTES_IN_DAY_F)
        val to = top + height * (activity.last / MINUTES_IN_DAY_F)
        drawLine(
            color = if (isToday) background else content,
            start = Offset(left + inset * 0.5f + width * DAYLIGHT_WIDTH * 0.5f, from),
            end = Offset(left + inset * 0.5f + width * DAYLIGHT_WIDTH * 0.5f, to.coerceAtLeast(from + 1f)),
            strokeWidth = 2f,
        )
    }

    // Crossed out once the day has gone.
    //
    // One diagonal, not two: an X reads as cancelled or wrong, where a single stroke reads as
    // spent, which is what a day behind you is. Suppressed over a photograph, where a line
    // across the cell reads as damage to the picture rather than as a mark on the calendar.
    if (struck && cover == null) {
        drawLine(
            color = (if (isToday) background else rule).copy(alpha = STRUCK_ALPHA),
            start = Offset(left, top + height),
            end = Offset(left + width, top),
            strokeWidth = 2f,
        )
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

    // **What is true of the whole day goes beside its date**, for the same reason it does on the day
    // screen: an all-day thing has no time, so anywhere on the column below would be claiming one.
    // Only when there is room to read it — on a month cell the number is already most of the square.
    if (showEntries) {
        val allDay = rows.filter { it.minutes == null }
        if (allDay.isNotEmpty()) {
            val labelLeft = left + inset + number.size.width + inset * 0.6f
            val labelWidth = (left + width - inset - labelLeft).roundToInt()
            if (labelWidth > 0) {
                val label = measurer.measure(
                    text = allDay.joinToString(" · ") { it.title },
                    style = entryStyle.copy(
                        color = ink,
                        fontSize = (entryStyle.fontSize.value * scale * 0.7f)
                            .coerceIn(MIN_ENTRY_SP, MAX_ENTRY_SP).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth),
                )
                // Sat on the number's baseline rather than its top edge: the day number is much the
                // larger type, and aligning the boxes would leave the label floating.
                drawText(
                    label,
                    topLeft = Offset(
                        labelLeft,
                        top + inset + (number.size.height - label.size.height).coerceAtLeast(0) / 2f,
                    ),
                )
            }
        }
    }

    if (!showEntries) {
        // Two marks, and they have to be told apart at a glance on a cell a few millimetres
        // wide. A filled dot is something *written* on the day; a hollow square is something
        // *photographed* — a frame, which is what a picture is. Filled versus outline is how
        // LightOS carries state everywhere else, and it survives being three pixels across
        // in a way that two different dots would not.
        val markY = top + height - inset - width * 0.05f
        val radius = (width * 0.035f).coerceAtLeast(1.5f)
        val written = rows.isNotEmpty()

        // Both marks present: they share the bottom edge, so each shifts off centre by its
        // own width rather than overlapping into an ambiguous blob.
        val shift = if (written && hasPhotos) radius * 2.2f else 0f

        if (written) {
            drawCircle(
                color = ink,
                radius = radius,
                center = Offset(left + width / 2f - shift, markY),
            )
        }
        if (hasPhotos) {
            val side = radius * 2f
            drawRect(
                color = ink,
                topLeft = Offset(left + width / 2f + shift - side / 2f, markY - side / 2f),
                size = Size(side, side),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    // Hairline at this size, but a 1px stroke on a 1080-wide panel is a
                    // clean line; anything thicker fills the square in and it becomes a dot.
                    width = (radius * 0.5f).coerceAtLeast(1f),
                ),
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

    val bodyTop = headerY + inset * 0.6f
    val available = top + height - inset
    val textSize = (entryStyle.fontSize.value * scale * 0.75f)
        .coerceIn(MIN_ENTRY_SP, MAX_ENTRY_SP)
    val lineHeight = textSize * 1.6f
    val textLeft = left + inset * 0.5f + width * DAYLIGHT_WIDTH + inset * 0.5f
    val textWidth = (left + width - inset - textLeft).roundToInt().coerceAtLeast(1)

    // **Entries sit at the time they happen.** The cell already draws the day as a column — the
    // daylight band runs down it from the cutover to the next — and a list stacked from the top
    // contradicts that: a half past five appointment drawn first looks like it happened at
    // breakfast. So each row is placed against the same axis the band uses, and the cell becomes a
    // very small calendar day rather than a very small list.
    var lastBottom = Float.NEGATIVE_INFINITY
    var hidden = 0

    rows.forEach { row ->
        // All-day things are drawn beside the date above and must not appear twice.
        val minutes = row.minutes ?: return@forEach
        val wanted = top + height * (minutes / MINUTES_IN_DAY_F)

        // Never above the heading, and never off the bottom. Rows are also pushed down past the
        // previous one so two things half an hour apart in a cell this size stay legible instead of
        // printing over each other — position is a strong hint here, not a guarantee.
        val y = wanted.coerceAtLeast(bodyTop).coerceAtLeast(lastBottom)
        if (y + lineHeight > available) {
            hidden++
            return@forEach
        }

        val clock = if (showTimes) NoteDates.clock(row.minutes) else null
        val line = listOfNotNull(clock, row.title).joinToString(" ")
        val measured = measurer.measure(
            text = line,
            style = entryStyle.copy(color = ink, fontSize = textSize.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = textWidth),
        )
        drawText(measured, topLeft = Offset(textLeft, y))
        lastBottom = y + measured.size.height * 1.05f
    }

    if (hidden > 0) {
        val more = measurer.measure(
            text = "+$hidden",
            style = monthStyle.copy(color = ink, fontSize = textSize.sp),
            maxLines = 1,
        )
        drawText(more, topLeft = Offset(textLeft, available - more.size.height))
    }
}

private const val MAX_NUMBER_SP = 34f
private const val MIN_ENTRY_SP = 8f
private const val MAX_ENTRY_SP = 15f
