package com.gios.lightnotebook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightTextStyle
import com.gios.lightnotebook.util.AgendaRow
import com.gios.lightnotebook.util.CanvasMath
import com.gios.lightnotebook.util.NoteDates
import com.gios.lightnotebook.util.Pt
import com.gios.lightnotebook.util.ZoomLevel
import java.time.LocalDate
import kotlin.math.roundToInt
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
    onOpenDay: (Long) -> Unit,
    onWindowChanged: (Long, Long) -> Unit,
    onFocusDayChanged: (Long) -> Unit,
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

        val home = remember(anchorDay, cellHeight) {
            CanvasMath.homeOffset(anchorDay, cellHeight, originWeekStart)
        }

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

        /** Animates to a scale and offset — the snap, and the spring back home. */
        suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
            val fromScale = scale
            val fromOffset = offset
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(durationMillis = SNAP_MS)) {
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

        /** Runs when the fingers leave: snap to a stop, spring home, or hand over to a day. */
        fun settle() {
            val target = CanvasMath.snapTarget(scale)
            val pt = Pt(offset.x, offset.y)
            when {
                CanvasMath.isNearHome(target, pt, home, cellWidth) -> scope.launch {
                    animateTo(ZoomLevel.Month.scale, Offset(home.x, home.y))
                }

                target >= ZoomLevel.Day.scale -> {
                    val day = CanvasMath.focusedDay(
                        offset = pt,
                        scale = scale,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        originWeekStart = originWeekStart,
                    )
                    // Drop back to the week stop first, so returning from the day screen does
                    // not immediately trigger another hand-over.
                    place(ZoomLevel.Week.scale, offset)
                    onOpenDay(day)
                }

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

        // TODAY, or any other jump, re-anchors home and pulls the surface back to it.
        LaunchedEffect(anchorDay) {
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { position ->
                            CanvasMath.dayAtScreen(
                                point = Pt(position.x, position.y),
                                offset = Pt(offset.x, offset.y),
                                scale = scale,
                                cellWidth = cellWidth,
                                cellHeight = cellHeight,
                                originWeekStart = originWeekStart,
                            )?.let(onOpenDay)
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
                .pointerInput(Unit) {
                    // Hand-rolled rather than detectTransformGestures, which has no
                    // end-of-gesture hook — and the snap is the whole feel of this thing.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
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
                        } while (event.changes.any { it.pressed })
                        if (moved) settle()
                    }
                },
        ) {
            val level = CanvasMath.levelFor(scale)
            val weeks = CanvasMath.visibleWeeks(offset.y, scale, viewportHeight, cellHeight)
            val cellW = cellWidth * scale
            val cellH = cellHeight * scale

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
                        background = colors.background,
                        rule = colors.rule,
                    )
                }
            }
        }
    }
}

private const val SNAP_MS = 220

/** Below this cell width there is no room for words, whatever the zoom says. */
private const val TEXT_MIN_CELL_PX = 150f
private const val MAX_LINES_PER_CELL = 4

private fun DrawScope.drawDay(
    day: Long,
    rows: List<AgendaRow>,
    isToday: Boolean,
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
    background: androidx.compose.ui.graphics.Color,
    rule: androidx.compose.ui.graphics.Color,
) {
    val date = LocalDate.ofEpochDay(day)
    val inset = width * 0.06f

    // Cell borders instead of a background: at any zoom the grid has to stay a grid, and a
    // hairline is the cheapest thing on this panel that says so.
    drawRect(
        color = rule,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
    )

    if (isToday) {
        drawRect(color = content, topLeft = Offset(left, top), size = Size(width, height))
    }

    val ink = if (isToday) background else content

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

    if (rows.isEmpty()) return

    var y = top + inset + number.size.height + inset * 0.5f
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
