package com.gios.lightnotebook.util

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A point on the canvas or the screen. Not `Offset`, so this file stays Compose-free. */
data class Pt(val x: Float, val y: Float)

/**
 * How zoomed in the wall planner is. Only two of these are drawn — [Day] is the point at
 * which the canvas hands over to the day screen itself.
 */
enum class ZoomLevel(val scale: Float) {
    Month(1f),
    Week(2.3f),

    /**
     * Seven, exactly — because there are seven columns, so at this scale one cell is precisely
     * the width of the viewport. That is what lets a day slide sideways as a real object on the
     * planner: one cell of pan is one day, and the neighbour arriving is the neighbour's own
     * square rather than a redrawn panel.
     */
    Day(7f),
}

/**
 * The geometry of the zoomable calendar.
 *
 * The surface is one continuous wall planner: seven columns wide, weeks running downwards
 * without end. A day's place on it never changes — only the transform does — so every
 * question the UI asks (what is visible, what did I tap, where is home) is arithmetic on a
 * scale and an offset, and all of it can be tested off-device.
 *
 * Screen and canvas relate as `screen = canvas * scale + offset`. Offsets are screen pixels
 * so that a drag can simply be added to them.
 */
object CanvasMath {

    /** Snap to a level when within this fraction of its scale. */
    private const val SNAP_BAND = 0.22f

    /** And treat the transform as "home" within this much of it. */
    private const val HOME_SCALE_TOLERANCE = 0.10f
    private const val HOME_OFFSET_TOLERANCE_CELLS = 0.6f

    val MIN_SCALE = ZoomLevel.Month.scale
    val MAX_SCALE = ZoomLevel.Day.scale

    /** Sunday-first column, matching the month grid and the LPIII's own calendar. */
    fun columnOf(epochDay: Long): Int = (LocalDate.ofEpochDay(epochDay).dayOfWeek.value % 7)

    /** The Sunday that starts [epochDay]'s week. */
    fun weekStart(epochDay: Long): Long = epochDay - columnOf(epochDay)

    /** Whole weeks between two Sundays; the vertical coordinate of the planner. */
    fun weekIndexOf(epochDay: Long, originWeekStart: Long): Int =
        floor((weekStart(epochDay) - originWeekStart) / 7.0).toInt()

    fun dayAt(column: Int, weekIndex: Int, originWeekStart: Long): Long =
        originWeekStart + weekIndex * 7L + column

    /* ---------- what is on screen ---------- */

    /**
     * The weeks that intersect the viewport, with [pad] extra rows either side so a drag
     * doesn't reveal blank space before the next frame.
     */
    fun visibleWeeks(
        offsetY: Float,
        scale: Float,
        viewportHeight: Float,
        cellHeight: Float,
        pad: Int = 1,
    ): IntRange {
        val step = cellHeight * scale
        if (step <= 0f) return 0..0
        val first = floor((-offsetY) / step).toInt() - pad
        val last = ceil((viewportHeight - offsetY) / step).toInt() + pad
        return first..maxOf(first, last)
    }

    /** Which day a tap landed on, or null when it fell outside the seven columns. */
    fun dayAtScreen(
        point: Pt,
        offset: Pt,
        scale: Float,
        cellWidth: Float,
        cellHeight: Float,
        originWeekStart: Long,
    ): Long? {
        if (scale <= 0f || cellWidth <= 0f || cellHeight <= 0f) return null
        val canvasX = (point.x - offset.x) / scale
        val canvasY = (point.y - offset.y) / scale
        val column = floor(canvasX / cellWidth).toInt()
        if (column !in 0..6) return null
        val week = floor(canvasY / cellHeight).toInt()
        return dayAt(column, week, originWeekStart)
    }

    /** Top-left of a day's cell, in screen pixels. */
    fun cellTopLeft(
        epochDay: Long,
        offset: Pt,
        scale: Float,
        cellWidth: Float,
        cellHeight: Float,
        originWeekStart: Long,
    ): Pt = Pt(
        x = columnOf(epochDay) * cellWidth * scale + offset.x,
        y = weekIndexOf(epochDay, originWeekStart) * cellHeight * scale + offset.y,
    )

