package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceKeysTest {

    @Test
    fun `two visits to the same cafe share a key`() {
        // The whole point: a few metres apart on two different days must not be two lookups.
        val monday = PlaceKeys.of(40.71281, -74.00601)
        val friday = PlaceKeys.of(40.71284, -74.00598)
        assertEquals(monday, friday)
    }

    @Test
    fun `somewhere genuinely else does not`() {
        assertNotEquals(PlaceKeys.of(40.7128, -74.0060), PlaceKeys.of(40.7500, -73.9900))
    }

    @Test
    fun `the key is stable, not merely equal by luck`() {
        assertEquals(PlaceKeys.of(40.7128, -74.0060), PlaceKeys.of(40.7128, -74.0060))
    }

    @Test
    fun `negative coordinates round the same way as positive ones`() {
        // Truncation instead of rounding would put the southern and western hemispheres on a grid
        // offset by half a cell from the northern and eastern ones.
        assertEquals(PlaceKeys.of(-40.71281, -74.00601), PlaceKeys.of(-40.71284, -74.00598))
    }

    @Test
    fun `the equator and the meridian are not special`() {
        assertEquals(PlaceKeys.of(0.00001, 0.00002), PlaceKeys.of(0.0, 0.0))
    }

    @Test
    fun `the same spot is recognised without going to disk`() {
        assertTrue(PlaceKeys.sameSpot(40.7128, -74.0060, 40.71284, -74.00597))
        assertFalse(PlaceKeys.sameSpot(40.7128, -74.0060, 40.7200, -74.0060))
    }
}
