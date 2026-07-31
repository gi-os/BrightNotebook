package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenUseTest {

    private val start = 1_753_848_000_000L
    private val end = start + 86_400_000L
    private fun at(hours: Int, minutes: Int = 0) = start + hours * 3_600_000L + minutes * 60_000L

    private fun on(h: Int, m: Int = 0) = ScreenUse.Event(at(h, m), ScreenUse.Kind.ScreenOn)
    private fun off(h: Int, m: Int = 0) = ScreenUse.Event(at(h, m), ScreenUse.Kind.ScreenOff)
    private fun unlock(h: Int, m: Int = 0) = ScreenUse.Event(at(h, m), ScreenUse.Kind.Unlocked)

    private fun fold(events: List<ScreenUse.Event>, onAtStart: Boolean = false) =
        ScreenUse.fold(events, start, end, onAtStart)

    @Test
    fun `paired intervals add up`() {
        val r = fold(listOf(on(9), off(9, 20), on(14), off(14, 10)))
        assertEquals(30, r.screenOnMinutes)
    }

    @Test
    fun `unlocks are counted, and are not the same thing as the screen coming on`() {
        // The screen turning on is not a pick-up: a notification lights the panel without you
        // touching it, and counting those would inflate the number that is meant to be honest.
        val r = fold(listOf(on(9), unlock(9), off(9, 5), on(11), off(11, 1)))
        assertEquals(1, r.unlocks)
        assertEquals(6, r.screenOnMinutes)
    }

    @Test
    fun `an interval left open at midnight is closed at midnight`() {
        // Not run on to "now" — for a day last month that would be weeks of screen time.
        val r = fold(listOf(on(23, 30)))
        assertEquals(30, r.screenOnMinutes)
    }

    @Test
    fun `the screen being on when the day started counts from the start`() {
        // Up past midnight. Dropping this reads as "you didn't touch your phone until 8am".
        val r = fold(listOf(off(0, 40)), onAtStart = true)
        assertEquals(40, r.screenOnMinutes)
    }

    @Test
    fun `a duplicate screen-on does not restart the clock`() {
        // Taking the later event would lose the first ten minutes.
        val r = fold(listOf(on(9), on(9, 10), off(9, 30)))
        assertEquals(30, r.screenOnMinutes)
    }

    @Test
    fun `a screen-off with nothing open is ignored`() {
        val r = fold(listOf(off(3), on(9), off(9, 15)))
        assertEquals(15, r.screenOnMinutes)
    }

    @Test
    fun `events are not assumed to be in order`() {
        val r = fold(listOf(off(14, 10), on(9), off(9, 20), on(14)))
        assertEquals(30, r.screenOnMinutes)
    }

    @Test
    fun `events outside the day are not this day's`() {
        val before = ScreenUse.Event(start - 60_000L, ScreenUse.Kind.Unlocked)
        val after = ScreenUse.Event(end + 60_000L, ScreenUse.Kind.Unlocked)
        assertEquals(0, fold(listOf(before, after)).unlocks)
    }

    @Test
    fun `midnight belongs to the day it starts`() {
        assertEquals(1, fold(listOf(ScreenUse.Event(start, ScreenUse.Kind.Unlocked))).unlocks)
        assertEquals(0, fold(listOf(ScreenUse.Event(end, ScreenUse.Kind.Unlocked))).unlocks)
    }

    @Test
    fun `screen time can never exceed the day`() {
        // A duplicated or out-of-order pair could otherwise produce twenty-six hours in a day,
        // which is the kind of number that destroys trust in every other number on the screen.
        val silly = (0..40).flatMap { listOf(on(0), off(23, 59)) }
        val r = fold(silly)
        assertEquals(24 * 60 - 1, r.screenOnMinutes.coerceAtMost(24 * 60 - 1))
        assert(r.screenOnMs <= end - start)
    }

    @Test
    fun `an empty day is empty rather than absent`() {
        assertEquals(ScreenUse.EMPTY, fold(emptyList()))
    }

    @Test
    fun `a backwards window is not a day`() {
        assertEquals(ScreenUse.EMPTY, ScreenUse.fold(listOf(on(9), off(10)), end, start))
    }
}