    /* ---------- zoom ---------- */

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** The level a scale belongs to, for choosing what to draw. */
    fun levelFor(scale: Float): ZoomLevel = when {
        scale >= ZoomLevel.Day.scale -> ZoomLevel.Day
        scale >= (ZoomLevel.Month.scale + ZoomLevel.Week.scale) / 2f -> ZoomLevel.Week
        else -> ZoomLevel.Month
    }

    /**
     * Where a pinch should settle: the nearest level, if the gesture ended near one.
     * Otherwise the scale is left alone, so deliberate in-between framing is not fought.
     */
    fun snapTarget(scale: Float): Float {
        val clamped = clampScale(scale)
        // Past halfway between the week and the day, the day wins outright rather than having
        // to be pinched all the way to seven. A proportional band would put that boundary at
        // 5.5, which is a lot of pinching for a phone this size.
        if (clamped >= (ZoomLevel.Week.scale + ZoomLevel.Day.scale) / 2f) return ZoomLevel.Day.scale
        val nearest = listOf(ZoomLevel.Month, ZoomLevel.Week)
            .minByOrNull { abs(it.scale - clamped) } ?: return clamped
        return if (abs(nearest.scale - clamped) / nearest.scale <= SNAP_BAND) {
            nearest.scale
        } else {
            clamped
        }
    }

    /** Zooming about a focal point: the canvas point under the fingers stays put. */
    fun offsetForZoom(offset: Pt, oldScale: Float, newScale: Float, focus: Pt): Pt {
        if (oldScale <= 0f) return offset
        val k = newScale / oldScale
        return Pt(
            x = focus.x - (focus.x - offset.x) * k,
            y = focus.y - (focus.y - offset.y) * k,
        )
    }

    /* ---------- home ---------- */

    /**
     * The offset that puts [anchorDay]'s month at the top of the viewport at scale 1 — the
     * preset position the view starts in and springs back to.
     */
    fun homeOffset(
        anchorDay: Long,
        cellHeight: Float,
        originWeekStart: Long,
        topInset: Float = 0f,
        bottomInset: Float = 0f,
        viewportHeight: Float = 0f,
    ): Pt {
        val firstOfMonth = LocalDate.ofEpochDay(anchorDay).withDayOfMonth(1).toEpochDay()
        val monthTop = topInset - weekIndexOf(firstOfMonth, originWeekStart) * cellHeight

        // Framing the month from its first week is the nicer view, but it can push the
        // anchor's own week down behind the NEXT UP footer — a "jump to today" that leaves
        // today under a button is not a jump to today. Slide up only as far as needed.
        if (viewportHeight <= 0f) return Pt(0f, monthTop)
        val anchorWeekTop = weekIndexOf(anchorDay, originWeekStart) * cellHeight + monthTop
        val floor = viewportHeight - bottomInset - cellHeight
        val y = if (anchorWeekTop > floor) monthTop - (anchorWeekTop - floor) else monthTop
        return Pt(x = 0f, y = y)
    }

    /**
     * Close enough to home to be pulled back into it. Deliberately generous: a person
     * pinching back out wants the grid, not the grid minus four pixels.
     */
    fun isNearHome(scale: Float, offset: Pt, home: Pt, cellWidth: Float): Boolean {
        if (abs(scale - MIN_SCALE) / MIN_SCALE > HOME_SCALE_TOLERANCE) return false
        val slack = cellWidth * HOME_OFFSET_TOLERANCE_CELLS
        return abs(offset.x - home.x) <= slack && abs(offset.y - home.y) <= slack
    }

    /**
     * Panning limits. Horizontally the seven columns are pinned to the viewport, so there is
     * nothing to reach sideways at scale 1 and only the overflow at higher scales. Vertically
     * it is unbounded — that is the whole point of a wall planner.
     */
    fun clampOffsetX(offsetX: Float, scale: Float, viewportWidth: Float, cellWidth: Float): Float {
        val contentWidth = cellWidth * 7 * scale
        if (contentWidth <= viewportWidth) {
            // Centred rather than left-aligned, so a zoomed-out planner is not lopsided.
            return (viewportWidth - contentWidth) / 2f
        }
        return offsetX.coerceIn(viewportWidth - contentWidth, 0f)
    }

