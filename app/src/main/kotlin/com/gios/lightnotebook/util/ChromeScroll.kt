package com.gios.lightnotebook.util

import kotlin.math.abs

/**
 * Whether the bars belong on screen, decided from two successive scroll positions.
 *
 * Pulled out of the day screen because it is the sort of rule that is wrong in one specific place
 * and cannot be tested where it lived. The rule itself: the chrome gets out of the way as you read
 * down the day and comes back the moment you reach for it by scrolling up — *direction* of travel,
 * not position, so a long day does not permanently hide its own header once you are past the top.
 *
 * **The end of the list is the exception.** Hiding the chrome makes the list taller, and a list
 * already at its last pixel answers a taller viewport by clamping its own scroll — the offset goes
 * *down* without a finger moving. Read as travel that is an up-scroll, so the bars come back, which
 * makes the list shorter again, which pushes the day back down: the bottom of a day bounced and
 * re-showed the bars every time you reached it. So an up-scroll only counts while the list still
 * has somewhere forward to go.
 */
object ChromeScroll {

    /** Ignore a few pixels of jitter, or the bars flicker while a finger rests on the screen. */
    const val SLOP = 12

    /** A scroll position: what the list reports, plus whether anything is left below it. */
    data class Position(
        val index: Int,
        val offset: Int,
        val canScrollForward: Boolean,
    )

    /**
     * `true` to hide the chrome, `false` to show it, `null` to leave it exactly as it is —
     * which is the answer for a clamp at the end of the list, and for jitter under a resting thumb.
     */
    fun hidden(from: Position, to: Position, slop: Int = SLOP): Boolean? {
        val movedDown = to.index > from.index ||
            (to.index == from.index && to.offset > from.offset + slop)
        val movedUp = to.index < from.index ||
            (to.index == from.index && to.offset < from.offset - slop)
        // The top of the day always shows its chrome: there is nothing above it to read, and
        // arriving at a day with no header would look like a broken screen.
        val atTop = to.index == 0 && to.offset < slop
        return when {
            atTop -> false
            movedDown -> true
            movedUp && to.canScrollForward -> false
            else -> null
        }
    }

    /**
     * Whether this position replaces the one the next comparison is made against. Jitter must not,
     * or a slow drift past the slop never registers as travel at all.
     */
    fun advanced(from: Position, to: Position, slop: Int = SLOP): Boolean =
        to.index != from.index || abs(to.offset - from.offset) > slop
}
