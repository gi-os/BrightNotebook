package com.gios.lightnotebook.util

/**
 * Where the screen time went, folded out of resume/pause events.
 *
 * Screen time already says how long the phone was on. This says what it was on *for*, which is
 * the more honest number: "seven pickups" describes a habit, "thirty-eight minutes in Chat"
 * describes an afternoon.
 *
 * The fold is the whole difficulty and the reason this is a separate, Android-free file. Usage
 * events are not intervals — they are a stream of "this package came to the foreground" and
 * "this one left", from which intervals have to be reconstructed. Three things go wrong if you
 * take them at face value:
 *
 * - **An app can be resumed twice with no pause between**, when a package swaps activities.
 *   Counting from the first resume and again from the second double-counts the overlap.
 * - **The last app of the day never pauses.** It is still in the foreground when the window
 *   ends, or the phone simply slept, and an interval left open contributes nothing at all —
 *   which loses the single longest run of a day spent reading one thing.
 * - **The day starts mid-app.** Something was already in the foreground at the cutover, and
 *   its share of this day begins at the boundary rather than at its resume, which happened
 *   yesterday.
 */
object AppUse {

    enum class Kind { Resumed, Paused }

    data class Event(val atMs: Long, val packageName: String, val kind: Kind)

    /** One app's share of a day. [longestRunMs] is its single longest uninterrupted stretch. */
    data class Total(
        val packageName: String,
        val totalMs: Long,
        val longestRunMs: Long,
    ) {
        val minutes: Int get() = (totalMs / 60_000L).toInt()
    }

    /**
     * Folds events into per-package totals, largest first.
     *
     * [foregroundAtStart] is whatever was already open when the window began, from events read
     * before it — the same lookback trick [ScreenUse] uses, and for the same reason.
     */
    fun fold(
        events: List<Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        foregroundAtStart: String? = null,
    ): List<Total> {
        if (windowEndMs <= windowStartMs) return emptyList()
        val totals = HashMap<String, Long>()
        val longest = HashMap<String, Long>()
        // Only one package is in the foreground at a time, so this is a single open interval
        // rather than a map of them. A resume of a different package closes the one before it —
        // which is also what covers the missing pause when the phone was simply put down.
        var openPackage: String? = foregroundAtStart
        var openSince = windowStartMs

        fun close(atMs: Long) {
            val pkg = openPackage ?: return
            val until = atMs.coerceIn(windowStartMs, windowEndMs)
            val from = openSince.coerceIn(windowStartMs, windowEndMs)
            val span = until - from
            if (span > 0) {
                totals[pkg] = (totals[pkg] ?: 0L) + span
                if (span > (longest[pkg] ?: 0L)) longest[pkg] = span
            }
            openPackage = null
        }

        events.asSequence()
            .filter { it.atMs in windowStartMs..windowEndMs }
            .sortedBy { it.atMs }
            .forEach { event ->
                when (event.kind) {
                    Kind.Resumed -> {
                        // A resume of the same package with no pause is not a new interval; it is
                        // the same one continuing, and restarting the clock would lose the time
                        // already accumulated in it.
                        if (openPackage == event.packageName) return@forEach
                        close(event.atMs)
                        openPackage = event.packageName
                        openSince = event.atMs
                    }

                    Kind.Paused -> if (openPackage == event.packageName) close(event.atMs)
                }
            }
        // Whatever was still open when the day ended ran to the end of it.
        close(windowEndMs)

        return totals.map { (pkg, total) ->
            Total(packageName = pkg, totalMs = total, longestRunMs = longest[pkg] ?: total)
        }.sortedWith(compareByDescending<Total> { it.totalMs }.thenBy { it.packageName })
    }

    /**
     * The line the day's footer shows: the biggest few, named, with anything under a minute
     * dropped.
     *
     * Three, because a fourth entry pushes the row onto a second line on this panel, and
     * because the tail of a day's app list is all thirty-second glances at the same two things.
     */
    fun summary(totals: List<Total>, nameOf: (String) -> String, limit: Int = 3): List<String> =
        totals.asSequence()
            .filter { it.minutes >= 1 }
            .take(limit)
            .map { "${it.minutes}M ${nameOf(it.packageName).uppercase()}" }
            .toList()
}
