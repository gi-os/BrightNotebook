package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteChecklistTest {

    @Test
    fun findsEveryCheckboxAndLeavesEverythingElseAlone() {
        val note = "Shopping\n- [ ] milk\nnot a box\n- [x] bread\n* [ ] eggs"
        val items = NoteChecklist.items(note)
        assertEquals(listOf(1, 3, 4), items.map { it.lineIndex })
        assertEquals(listOf(false, true, false), items.map { it.checked })
        assertEquals(listOf("milk", "bread", "eggs"), items.map { it.text })
    }

    @Test
    fun ticksAndUnticks() {
        assertEquals("- [x] milk", NoteChecklist.toggle("- [ ] milk", 0))
        assertEquals("- [ ] milk", NoteChecklist.toggle("- [x] milk", 0))
        // A capital X is somebody else's file, and it must still untick.
        assertEquals("- [ ] milk", NoteChecklist.toggle("- [X] milk", 0))
    }

    @Test
    fun handlesTheAsteriskBullet() {
        assertEquals("* [x] eggs", NoteChecklist.toggle("* [ ] eggs", 0))
    }

    @Test
    fun keepsIndentation() {
        val note = "- [ ] shop\n    - [ ] milk\n\t- [ ] bread"
        assertEquals("- [ ] shop\n    - [x] milk\n\t- [ ] bread", NoteChecklist.toggle(note, 1))
        assertEquals("- [ ] shop\n    - [ ] milk\n\t- [x] bread", NoteChecklist.toggle(note, 2))
    }

    @Test
    fun twoIdenticalLinesToggleIndependently() {
        // The whole reason a toggle is addressed by line index and not by matching the text.
        val note = "- [ ] milk\n- [ ] milk"
        assertEquals("- [x] milk\n- [ ] milk", NoteChecklist.toggle(note, 0))
        assertEquals("- [ ] milk\n- [x] milk", NoteChecklist.toggle(note, 1))
    }

    @Test
    fun preservesTrailingWhitespaceAndAnythingAfterTheMarker() {
        val note = "- [ ] milk (2%)  [urgent]   "
        assertEquals("- [x] milk (2%)  [urgent]   ", NoteChecklist.toggle(note, 0))
    }

    @Test
    fun theLastLineWithNoTrailingNewlineIsStillALine() {
        val note = "notes\n- [ ] last"
        assertEquals("notes\n- [x] last", NoteChecklist.toggle(note, 1))
        // And a note that does end in a newline keeps the empty line at the end.
        assertEquals("- [x] a\n", NoteChecklist.toggle("- [ ] a\n", 0))
    }

    @Test
    fun aLineThatIsNotACheckboxIsLeftExactlyAsItWas() {
        val note = "- milk\n[ ] milk\n-[ ] milk\n- [] milk\n- [y] milk"
        (0..4).forEach { assertEquals(note, NoteChecklist.toggle(note, it)) }
        assertTrue(NoteChecklist.items(note).isEmpty())
    }

    @Test
    fun anEmptyNoteHasNothingToToggle() {
        assertEquals("", NoteChecklist.toggle("", 0))
        assertTrue(NoteChecklist.items("").isEmpty())
    }

    @Test
    fun anIndexOffTheEndChangesNothing() {
        val note = "- [ ] milk"
        assertEquals(note, NoteChecklist.toggle(note, 7))
        assertEquals(note, NoteChecklist.toggle(note, -1))
    }

    @Test
    fun settingExplicitlyIsIdempotent() {
        val note = "- [ ] milk"
        val on = NoteChecklist.setChecked(note, 0, true)
        assertEquals("- [x] milk", on)
        assertEquals(on, NoteChecklist.setChecked(on, 0, true))
        assertEquals(note, NoteChecklist.setChecked(on, 0, false))
    }

    @Test
    fun theMarkerOffsetPointsAtTheBulletInTheWholeNote() {
        val note = "title\n  - [ ] milk"
        val item = NoteChecklist.items(note).single()
        assertEquals(8, item.markerOffset)
        assertEquals('-', note[item.markerOffset])
        assertEquals(']', note[item.markerOffset + NoteChecklist.MARKER_LENGTH - 1])
    }

    @Test
    fun carriageReturnsSurviveATrip() {
        val note = "- [ ] milk\r\n- [ ] bread\r"
        assertEquals("- [x] milk\r\n- [ ] bread\r", NoteChecklist.toggle(note, 0))
    }

    @Test
    fun recognisesALineOnItsOwn() {
        assertTrue(NoteChecklist.isCheckbox("   * [X] done"))
        assertFalse(NoteChecklist.isCheckbox("* [X]x done"))
        assertFalse(NoteChecklist.isCheckbox(""))
    }
}