    /* ---------- how much detail fits ---------- */

    /**
     * Detail is decided by how many days are across the screen, not by the zoom number.
     *
     * That is the thing a person is actually looking at, and it holds however wide the panel is:
     * "I can see four days, so I should be able to read them" is a sentence about days across.
     */
    fun daysAcross(cellSpan: Float, viewportWidth: Float): Float =
        if (cellSpan <= 0f) 7f else viewportWidth / cellSpan

    /** Titles appear once a day is wide enough for a couple of words. */
    fun showsEntries(daysAcross: Float): Boolean = daysAcross <= ENTRIES_FROM_DAYS_ACROSS

    /**
     * The same question answered from the scale alone, for code that runs outside the draw.
     *
     * The grid is always seven columns of `viewportWidth / 7`, so a cell at a given scale spans
     * `viewportWidth * scale / 7` and the days across the screen come to `7 / scale` — the
     * viewport cancels out. That matters because loading a cell's background photograph has to
     * be decided in a composable, where the viewport is not known yet, and it must agree
     * exactly with what the draw decides or cells load pictures they never show.
     */
    fun daysAcrossAtScale(scale: Float): Float = if (scale <= 0f) 7f else 7f / scale

    fun showsEntriesAtScale(scale: Float): Boolean = showsEntries(daysAcrossAtScale(scale))

    /**
     * Times wait until closer in. At four days across a title uses the whole width, and
     * prefixing "9:30" to it just eats the words that say what the thing is.
     */
    fun showsTimes(daysAcross: Float): Boolean = daysAcross <= TIMES_FROM_DAYS_ACROSS

    /** How many lines of text a cell that tall can honestly show. */
    fun linesFor(cellHeight: Float, lineHeight: Float): Int {
        if (lineHeight <= 0f) return 0
        return ((cellHeight * 0.62f) / lineHeight).toInt().coerceIn(0, 6)
    }

    private const val ENTRIES_FROM_DAYS_ACROSS = 5.2f
    private const val TIMES_FROM_DAYS_ACROSS = 2.6f

    /* ---------- sliding between days, with a day filling the screen ---------- */

    /**
     * Which column is filling the screen, given how far the planner has been slid.
     *
     * At [ZoomLevel.Day] a cell spans the whole viewport, so this is just the offset measured
     * in screens. May return -1 or 7: sliding off either end of the week is how you reach the
     * row above or below, and the caller wraps it.
     */
    fun openColumn(offsetX: Float, cellSpan: Float): Int {
        if (cellSpan <= 0f) return 0
        return Math.round(-offsetX / cellSpan)
    }

    /** The offset that puts [column] exactly on screen. */
    fun offsetXForColumn(column: Int, cellSpan: Float): Float = -column * cellSpan

    /**
     * Where sliding off the end of a week lands: the next row's Sunday, or the previous row's
     * Saturday. Returns the column, the week and the day, because the surface has to move
     * diagonally to get there — a wall planner's "tomorrow" after Saturday is at the far left,
     * one row down.
     */
    fun wrapSlide(column: Int, week: Int, originWeekStart: Long): Triple<Int, Int, Long> = when {
        column > 6 -> Triple(0, week + 1, dayAt(0, week + 1, originWeekStart))
        column < 0 -> Triple(6, week - 1, dayAt(6, week - 1, originWeekStart))
        else -> Triple(column, week, dayAt(column, week, originWeekStart))
    }

    /** The day nearest the middle of the viewport — what a zoom-in should open. */
    fun focusedDay(
        offset: Pt,
        scale: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        cellWidth: Float,
        cellHeight: Float,
        originWeekStart: Long,
    ): Long {
        val centre = Pt(viewportWidth / 2f, viewportHeight / 2f)
        dayAtScreen(centre, offset, scale, cellWidth, cellHeight, originWeekStart)?.let { return it }
        // The centre can fall outside the columns while zoomed in; take the nearest one.
        val canvasY = (centre.y - offset.y) / scale
        val week = floor(canvasY / cellHeight).toInt()
        val column = ((centre.x - offset.x) / scale / cellWidth).roundToInt().coerceIn(0, 6)
        return dayAt(column, week, originWeekStart)
    }
}
