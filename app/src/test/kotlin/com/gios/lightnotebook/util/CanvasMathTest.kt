package com.gios.lightnotebook.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasMathTest {

    // 2 August 2026 is a Sunday, so it is a week start and column 0.
    private val sunday = LocalDate.of(2026, 8, 2).toEpochDay()
    private val wednesday = LocalDate.of(2026, 8, 5).toEpochDay()
    private val origin = sunday

    private val cellW = 60f
    private val cellH = 80f
    private val viewportW = 420f
    private val viewportH = 900f

    /* ---------- where a day lives ---------- */

    @Test
    fun columnsAreSundayFirst() {
        assertEquals(0, CanvasMath.columnOf(sunday))
        assertEquals(3, CanvasMath.columnOf(wednesday))
        assertEquals(6, CanvasMath.columnOf(sunday + 6))
    }

    @Test
    fun weekStartsAreSundays() {
        assertEquals(sunday, CanvasMath.weekStart(wednesday))
        assertEquals(sunday, CanvasMath.weekStart(sunday))
        assertEquals(sunday + 7, CanvasMath.weekStart(sunday + 7))
    }

    @Test
    fun weekIndexCountsForwardsAndBackwards() {
        assertEquals(0, CanvasMath.weekIndexOf(wednesday, origin))
        assertEquals(1, CanvasMath.weekIndexOf(wednesday + 7, origin))
        assertEquals(-1, CanvasMath.weekIndexOf(wednesday - 7, origin))
        assertEquals(-4, CanvasMath.weekIndexOf(wednesday - 28, origin))
    }

    @Test
    fun dayAtIsTheInverseOfColumnAndWeek() {
        for (offset in -400L..400L step 7L) {
            val day = wednesday + offset
            val column = CanvasMath.columnOf(day)
            val week = CanvasMath.weekIndexOf(day, origin)
            assertEquals(day, CanvasMath.dayAt(column, week, origin))
        }
    }

    /* ---------- hit testing ---------- */

    @Test
    fun aTapFindsTheDayUnderIt() {
        val offset = Pt(0f, 0f)
        // Third column, second row, at scale 1.
        val point = Pt(x = 2 * cellW + 5f, y = 1 * cellH + 5f)
        assertEquals(
            CanvasMath.dayAt(2, 1, origin),
            CanvasMath.dayAtScreen(point, offset, 1f, cellW, cellH, origin),
        )
    }

    @Test
    fun aTapOutsideTheSevenColumnsHitsNothing() {
        val offset = Pt(0f, 0f)
        assertNull(CanvasMath.dayAtScreen(Pt(-10f, 10f), offset, 1f, cellW, cellH, origin))
        assertNull(
            CanvasMath.dayAtScreen(Pt(7 * cellW + 1f, 10f), offset, 1f, cellW, cellH, origin),
        )
    }

    @Test
    fun hitTestingFollowsTheTransform() {
        val offset = Pt(-100f, -240f)
        val scale = 2.3f
        val day = wednesday + 14
        val topLeft = CanvasMath.cellTopLeft(day, offset, scale, cellW, cellH, origin)
        val inside = Pt(topLeft.x + 2f, topLeft.y + 2f)
        assertEquals(day, CanvasMath.dayAtScreen(inside, offset, scale, cellW, cellH, origin))
    }

    /* ---------- visible range ---------- */

    @Test
    fun visibleWeeksCoverTheViewport() {
        val range = CanvasMath.visibleWeeks(
            offsetY = 0f,
            scale = 1f,
            viewportHeight = viewportH,
            cellHeight = cellH,
            pad = 0,
        )
        assertEquals(0, range.first)
        // 900 / 80 = 11.25 rows, so row 11 is partly on screen.
        assertEquals(12, range.last)
    }

    @Test
    fun scrollingBackwardsGivesNegativeWeeks() {
        val range = CanvasMath.visibleWeeks(
            offsetY = 400f,
            scale = 1f,
            viewportHeight = viewportH,
            cellHeight = cellH,
            pad = 0,
        )
        assertTrue(range.first < 0)
    }

    @Test
    fun zoomingInShowsFewerWeeks() {
        val out = CanvasMath.visibleWeeks(0f, 1f, viewportH, cellH, pad = 0)
        val inn = CanvasMath.visibleWeeks(0f, 4f, viewportH, cellH, pad = 0)
        assertTrue(inn.count() < out.count())
    }

    /* ---------- zoom behaviour ---------- */

    @Test
    fun scaleIsClampedToTheLevels() {
        assertEquals(CanvasMath.MIN_SCALE, CanvasMath.clampScale(0.2f))
        assertEquals(CanvasMath.MAX_SCALE, CanvasMath.clampScale(99f))
    }

    @Test
    fun levelsSwitchWhereExpected() {
        assertEquals(ZoomLevel.Month, CanvasMath.levelFor(1f))
        assertEquals(ZoomLevel.Month, CanvasMath.levelFor(1.5f))
        assertEquals(ZoomLevel.Week, CanvasMath.levelFor(2.3f))
        assertEquals(ZoomLevel.Week, CanvasMath.levelFor(3.5f))
        assertEquals(ZoomLevel.Day, CanvasMath.levelFor(ZoomLevel.Day.scale))
    }

    @Test
    fun aDayFillsTheViewportAtTheDayStop() {
        // Seven columns across the width means the day stop has to be exactly seven, or a
        // slide of one screen would not be a slide of one day.
        assertEquals(7f, ZoomLevel.Day.scale)
        val cellSpan = cellW * ZoomLevel.Day.scale
        assertEquals(cellW * 7f, cellSpan, 0.01f)
    }

    /* ---------- level of detail ---------- */

    @Test
    fun daysAcrossIsTheThingDetailDependsOn() {
        assertEquals(7f, CanvasMath.daysAcross(viewportW / 7f, viewportW), 0.01f)
        assertEquals(4f, CanvasMath.daysAcross(viewportW / 4f, viewportW), 0.01f)
        assertEquals(1f, CanvasMath.daysAcross(viewportW, viewportW), 0.01f)
    }

    @Test
    fun fourDaysAcrossIsEnoughToReadWhatIsOn() {
        assertTrue(CanvasMath.showsEntries(4f))
        assertTrue(CanvasMath.showsEntries(5f))
        // A whole month across is dots only.
        assertFalse(CanvasMath.showsEntries(7f))
    }

    @Test
    fun timesWaitUntilCloserIn() {
        assertFalse(CanvasMath.showsTimes(4f))
        assertTrue(CanvasMath.showsTimes(2.5f))
        assertTrue(CanvasMath.showsTimes(1f))
    }

    @Test
    fun timesNeverAppearBeforeTitlesDo() {
        var across = 7f
        while (across > 0.5f) {
            if (CanvasMath.showsTimes(across)) assertTrue(CanvasMath.showsEntries(across))
            across -= 0.1f
        }
    }

    @Test
    fun lineCountFollowsTheHeightAvailable() {
        assertEquals(0, CanvasMath.linesFor(cellHeight = 10f, lineHeight = 20f))
        assertEquals(3, CanvasMath.linesFor(cellHeight = 100f, lineHeight = 20f))
        assertEquals(6, CanvasMath.linesFor(cellHeight = 1000f, lineHeight = 20f))
        assertEquals(0, CanvasMath.linesFor(cellHeight = 100f, lineHeight = 0f))
    }

    @Test
    fun theOpenColumnIsTheOffsetMeasuredInScreens() {
        val span = 420f
        assertEquals(0, CanvasMath.openColumn(0f, span))
        assertEquals(1, CanvasMath.openColumn(-span, span))
        assertEquals(3, CanvasMath.openColumn(-span * 3, span))
        // Mid-drag it rounds to whichever day is more than half on screen.
        assertEquals(1, CanvasMath.openColumn(-span * 0.6f, span))
        assertEquals(0, CanvasMath.openColumn(-span * 0.4f, span))
    }

    @Test
    fun offsetForColumnIsTheInverse() {
        val span = 420f
        for (column in 0..6) {
            assertEquals(
                column,
                CanvasMath.openColumn(CanvasMath.offsetXForColumn(column, span), span),
            )
        }
    }

    @Test
    fun slidingOffTheEndOfAWeekWrapsToTheNextRow() {
        val (column, week, day) = CanvasMath.wrapSlide(7, 2, origin)
        assertEquals(0, column)
        assertEquals(3, week)
        assertEquals(CanvasMath.dayAt(0, 3, origin), day)
        // And the day really is the one after Saturday.
        assertEquals(CanvasMath.dayAt(6, 2, origin) + 1, day)
    }

    @Test
    fun slidingBackOffTheStartWrapsToThePreviousRow() {
        val (column, week, day) = CanvasMath.wrapSlide(-1, 2, origin)
        assertEquals(6, column)
        assertEquals(1, week)
        assertEquals(CanvasMath.dayAt(0, 2, origin) - 1, day)
    }

    @Test
    fun aColumnInsideTheWeekIsLeftAlone() {
        val (column, week, day) = CanvasMath.wrapSlide(3, 5, origin)
        assertEquals(3, column)
        assertEquals(5, week)
        assertEquals(CanvasMath.dayAt(3, 5, origin), day)
    }

    @Test
    fun aPinchThatEndsNearALevelSnapsToIt() {
        assertEquals(1f, CanvasMath.snapTarget(1.1f))
        assertEquals(ZoomLevel.Week.scale, CanvasMath.snapTarget(2.1f))
    }

    @Test
    fun pinchingPastHalfwayToTheDayOpensIt() {
        val halfway = (ZoomLevel.Week.scale + ZoomLevel.Day.scale) / 2f
        assertEquals(ZoomLevel.Day.scale, CanvasMath.snapTarget(halfway))
        assertEquals(ZoomLevel.Day.scale, CanvasMath.snapTarget(halfway + 1f))
        // Just short of it is not enough — you get to stay between stops.
        val short = halfway - 0.2f
        assertEquals(short, CanvasMath.snapTarget(short))
    }

    @Test
    fun aPinchThatEndsBetweenLevelsIsLeftAlone() {
        val between = 3.2f
        assertEquals(between, CanvasMath.snapTarget(between))
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val offset = Pt(-40f, -120f)
        val focus = Pt(200f, 500f)
        val old = 1f
        val new = 2.3f
        val moved = CanvasMath.offsetForZoom(offset, old, new, focus)
        // The canvas coordinate under the focus is the same before and after.
        val before = Pt((focus.x - offset.x) / old, (focus.y - offset.y) / old)
        val after = Pt((focus.x - moved.x) / new, (focus.y - moved.y) / new)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    /* ---------- home ---------- */

    @Test
    fun homePutsTheAnchorMonthAtTheTop() {
        // 1 August 2026 is a Saturday, so its week starts on 26 July — one week before the
        // origin, which lands the month one row down.
        val home = CanvasMath.homeOffset(wednesday, cellH, origin)
        assertEquals(0f, home.x)
        assertEquals(cellH, home.y, 0.01f)
    }

    @Test
    fun homeKeepsTheAnchorWeekClearOfTheFooter() {
        // A day late in a long month: framing from the 1st would put its week off the bottom.
        val lateInMonth = LocalDate.of(2026, 8, 29).toEpochDay()
        val footer = 200f
        val home = CanvasMath.homeOffset(
            anchorDay = lateInMonth,
            cellHeight = cellH,
            originWeekStart = origin,
            topInset = 0f,
            bottomInset = footer,
            viewportHeight = 500f,
        )
        val weekTop = CanvasMath.weekIndexOf(lateInMonth, origin) * cellH + home.y
        assertTrue("week starts above the footer", weekTop + cellH <= 500f - footer + 0.01f)
    }

    @Test
    fun homeStillFramesTheMonthWhenItFits() {
        val plain = CanvasMath.homeOffset(wednesday, cellH, origin)
        val withRoom = CanvasMath.homeOffset(
            anchorDay = wednesday,
            cellHeight = cellH,
            originWeekStart = origin,
            topInset = 0f,
            bottomInset = 100f,
            viewportHeight = 2000f,
        )
        assertEquals(plain.y, withRoom.y, 0.01f)
    }

    @Test
    fun nearHomeIsForgivingButNotBlind() {
        val home = Pt(0f, 80f)
        assertTrue(CanvasMath.isNearHome(1f, home, home, cellW))
        assertTrue(CanvasMath.isNearHome(1.05f, Pt(10f, 90f), home, cellW))
        assertFalse(CanvasMath.isNearHome(2.3f, home, home, cellW))
        assertFalse(CanvasMath.isNearHome(1f, Pt(0f, 400f), home, cellW))
    }

    /* ---------- panning limits ---------- */

    @Test
    fun theGridIsCentredWhenItFits() {
        val x = CanvasMath.clampOffsetX(123f, 1f, viewportW, cellW)
        assertEquals((viewportW - cellW * 7) / 2f, x, 0.01f)
    }

    @Test
    fun zoomedInPanningStopsAtTheEdges() {
        val scale = 3f
        val contentWidth = cellW * 7 * scale
        assertEquals(0f, CanvasMath.clampOffsetX(50f, scale, viewportW, cellW))
        assertEquals(
            viewportW - contentWidth,
            CanvasMath.clampOffsetX(-9999f, scale, viewportW, cellW),
            0.01f,
        )
    }

    /* ---------- the day a zoom-in opens ---------- */

    @Test
    fun theFocusedDayIsTheOneInTheMiddle() {
        val scale = 2.3f
        val day = wednesday + 7
        // Put that day's cell centre in the middle of the viewport.
        val canvasX = (CanvasMath.columnOf(day) + 0.5f) * cellW
        val canvasY = (CanvasMath.weekIndexOf(day, origin) + 0.5f) * cellH
        val offset = Pt(viewportW / 2f - canvasX * scale, viewportH / 2f - canvasY * scale)
        assertEquals(
            day,
            CanvasMath.focusedDay(offset, scale, viewportW, viewportH, cellW, cellH, origin),
        )
    }

    @Test
    fun theFocusedDayIsStillFoundWhenTheCentreFallsBetweenColumns() {
        // Panned so the middle of the screen is off the left edge of the columns.
        val offset = Pt(600f, 0f)
        val day = CanvasMath.focusedDay(offset, 3f, viewportW, viewportH, cellW, cellH, origin)
        assertEquals(0, CanvasMath.columnOf(day))
    }
}
