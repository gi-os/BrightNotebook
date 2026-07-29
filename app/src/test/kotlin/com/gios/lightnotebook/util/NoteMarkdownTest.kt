package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteMarkdownTest {

    /* ---------- bold ---------- */

    @Test
    fun boldWrapsSelection() {
        val e = NoteMarkdown.toggleBold("buy milk today", 4, 8)
        assertEquals("buy **milk** today", e.text)
        assertEquals("milk", e.text.substring(e.selStart, e.selEnd).replace("**", ""))
    }

    @Test
    fun boldWithNoSelectionParksCursorBetweenMarkers() {
        val e = NoteMarkdown.toggleBold("ab", 1, 1)
        assertEquals("a****b", e.text)
        assertEquals(3, e.selStart)
        assertEquals(3, e.selEnd)
    }

    @Test
    fun boldTogglesOffWhenMarkersSitOutsideSelection() {
        val text = "buy **milk** today"
        val start = text.indexOf("milk")
        val e = NoteMarkdown.toggleBold(text, start, start + 4)
        assertEquals("buy milk today", e.text)
        assertEquals("milk", e.text.substring(e.selStart, e.selEnd))
    }

    @Test
    fun boldTogglesOffWhenMarkersAreInsideSelection() {
        val text = "buy **milk** today"
        val e = NoteMarkdown.toggleBold(text, 4, 12)
        assertEquals("buy milk today", e.text)
    }

    @Test
    fun boldRoundTrips() {
        val original = "one two three"
        val on = NoteMarkdown.toggleBold(original, 4, 7)
        val off = NoteMarkdown.toggleBold(on.text, on.selStart, on.selEnd)
        assertEquals(original, off.text)
    }

    /* ---------- bullets ---------- */

    @Test
    fun bulletMarksEveryLineTheSelectionTouches() {
        val text = "milk\neggs\nbread"
        val e = NoteMarkdown.toggleBullet(text, 0, text.length)
        assertEquals("- milk\n- eggs\n- bread", e.text)
    }

    @Test
    fun bulletOnlyTouchesSelectedLines() {
        val text = "title\nmilk\neggs"
        val start = text.indexOf("milk")
        val e = NoteMarkdown.toggleBullet(text, start, start + 1)
        assertEquals("title\n- milk\neggs", e.text)
    }

    @Test
    fun bulletRoundTrips() {
        val text = "milk\neggs"
        val on = NoteMarkdown.toggleBullet(text, 0, text.length)
        val off = NoteMarkdown.toggleBullet(on.text, on.selStart, on.selEnd)
        assertEquals(text, off.text)
    }

    @Test
    fun bulletLeavesBlankLinesAlone() {
        val e = NoteMarkdown.toggleBullet("milk\n\neggs", 0, 10)
        assertEquals("- milk\n\n- eggs", e.text)
    }

    @Test
    fun bulletReplacesNumbering() {
        val text = "1. milk\n2. eggs"
        val e = NoteMarkdown.toggleBullet(text, 0, text.length)
        assertEquals("- milk\n- eggs", e.text)
    }

    /* ---------- numbering ---------- */

    @Test
    fun numberingCountsFromOne() {
        val text = "milk\neggs\nbread"
        val e = NoteMarkdown.toggleNumbered(text, 0, text.length)
        assertEquals("1. milk\n2. eggs\n3. bread", e.text)
    }

    @Test
    fun numberingRenumbersAfterAnInsert() {
        val text = "1. milk\ncheese\n3. bread"
        val e = NoteMarkdown.toggleNumbered(text, 0, text.length)
        assertEquals("1. milk\n2. cheese\n3. bread", e.text)
    }

    @Test
    fun numberingRoundTrips() {
        val text = "milk\neggs"
        val on = NoteMarkdown.toggleNumbered(text, 0, text.length)
        val off = NoteMarkdown.toggleNumbered(on.text, on.selStart, on.selEnd)
        assertEquals(text, off.text)
    }

    @Test
    fun numberingSkipsBlankLinesWithoutSpendingANumber() {
        val e = NoteMarkdown.toggleNumbered("milk\n\neggs", 0, 10)
        assertEquals("1. milk\n\n2. eggs", e.text)
    }

    /* ---------- continuing a list on Enter ---------- */

    @Test
    fun enterCarriesTheBulletDown() {
        val text = "- milk\n"
        val e = NoteMarkdown.continueList(text, text.length)!!
        assertEquals("- milk\n- ", e.text)
        assertEquals(e.text.length, e.selStart)
    }

    @Test
    fun enterIncrementsTheNumber() {
        val text = "1. milk\n2. eggs\n"
        val e = NoteMarkdown.continueList(text, text.length)!!
        assertEquals("1. milk\n2. eggs\n3. ", e.text)
    }

    @Test
    fun enterOnAnEmptyBulletEndsTheList() {
        val text = "- milk\n- \n"
        val e = NoteMarkdown.continueList(text, text.length)!!
        assertEquals("- milk\n\n", e.text)
        assertEquals(e.text.length, e.selStart)
    }

    @Test
    fun enterOnAnEmptyNumberEndsTheList() {
        val text = "1. milk\n2. \n"
        val e = NoteMarkdown.continueList(text, text.length)!!
        assertEquals("1. milk\n\n", e.text)
    }

    @Test
    fun enterOnPlainProseDoesNothing() {
        assertNull(NoteMarkdown.continueList("just a sentence\n", 16))
    }

    @Test
    fun continueListIgnoresANonNewlineCursor() {
        assertNull(NoteMarkdown.continueList("- milk", 6))
    }

    @Test
    fun indentedBulletsKeepTheirIndent() {
        val text = "  - milk\n"
        val e = NoteMarkdown.continueList(text, text.length)!!
        assertEquals("  - milk\n  - ", e.text)
    }

    /* ---------- display ---------- */

    @Test
    fun plainStripsEveryMarker() {
        assertEquals("milk\neggs", NoteMarkdown.plain("- **milk**\n1. eggs"))
    }

    @Test
    fun previewFlattensAndTruncates() {
        assertEquals("milk  eggs", NoteMarkdown.preview("- milk\n\n- eggs"))
        assertEquals("aaaa…", NoteMarkdown.preview("aaaaaaa", maxChars = 4))
    }

    @Test
    fun firstLineSkipsLeadingBlanks() {
        assertEquals("milk", NoteMarkdown.firstLine("\n\n- **milk**\neggs"))
        assertEquals("", NoteMarkdown.firstLine("   \n "))
    }
}
