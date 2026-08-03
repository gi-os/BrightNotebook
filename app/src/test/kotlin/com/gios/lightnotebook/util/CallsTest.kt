package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {

    private fun call(
        kind: Calls.Kind,
        seconds: Int = 120,
        name: String? = "Alex",
        number: String? = "9175551234",
    ) = Calls.Call(atMs = 0L, name = name, number = number, kind = kind, seconds = seconds)

    @Test
    fun `the three kinds read as three different facts`() {
        assertEquals("Called Alex", call(Calls.Kind.Outgoing).phrase)
        assertEquals("Alex called", call(Calls.Kind.Incoming).phrase)
        assertEquals("Missed call from Alex", call(Calls.Kind.Missed).phrase)
    }

    @Test
    fun `a number stands in when the phone had no name`() {
        assertEquals("Called (917) 555-1234", call(Calls.Kind.Outgoing, name = null).phrase)
        assertEquals(
            "Called Unknown number",
            call(Calls.Kind.Outgoing, name = null, number = null).phrase,
        )
        // A name of whitespace is not a name.
        assertEquals("Called (917) 555-1234", call(Calls.Kind.Outgoing, name = "  ").phrase)
    }

    @Test
    fun `numbers are grouped when they are the shape this phone sees`() {
        assertEquals("(917) 555-1234", Calls.pretty("9175551234"))
        assertEquals("(917) 555-1234", Calls.pretty("+1 917 555 1234"))
        // Anything else is left exactly as it came, rather than mangled into a US shape.
        assertEquals("+33 1 42 86 82 00", Calls.pretty("+33 1 42 86 82 00"))
        assertEquals("611", Calls.pretty("611"))
    }

    @Test
    fun `a length is stated only when there was one`() {
        assertEquals("2 min", call(Calls.Kind.Outgoing, seconds = 120).length)
        assertEquals("40 sec", call(Calls.Kind.Incoming, seconds = 40).length)
        // A missed call has no duration, and a zero-second answered call never connected.
        assertNull(call(Calls.Kind.Missed, seconds = 0).length)
        assertNull(call(Calls.Kind.Outgoing, seconds = 0).length)
    }

    @Test
    fun `a misdial is not a thing that happened, but a missed call is`() {
        assertTrue(!Calls.worthShowing(call(Calls.Kind.Outgoing, seconds = 0)))
        assertTrue(Calls.worthShowing(call(Calls.Kind.Missed, seconds = 0)))
        assertTrue(Calls.worthShowing(call(Calls.Kind.Outgoing, seconds = 5)))
    }
}
