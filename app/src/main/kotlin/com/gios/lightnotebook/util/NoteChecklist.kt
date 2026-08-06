package com.gios.lightnotebook.util

/**
 * Checkboxes in a note, as plain text and nothing else.
 *
 * A line reading `- [ ] milk` is a checkbox; ticking it rewrites that one line to `- [x] milk`
 * in the note's own text. There is **no parallel checkbox model** — the text is the note, the
 * text is what gets saved, backed up and read by anything else, and a second representation of
 * the same list would be a second thing to keep in step.
 *
 * Everything here is addressed **by line index**, never by matching the line's text. Two
 * identical `- [ ] milk` lines are two different things to buy, and a string-matching rewrite
 * would tick both — which is the bug this file exists to not have.
 *
 * Nothing in this file may import Android: it is pure string work and it is tested off-device.
 */
object NoteChecklist {

    /**
     * The whole marker, including the bullet: `- [ ]` or `* [x]`, after any indentation.
     *
     * The rest of the line is captured raw and never touched, so trailing spaces, a second
     * pair of brackets, punctuation and anything else survive a toggle exactly as typed. It is
     * `[\s\S]` rather than `.` because `.` stops at a carriage return, and a note pasted in
     * from a file with CRLF endings would then have no checkboxes in it at all.
     *
     * What follows the bracket must be whitespace or nothing: `- [x]done` is prose that happens
     * to contain a bracket, and turning it into a checkbox would rewrite somebody's sentence.
     */
    private val CHECKBOX = Regex("^([ \t]*)([-*]) \\[([ xX])\\]([ \t][\\s\\S]*|)$")

    /** How many characters the marker occupies after the indent: `- [ ]`. */
    const val MARKER_LENGTH = 5

    /**
     * One checkbox line.
     *
     * [markerOffset] is the position of the bullet in the *whole note*, which is what the
     * editor needs to put a glyph on top of it; [lineIndex] is what a toggle is addressed by.
     */
    data class Item(
        val lineIndex: Int,
        val markerOffset: Int,
        val checked: Boolean,
        val text: String,
    )

    /** Every checkbox line in a note, in the order they appear. */
    fun items(note: String): List<Item> {
        val out = mutableListOf<Item>()
        var offset = 0
        note.split("\n").forEachIndexed { index, line ->
            CHECKBOX.matchEntire(line)?.let { m ->
                out.add(
                    Item(
                        lineIndex = index,
                        markerOffset = offset + m.groupValues[1].length,
                        checked = !m.groupValues[3].equals(" ", ignoreCase = false),
                        text = m.groupValues[4].trim(),
                    ),
                )
            }
            offset += line.length + 1
        }
        return out
    }

    /** Whether one line is a checkbox at all. */
    fun isCheckbox(line: String): Boolean = CHECKBOX.matches(line)

    /** Flips the box on one line. Anything that is not a checkbox comes back untouched. */
    fun toggle(note: String, lineIndex: Int): String {
        val lines = note.split("\n")
        if (lineIndex !in lines.indices) return note
        val line = lines[lineIndex]
        val match = CHECKBOX.matchEntire(line) ?: return note
        val checked = !match.groupValues[3].equals(" ", ignoreCase = false)
        return write(lines, lineIndex, line, match.groupValues[1].length, !checked)
    }

    /** Sets one line's box explicitly, for callers that know which way it should end up. */
    fun setChecked(note: String, lineIndex: Int, checked: Boolean): String {
        val lines = note.split("\n")
        if (lineIndex !in lines.indices) return note
        val line = lines[lineIndex]
        val match = CHECKBOX.matchEntire(line) ?: return note
        return write(lines, lineIndex, line, match.groupValues[1].length, checked)
    }

    /**
     * Rewrites exactly one character — the one between the brackets — and rebuilds the note.
     *
     * Splitting on `\n` and joining on `\n` round-trips the text exactly, including a missing
     * final newline and any `\r` a pasted-in file left at the ends of its lines.
     */
    private fun write(
        lines: List<String>,
        lineIndex: Int,
        line: String,
        indent: Int,
        checked: Boolean,
    ): String {
        // indent, then '-', ' ', '[' — so the box itself is the fourth character after the indent.
        val at = indent + 3
        val rewritten = line.substring(0, at) + (if (checked) "x" else " ") + line.substring(at + 1)
        return lines.mapIndexed { index, existing ->
            if (index == lineIndex) rewritten else existing
        }.joinToString("\n")
    }
}
