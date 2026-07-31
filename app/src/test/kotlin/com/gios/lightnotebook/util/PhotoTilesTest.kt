package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTilesTest {

    @Test
    fun `every photograph is placed exactly once, for any count`() {
        for (count in 0..60) {
            assertEquals("count $count", count, PhotoTiles.rows(count).sum())
        }
    }

    @Test
    fun `no row is empty and none is too wide to see`() {
        for (count in 1..60) {
            PhotoTiles.rows(count).forEach { inRow ->
                assertTrue("count $count had a row of $inRow", inRow in 1..PhotoTiles.MAX_PER_ROW)
            }
        }
    }

    @Test
    fun `three is one above two, the way a page does it`() {
        assertEquals(listOf(1, 2), PhotoTiles.rows(3))
    }

    @Test
    fun `five puts the bigger pair on top rather than leaving a leftover`() {
        assertEquals(listOf(2, 3), PhotoTiles.rows(5))
    }

    @Test
    fun `no photograph is ever left alone on the last row`() {
        // A single picture on a row of its own at the bottom reads as a mistake.
        for (count in 2..60) {
            assertTrue("count $count", PhotoTiles.rows(count).last() > 1)
        }
    }

    @Test
    fun `one photograph is allowed to be alone, because it is all there is`() {
        assertEquals(listOf(1), PhotoTiles.rows(1))
    }

    @Test
    fun `the arrangement never changes for the same count`() {
        // A page that reshuffles when a photograph is added feels broken, so this is fixed rather
        // than hashed or random.
        for (count in 0..40) {
            assertEquals(PhotoTiles.rows(count), PhotoTiles.rows(count))
        }
    }

    @Test
    fun `a big burst is a block of pictures, not forty rows`() {
        val rows = PhotoTiles.rows(40)
        assertTrue("was ${rows.size} rows", rows.size <= 15)
        assertEquals(40, rows.sum())
    }

    @Test
    fun `nothing is nothing`() {
        assertTrue(PhotoTiles.rows(0).isEmpty())
        assertTrue(PhotoTiles.rowRanges(0).isEmpty())
    }

    @Test
    fun `rows slice the list in order and cover all of it`() {
        val ranges = PhotoTiles.rowRanges(7)
        assertEquals(0, ranges.first().first)
        assertEquals(6, ranges.last().last)
        var expected = 0
        ranges.forEach { range ->
            assertEquals(expected, range.first)
            expected = range.last + 1
        }
        assertEquals(7, expected)
    }

    @Test
    fun `fewer on a row means a taller row`() {
        assertTrue(PhotoTiles.rowHeightFraction(1) > PhotoTiles.rowHeightFraction(2))
        assertTrue(PhotoTiles.rowHeightFraction(2) > PhotoTiles.rowHeightFraction(3))
        assertEquals(0f, PhotoTiles.rowHeightFraction(0), 0.001f)
    }
}
