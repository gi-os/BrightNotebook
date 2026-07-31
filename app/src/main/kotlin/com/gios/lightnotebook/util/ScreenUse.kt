package com.gios.lightnotebook.util

/**
 * How much the phone was used on a day, folded out of the system's usage events.
 *
 * Two numbers, and on a Light Phone they are the interesting ones: how many times you picked it up,
 * and how long you had it open. Both are **retroactive** — unlike a step counter, the system keeps
 * weeks of usage events, so a day from last month can be answered without this app having been
 * awake for it.
 *
 * Android-free, because the querying is three lines and the folding is where every mistake lives:
 * an interval that was still open at midnight, an event pair that never closed because the process
 * died, a screen-on with no matching screen-off. Each of those, handled wrongly, produces a number
 * that looks plausible and is nonsense.
 */
object ScreenUse {

    /** Only the events this cares about, so the fold has no Android types in it. */
    enum class Kind {
        /** The keyguard went away — a real pick-up-and-look. */
        Unlocked,

        /** The screen became interactive. Not an unlock on its own; the phone may stay locked. */
        ScreenOn,
        ScreenOff,
    }

    data class Event(val atMs: Long, val kind: Kind)

    data class Result(
        val unlocks: Int,
        val screenOnMs: Long,
    ) {
        val screenOnMinutes: Int get() = (screenOnMs / 60_000L).toInt()
    }

    val EMPTY = Result(unlocks = 0, screenOnMs = 0L)

    /**
     * Fold a day's events into a count and a duration.
     *
     * [events] need not be sorted, need not be complete, and may begin or end mid-interval — all
     * three happen in practice. The rules:
     *
     * - **An interval open at the start of the window counts from the window's start.** The phone
     *   was on at midnight if it was on before midnight and never turned off, and a day that
     *   silently drops that reads as "you didn't use your phone until 8am" when you were up.
     * - **An interval still open at the end counts to the end.** Not to "now", which for a past day
     *   would be days of screen time.
     * - **A second ScreenOn with no ScreenOff between is ignored**, rather than restarting the
     *   clock. Duplicate events are normal, and taking the later one loses real time.
     * - Anything negative is impossible and is dropped rather than subtracted.
     */
    fun fold(
        events: List<Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        /** Whether the screen was already on when the window opened. */
        onAtStart: Boolean = false,
    ): Result {
        if (windowEndMs <= windowStartMs) return EMPTY

        val inWindow = events
            .filter { it.atMs in windowStartMs until windowEndMs }
            .sortedBy { it.atMs }

        var unlocks = 0
        var total = 0L
        var onSince: Long? = if (onAtStart) windowStartMs else null

        for (event in inWindow) {
            when (event.kind) {
                Kind.Unlocked -> unlocks++
                Kind.ScreenOn -> if (onSince == null) onSince = event.atMs
                Kind.ScreenOff -> {
                    val since = onSince
                    if (since != null) {
                        total += (event.atMs - since).coerceAtLeast(0L)
                        onSince = null
                    }
                }
            }
        }

        // Still on when the day ended: closed at the boundary, not left out and not run to now.
        onSince?.let { total += (windowEndMs - it).coerceAtLeast(0L) }

        return Result(
            unlocks = unlocks,
            // Cannot exceed the window. A duplicated or out-of-order event pair could otherwise
            // produce twenty-six hours of screen time in a day, which is the sort of number that
            // destroys trust in every other number on the screen.
            screenOnMs = total.coerceIn(0L, windowEndMs - windowStartMs),
        )
    }
}
