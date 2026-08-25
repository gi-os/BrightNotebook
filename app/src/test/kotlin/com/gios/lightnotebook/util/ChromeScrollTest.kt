package com.gios.lightnotebook.util

import com.gios.lightnotebook.util.ChromeScroll.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeScrollTest {

    private fun at(index: Int, offset: Int, forward: Boolean = true) =
        Position(index = index, offset = offset, canScrollForward = forward)

    @Test
    fun `reading down hides the bars`() {
        assertEquals(true, ChromeScroll.hidden(at(0, 0), at(2, 40)))
        assertEquals(true, ChromeScroll.hidden(at(3, 10), at(3, 10 + ChromeScroll.SLOP + 1)))
    }

    @Test
    fun `scrolling back up shows them`() {
        assertEquals(false, ChromeScroll.hidden(at(4, 0), at(2, 0)))
        assertEquals(false, ChromeScroll.hidden(at(3, 100), at(3, 100 - ChromeScroll.SLOP - 1)))
    }

    @Test
    fun `the top of the day always shows its chrome`() {
        assertEquals(false, ChromeScroll.hidden(at(1, 0), at(0, 0)))
        assertEquals(false, ChromeScroll.hidden(at(0, 200), at(0, ChromeScroll.SLOP - 1)))
    }

    @Test
    fun `jitter under a resting thumb decides nothing`() {
        assertNull(ChromeScroll.hidden(at(5, 100), at(5, 100 + ChromeScroll.SLOP - 1)))
        assertNull(ChromeScroll.hidden(at(5, 100), at(5, 100 - ChromeScroll.SLOP + 1)))
    }

    // The bug: hiding the chrome grows the viewport, the list clamps its own scroll, and the
    // offset drops with no finger involved. Read as an up-scroll that re-showed the bars and
    // bounced the day back down every time you reached the bottom.
    @Test
    fun `a clamp at the end of the list does not bring the bars back`() {
        val bottom = at(9, 400, forward = false)
        val clamped = at(9, 400 - 120, forward = false)
        assertNull(ChromeScroll.hidden(bottom, clamped))
    }

    @Test
    fun `an item-boundary clamp at the end does not bring them back either`() {
        assertNull(ChromeScroll.hidden(at(9, 30, forward = false), at(8, 500, forward = false)))
    }

    @Test
    fun `scrolling up away from the bottom still shows them`() {
        // The moment the list has somewhere forward to go again, the gesture is real.
        assertEquals(false, ChromeScroll.hidden(at(9, 400, forward = false), at(8, 100)))
    }

    @Test
    fun `reaching the bottom in one fling still hides them`() {
        assertEquals(true, ChromeScroll.hidden(at(0, 0), at(9, 400, forward = false)))
    }

    @Test
    fun `only real travel moves the reference position`() {
        val from = at(5, 100)
        assertTrue(ChromeScroll.advanced(from, at(6, 0)))
        assertTrue(ChromeScroll.advanced(from, at(5, 100 + ChromeScroll.SLOP + 1)))
        assertEquals(false, ChromeScroll.advanced(from, at(5, 100 + ChromeScroll.SLOP - 1)))
    }
}
