package com.gios.lightnotebook.util

/**
 * How a handful of photographs sit on a page together.
 *
 * A row of identical thumbnails is a contact sheet. A page of a photo book is not: the pictures are
 * different sizes, they sit in rows of different counts, and the eye is given somewhere to land.
 * This decides the arrangement — how many photographs on each row — and the drawing follows.
 *
 * Deterministic, because the alternative is worse than it sounds. A random or hashed arrangement
 * would reshuffle itself when a photograph is added to the day, and a page that rearranges while you
 * are looking at it feels broken. The same count always produces the same page.
 *
 * Android-free: it is arithmetic about rows, and the interesting part is what happens at awkward
 * counts like five and seven.
 */
object PhotoTiles {

    /** Never more than this across: a quarter of a 3.92" panel is a thumbnail of nothing. */
    const val MAX_PER_ROW = 3

    /**
     * How many photographs go on each row, top to bottom.
     *
     * The small counts are chosen rather than computed, because they are the ones that happen and
     * the obvious arithmetic gets them wrong. Three as `[1, 2]` — one large above two smaller — is
     * the arrangement a photo book uses, where `[3]` is three little squares in a line. Five as
     * `[2, 3]` puts the bigger pair on top, which reads as a page rather than as a leftover.
     *
     * Past eight it settles into threes with the remainder folded into the last rows, so a burst of
     * forty is a block of pictures and not forty rows.
     */
    fun rows(count: Int): List<Int> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf(1)
        count == 2 -> listOf(2)
        count == 3 -> listOf(1, 2)
        count == 4 -> listOf(2, 2)
        count == 5 -> listOf(2, 3)
        count == 6 -> listOf(3, 3)
        count == 7 -> listOf(2, 2, 3)
        count == 8 -> listOf(3, 2, 3)
        else -> packed(count)
    }

    /**
     * Rows of three, with the tail balanced.
     *
     * A remainder of one would leave a single photograph alone on the last row looking like a
     * mistake, so it is borrowed against: the last two rows become two and two instead of three and
     * one. A remainder of two is a fine last row on its own.
     */
    private fun packed(count: Int): List<Int> {
        val full = count / MAX_PER_ROW
        val remainder = count % MAX_PER_ROW
        val out = MutableList(full) { MAX_PER_ROW }
        when (remainder) {
            0 -> Unit
            1 -> {
                // Take one from the last full row and make a pair of pairs.
                out[out.lastIndex] = MAX_PER_ROW - 1
                out.add(2)
            }
            else -> out.add(remainder)
        }
        return out
    }

    /**
     * The height of a row, as a fraction of the block's width.
     *
     * A row of one is a wide picture and a row of three is three small ones, so the height follows
     * the width each photograph gets — a 4:3 frame at `1/n` of the width is `0.75/n` tall. Rows
     * therefore vary in height by themselves, which is most of what makes the arrangement look like
     * a page instead of a grid.
     */
    fun rowHeightFraction(inRow: Int): Float =
        if (inRow <= 0) 0f else FRAME_ASPECT / inRow

    /** 4:3, the shape a photograph is cropped to everywhere else in the app. */
    const val FRAME_ASPECT = 0.75f

    /** The index each row starts at, for slicing the list without arithmetic at the call site. */
    fun rowRanges(count: Int): List<IntRange> {
        var start = 0
        return rows(count).map { inRow ->
            val range = start until (start + inRow)
            start += inRow
            range
        }
    }
}
