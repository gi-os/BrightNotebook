package com.gios.lightnotebook.util

/**
 * Charging, as spans of a day rather than as a battery percentage.
 *
 * A number on a gauge says nothing about a day. "Plugged in from 23:40 to 7:10" says you went to
 * bed and got up, which is a fact about you, and it is the only such fact this phone will hand
 * over for free — the broadcasts arrive whether or not anything is listening.
 *
 * Kept Android-free so the pairing is testable, because pairing is where this goes wrong: the
 * events are plug and unplug, and a night's charge is a *pair* that straddles a day boundary. A
 * charge that begins on Tuesday and ends on Wednesday belongs, in part, to both.
 */
object Charging {

    enum class Kind { Plugged, Unplugged }

    data class Event(val atMs: Long, val kind: Kind)

    /** A stretch of being plugged in, clipped to the day it is being shown on. */
    data class Span(
        val fromMs: Long,
        val untilMs: Long,
        /** True when the charge began before this day started. */
        val startedEarlier: Boolean,
        /** True when it was still going when the day ended — or is still going now. */
        val stillGoing: Boolean,
    ) {
        val lengthMinutes: Int get() = ((untilMs - fromMs) / 60_000L).toInt()
    }

    /**
     * Pairs raw events into spans clipped to one day's window.
     *
     * Unmatched events are the normal case, not the error case, so both are handled rather than
     * dropped: an unplug with no plug before it means the charge started before this window
     * (a night's charge, seen from the morning), and a plug with no unplug after it means it was
     * still going when the window ended — or still is, right now.
     *
     * [events] may include events from outside the window; that is what makes the first case
     * resolvable at all.
     */
    fun spansIn(
        events: List<Event>,
        windowStartMs: Long,
        windowEndMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Span> {
        if (windowEndMs <= windowStartMs) return emptyList()
        val sorted = events.sortedBy { it.atMs }
        val spans = mutableListOf<Span>()
        var plugged: Long? = null
        // Whatever the state was when the window opened, decided by the last event before it.
        var startedEarlier = sorted.lastOrNull { it.atMs <= windowStartMs }?.kind == Kind.Plugged
        if (startedEarlier) plugged = windowStartMs

        sorted.filter { it.atMs > windowStartMs && it.atMs <= windowEndMs }.forEach { event ->
            when (event.kind) {
                Kind.Plugged -> if (plugged == null) plugged = event.atMs
                Kind.Unplugged -> {
                    val from = plugged ?: return@forEach
                    spans.add(
                        Span(
                            fromMs = from,
                            untilMs = event.atMs,
                            startedEarlier = from == windowStartMs && startedEarlier,
                            stillGoing = false,
                        ),
                    )
                    plugged = null
                    startedEarlier = false
                }
            }
        }

        // Still plugged in when the window closed. Ended at the window's end, or at now if the
        // day is today — "charging since 14:00" is true; "charging until midnight" is a guess.
        plugged?.let { from ->
            val until = minOf(windowEndMs, maxOf(nowMs, from))
            if (until > from) {
                spans.add(
                    Span(
                        fromMs = from,
                        untilMs = until,
                        startedEarlier = from == windowStartMs && startedEarlier,
                        stillGoing = true,
                    ),
                )
            }
        }
        // A blip — plugged in and straight back out — is noise on a day, not an event in it.
        return spans.filter { it.lengthMinutes >= MIN_MINUTES }
    }

    /** Below this it is a cable being knocked, not a charge. */
    const val MIN_MINUTES = 5

    /** "7h 30m", "45m" — how long it was on the cable. */
    fun length(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
