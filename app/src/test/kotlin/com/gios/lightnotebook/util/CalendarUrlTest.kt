package com.gios.lightnotebook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarUrlTest {

    @Test
    fun `plain https url is taken as is`() {
        val url = "https://cal.basilnet.com/cal/deadbeef/work.ics"
        assertEquals(url, CalendarUrl.feedIn(url))
    }

    @Test
    fun `http is allowed for a server on the lan`() {
        assertEquals(
            "http://192.168.68.59:8099/cal/x/work.ics",
            CalendarUrl.feedIn("http://192.168.68.59:8099/cal/x/work.ics"),
        )
    }

    @Test
    fun `webcal is rewritten rather than refused`() {
        // Every calendar publisher still hands out webcal links, and no HTTP client accepts
        // one. Refusing it would look like the feed was broken.
        assertEquals(
            "https://example.com/a.ics",
            CalendarUrl.feedIn("webcal://example.com/a.ics"),
        )
    }

    @Test
    fun `the scheme prefix from the companion page is stripped`() {
        assertEquals(
            "https://example.com/a.ics",
            CalendarUrl.feedIn("lightcal:https://example.com/a.ics"),
        )
    }

    @Test
    fun `surrounding whitespace is forgiven but internal whitespace is not`() {
        assertEquals("https://example.com/a.ics", CalendarUrl.feedIn("  https://example.com/a.ics "))
        assertNull(CalendarUrl.feedIn("https://example.com/a b.ics"))
    }

    @Test
    fun `anything that is not an http url is rejected`() {
        // This is what stops a poster's QR code, or an email address, becoming a calendar
        // that silently never loads.
        assertNull(CalendarUrl.feedIn("BEGIN:VCALENDAR"))
        assertNull(CalendarUrl.feedIn("mailto:g.lupo@lrparis.com"))
        assertNull(CalendarUrl.feedIn("file:///sdcard/work.ics"))
        assertNull(CalendarUrl.feedIn("https://"))
        assertNull(CalendarUrl.feedIn(""))
        assertNull(CalendarUrl.feedIn(null))
    }

    @Test
    fun `absurdly long payloads are rejected`() {
        assertNull(CalendarUrl.feedIn("https://example.com/" + "a".repeat(3000)))
    }

    @Test
    fun `the label is the host without the noise`() {
        assertEquals("cal.basilnet.com", CalendarUrl.labelFor("https://cal.basilnet.com/x/y.ics"))
        assertEquals("example.com", CalendarUrl.labelFor("https://www.example.com/y.ics"))
        assertEquals("192.168.68.59", CalendarUrl.labelFor("http://192.168.68.59:8099/y.ics"))
    }
}
