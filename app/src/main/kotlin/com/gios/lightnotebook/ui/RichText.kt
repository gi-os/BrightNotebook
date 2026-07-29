package com.gios.lightnotebook.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val BOLD = Regex("""\*\*([^\n]+?)\*\*""")
private val BULLET_LINE = Regex("""^([ \t]*)- """)
private val NUMBER_LINE = Regex("""^([ \t]*)(\d+\. )""")

/**
 * Styles a note as it is typed without changing its length.
 *
 * Every substitution is one character for one character — a bullet's hyphen becomes a
 * real bullet glyph, and the asterisks around bold text are dimmed rather than hidden.
 * That keeps [OffsetMapping.Identity] honest, so the cursor can never drift out of step
 * with what is on screen, which is the failure mode of every editor that tries to hide
 * its markers.
 */
class NoteTransformation(
    private val markerColor: Color,
    private val boldColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styledNote(text.text, markerColor, boldColor), OffsetMapping.Identity)
}

fun styledNote(raw: String, markerColor: Color, boldColor: Color): AnnotatedString {
    val glyphs = StringBuilder(raw)
    val spans = mutableListOf<Triple<SpanStyle, Int, Int>>()

    // List markers: swap the hyphen for a bullet in place, and push the marker back to
    // grey so the words are what start the line visually.
    var lineStart = 0
    while (lineStart <= raw.length) {
        val newline = raw.indexOf('\n', lineStart)
        val lineEnd = if (newline == -1) raw.length else newline
        val line = raw.substring(lineStart, lineEnd)

        BULLET_LINE.find(line)?.let { m ->
            val hyphen = lineStart + m.groupValues[1].length
            glyphs[hyphen] = '•'
            spans.add(Triple(SpanStyle(color = markerColor), hyphen, hyphen + 2))
        }
        NUMBER_LINE.find(line)?.let { m ->
            val start = lineStart + m.groupValues[1].length
            spans.add(Triple(SpanStyle(color = markerColor), start, start + m.groupValues[2].length))
        }

        if (newline == -1) break
        lineStart = newline + 1
    }

    // Bold: the words go heavy, the asterisks recede.
    BOLD.findAll(raw).forEach { m ->
        val open = m.range.first
        val close = m.range.last - 1
        spans.add(Triple(SpanStyle(color = markerColor), open, open + 2))
        spans.add(Triple(SpanStyle(color = markerColor), close, close + 2))
        spans.add(
            Triple(
                SpanStyle(fontWeight = FontWeight.Bold, color = boldColor),
                open + 2,
                close,
            ),
        )
    }

    return AnnotatedString.Builder(glyphs.toString()).apply {
        spans.forEach { (style, start, end) ->
            if (start in 0..glyphs.length && end in start..glyphs.length) {
                addStyle(style, start, end)
            }
        }
    }.toAnnotatedString()
}
