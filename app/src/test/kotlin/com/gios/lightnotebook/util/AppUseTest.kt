package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUseTest {

    private val start = 1_000_000L
    private val end = start + 60 * 60_000L // an hour

    private fun at(minutes: Int) = start + minutes * 60_000L

    private fun resumed(minutes: Int, pkg: String) =
        AppUse.Event(at(minutes), pkg, AppUse.Kind.Resumed)

    private fun paused(minutes: Int, pkg: String) =
        AppUse.Event(at(minutes), pkg, AppUse.Kind.Paused)

    @Test
    fun `a plain run counts its own length`() {
        val totals = AppUse.fold(
            listOf(resumed(0, "chat"), paused(10, "chat")),
            start,
            end,
        )
        assertEquals(10, totals.single().minutes)
    }

    @Test
    fun `a resume of the same package is the same run, not a second one`() {
        // A package swapping activities resumes twice with no pause between. Treating the second
        // resume as a new interval loses everything accumulated in the first.
        val totals = AppUse.fold(
            listOf(resumed(0, "chat"), resumed(4, "chat"), paused(10, "chat")),
            start,
            end,
        )
        assertEquals(10, totals.single().minutes)
    }

    @Test
    fun `a resume of another package closes the one before it`() {
        // The missing-pause case: you put the phone down in one app and pick it up in another.
        val totals = AppUse.fold(
            listOf(resumed(0, "chat"), resumed(10, "phono"), paused(25, "phono")),
            start,
            end,
        ).associateBy { it.packageName }
        assertEquals(10, totals.getValue("chat").minutes)
        assertEquals(15, totals.getValue("phono").minutes)
    }

    @Test
    fun `an app still open when the day ends runs to the end of it`() {
        // Otherwise the longest run of a day spent reading one thing contributes nothing.
        val totals = AppUse.fold(listOf(resumed(30, "chat")), start, end)
        assertEquals(30, totals.single().minutes)
    }

    @Test
    fun `an app already open when the day begins starts at the boundary`() {
        val totals = AppUse.fold(
            listOf(paused(20, "chat")),
            start,
            end,
            foregroundAtStart = "chat",
        )
        assertEquals(20, totals.single().minutes)
    }

    @Test
    fun `totals are largest first`() {
        val totals = AppUse.fold(
            listOf(
                resumed(0, "small"), paused(5, "small"),
                resumed(5, "big"), paused(45, "big"),
            ),
            start,
            end,
        )
        assertEquals(listOf("big", "small"), totals.map { it.packageName })
    }

    @Test
    fun `the longest run is remembered separately from the total`() {
        val totals = AppUse.fold(
            listOf(
                resumed(0, "chat"), paused(5, "chat"),
                resumed(10, "chat"), paused(40, "chat"),
            ),
            start,
            end,
        )
        assertEquals(35, totals.single().minutes)
        assertEquals(30 * 60_000L, totals.single().longestRunMs)
    }

    @Test
    fun `events outside the window are ignored`() {
        val totals = AppUse.fold(
            listOf(resumed(0, "chat"), paused(10, "chat")),
            start + 20 * 60_000L,
            end,
        )
        assertTrue(totals.isEmpty())
    }

    @Test
    fun `the summary names the biggest few and drops the seconds`() {
        val totals = listOf(
            AppUse.Total("com.gios.lightchat", 38 * 60_000L, 20 * 60_000L),
            AppUse.Total("com.gios.lightphono", 12 * 60_000L, 12 * 60_000L),
            AppUse.Total("com.gios.lightnews", 3 * 60_000L, 3 * 60_000L),
            AppUse.Total("com.gios.lighttip", 40_000L, 40_000L),
        )
        assertEquals(
            listOf("38M CHAT", "12M PHONO", "3M NEWS"),
            AppUse.summary(totals, nameOf = { it.substringAfterLast('.').removePrefix("light") }),
        )
    }

    @Test
    fun `an empty day summarises to nothing`() {
        assertTrue(AppUse.summary(emptyList(), nameOf = { it }).isEmpty())
        assertTrue(AppUse.fold(emptyList(), start, end).isEmpty())
    }
}
