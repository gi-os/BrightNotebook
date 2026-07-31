package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShakeGestureTest {

    /** A shake: alternating hard swings, one every 100ms. */
    private fun shake(g: ShakeGesture, from: Long, swings: Int, everyMs: Long = 100): Int {
        var fires = 0
        for (i in 0 until swings) {
            val magnitude = if (i % 2 == 0) 2.0f else 0.2f
            if (g.sample(from + i * everyMs, magnitude)) fires++
        }
        return fires
    }

    @Test
    fun `still phone never fires`() {
        val g = ShakeGesture()
        for (i in 0 until 500) assertFalse(g.sample(i * 20L, 1.0f))
    }

    @Test
    fun `walking never fires`() {
        val g = ShakeGesture()
        // A brisk walk is roughly +-0.3g at 2Hz, sampled at 50Hz. It reverses, but never
        // hard enough to be looked at.
        for (i in 0 until 1000) {
            val phase = Math.sin(i * 0.25).toFloat()
            assertFalse(g.sample(i * 20L, 1f + 0.3f * phase))
        }
    }

    @Test
    fun `three alternations fire once`() {
        val g = ShakeGesture()
        assertEquals(1, shake(g, 0, 3))
    }

    @Test
    fun `two alternations are not enough`() {
        val g = ShakeGesture()
        assertEquals(0, shake(g, 0, 2))
    }

    @Test
    fun `a flick just past the threshold still fires`() {
        val g = ShakeGesture()
        // Not a rattle — a wrist turned out and back and out, barely clearing 0.38g. This is
        // the case the first thresholds got wrong: it felt like a shake and did nothing.
        var fires = 0
        val swing = floatArrayOf(1.45f, 0.55f, 1.45f)
        swing.forEachIndexed { i, m -> if (g.sample(i * 120L, m)) fires++ }
        assertEquals(1, fires)
    }

    @Test
    fun `slow waving does not accumulate`() {
        val g = ShakeGesture()
        // Same swings, same force, one every 900ms — someone gesturing with the phone
        // in hand rather than flicking it.
        assertEquals(0, shake(g, 0, 12, everyMs = 900))
    }

    @Test
    fun `one long shake is one report, not three`() {
        val g = ShakeGesture()
        // Twenty alternations without pause: the first three fire, the cooldown eats the rest.
        assertEquals(1, shake(g, 0, 20))
    }

    @Test
    fun `a second shake after the cooldown fires again`() {
        val g = ShakeGesture()
        assertEquals(1, shake(g, 0, 3))
        assertEquals(1, shake(g, 10_000, 3))
    }

    @Test
    fun `a dropped phone is a single jolt, not a shake`() {
        val g = ShakeGesture()
        var fires = 0
        // Free fall, then one hard landing, then still.
        for (i in 0 until 15) if (g.sample(i * 20L, 0.05f)) fires++
        for (i in 15 until 18) if (g.sample(i * 20L, 4.0f)) fires++
        for (i in 18 until 200) if (g.sample(i * 20L, 1.0f)) fires++
        assertEquals(0, fires)
    }

    @Test
    fun `reset abandons a half-finished gesture`() {
        val g = ShakeGesture()
        assertEquals(0, shake(g, 0, 2))
        g.reset()
        // One short of firing before the reset, so the next two start again from nothing
        // rather than completing the abandoned gesture.
        assertEquals(0, shake(g, 5_000, 2))
    }
}
