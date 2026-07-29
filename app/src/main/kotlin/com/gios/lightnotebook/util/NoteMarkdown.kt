package com.gios.lightnotebook.util

/**
 * Notes are stored as plain text with three markers: `**bold**`, `- ` bullets and
 * `1. ` numbers. Keeping the storage format text means a note is still readable if it
 * is ever exported, and it means every editing operation here is pure string surgery
 * that can be unit tested off-device.
 *
 * Nothing in this file may import Android — the sandbox can only compile and test
 * Kotlin that doesn't.
 */

/** A text buffer plus the selection, which every operation has to move deliberately. */
data class Edit(val text: String, val selStart: Int, val selEnd: Int) {
    constructor(text: String, cursor: Int) : this(text, cursor, cursor)
}

private const val B = "**"
private val BULLET = Regex("^(\\s*)- ")
private val NUMBER = Regex("^(\\s*)(\\d+)\\. ")

object NoteMarkdown {

    /* ---------- bold ---------- */

    /**
     * Wraps the selection in `**`, or unwraps it when it is already bold. With no
     * selection it drops an empty pair in and parks the cursor between them, which is
     * what you want when you are about to type the bold word.
     */
    fun toggleBold(text: String, selStart: Int, selEnd: Int): Edit {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)

        if (start == end) {
            return Edit(text.substring(0, start) + B + B + text.substring(start), start + 2)
        }

        // Markers sit just outside the selection: the usual case after a double-tap.
        val outsideBold = start >= 2 && end + 2 <= text.length &&
            text.regionMatches(start - 2, B, 0, 2) && text.regionMatches(end, B, 0, 2)
        if (outsideBold) {
            val stripped = text.removeRange(end, end + 2).removeRange(start - 2, start)
            return Edit(stripped, start - 2, end - 2)
        }

        // Markers sit inside the selection: the user dragged over them too.
        val sel = text.substring(start, end)
        if (sel.length >= 4 && sel.startsWith(B) && sel.endsWith(B)) {
            val inner = sel.substring(2, sel.length - 2)
            return Edit(text.replaceRange(start, end, inner), start, start + inner.length)
        }

        return Edit(text.replaceRange(start, end, B + sel + B), start, end + 4)
    }

    /* ---------- lists ---------- */

    /** Bullets every line the selection touches, or clears them if they all have one. */
    fun toggleBullet(text: String, selStart: Int, selEnd: Int): Edit =
        rewriteLines(text, selStart, selEnd) { lines ->
            val body = lines.filter { it.isNotBlank() }
            val allBulleted = body.isNotEmpty() && body.all { BULLET.containsMatchIn(it) }
            lines.map { line ->
                if (line.isBlank()) {
                    line
                } else if (allBulleted) {
                    line.replaceFirst(BULLET, "$1")
                } else {
                    val bare = stripMarkers(line)
                    indentOf(line) + "- " + bare
                }
            }
        }

    /**
     * Numbers every line the selection touches, renumbering from 1 so an inserted line
     * never leaves a `1. 1. 3.` sequence behind.
     */
    fun toggleNumbered(text: String, selStart: Int, selEnd: Int): Edit =
        rewriteLines(text, selStart, selEnd) { lines ->
            val body = lines.filter { it.isNotBlank() }
            val allNumbered = body.isNotEmpty() && body.all { NUMBER.containsMatchIn(it) }
            var n = 0
            lines.map { line ->
                if (line.isBlank()) {
                    line
                } else if (allNumbered) {
                    line.replaceFirst(NUMBER, "$1")
                } else {
                    n++
                    indentOf(line) + "$n. " + stripMarkers(line)
                }
            }
        }

    /**
     * Called right after a newline is typed at [cursor]. Carries the list marker down
     * to the new line, and — when the line you just left was an empty list item — takes
     * the marker away instead, which is how every list gets ended without a menu.
     * Returns null when there is no list to continue.
     */
    fun continueList(text: String, cursor: Int): Edit? {
        if (cursor <= 0 || cursor > text.length) return null
        if (text[cursor - 1] != '\n') return null

        val prevEnd = cursor - 1
        val prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1
        val prev = text.substring(prevStart, prevEnd)

        NUMBER.find(prev)?.let { m ->
            val indent = m.groupValues[1]
            val next = (m.groupValues[2].toIntOrNull() ?: 0) + 1
            if (prev.length == m.value.length) {
                // "3. " with nothing after it — the user is done listing.
                return Edit(text.removeRange(prevStart, prevEnd), prevStart + 1)
            }
            val marker = "$indent$next. "
            return Edit(text.replaceRange(cursor, cursor, marker), cursor + marker.length)
        }

        BULLET.find(prev)?.let { m ->
            if (prev.length == m.value.length) {
                return Edit(text.removeRange(prevStart, prevEnd), prevStart + 1)
            }
            val marker = m.groupValues[1] + "- "
            return Edit(text.replaceRange(cursor, cursor, marker), cursor + marker.length)
        }

        return null
    }

    /* ---------- display ---------- */

    /** Markers removed, for list previews and for the text handed to the OS calendar. */
    fun plain(text: String): String = text.lineSequence()
        .map { stripMarkers(it).replace(B, "") }
        .joinToString("\n")

    /** One-line preview: markers gone, blank lines collapsed. */
    fun preview(body: String, maxChars: Int = 120): String {
        val flat = plain(body).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("  ")
        return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "…"
    }

    /** First non-blank line, marker-free — used when a note has no explicit title. */
    fun firstLine(body: String, maxChars: Int = 60): String {
        val line = plain(body).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= maxChars) line else line.take(maxChars).trimEnd() + "…"
    }

    /* ---------- internals ---------- */

    private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /**
     * Drops any bullet or number marker and the indent in front of it, leaving the text
     * of the line. Callers that are re-marking a line put the indent back themselves.
     */
    private fun stripMarkers(line: String): String = line
        .replaceFirst(BULLET, "")
        .replaceFirst(NUMBER, "")
        .trimStart(' ', '\t')

    /**
     * Applies [transform] to the whole lines the selection covers and re-selects that
     * same block, so pressing bullet twice is a clean round trip.
     */
    private fun rewriteLines(
        text: String,
        selStart: Int,
        selEnd: Int,
        transform: (List<String>) -> List<String>,
    ): Edit {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val blockStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
            .let { if (start == 0) 0 else it + 1 }
        val nextBreak = text.indexOf('\n', end)
        val blockEnd = if (nextBreak == -1) text.length else nextBreak

        val block = text.substring(blockStart, blockEnd)
        val rewritten = transform(block.split("\n")).joinToString("\n")
        val updated = text.replaceRange(blockStart, blockEnd, rewritten)
        return Edit(updated, blockStart, blockStart + rewritten.length)
    }
}
