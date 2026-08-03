package com.gios.lightnotebook.util

import java.util.Locale

/**
 * Turns whatever arrived — a scanned QR, something typed with a thumb — into a calendar
 * feed URL, or into nothing.
 *
 * Subscribing to a URL is how a work calendar gets onto this phone: a server holds the
 * corporate credential and publishes one .ics, and the phone only has to fetch it. The URL
 * is therefore long, random and impossible to type correctly, which is why it normally
 * arrives by QR — and why anything that decodes but isn't a feed has to be rejected rather
 * than saved, so a poster in frame doesn't become a calendar that never loads.
 *
 * Android-free, so it is all tested off-device.
 */
object CalendarUrl {

    private const val SCHEME = "lightcal:"
    private const val MAX_LENGTH = 2000

    /** The normalised URL, or null if this payload is not one. */
    fun feedIn(payload: String?): String? {
        var raw = payload?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return null
        // The companion page can hand over a bare URL or a scheme-prefixed one, the same
        // way it does for the API key.
        if (raw.lowercase(Locale.US).startsWith(SCHEME)) {
            raw = raw.substring(SCHEME.length).trim()
        }
        // webcal:// is the same feed with a scheme no HTTP client will accept. Every
        // calendar publisher still hands them out, so rewrite rather than refuse.
        if (raw.lowercase(Locale.US).startsWith("webcal://")) {
            raw = "https://" + raw.substring("webcal://".length)
        }
        if (raw.any { it.isWhitespace() }) return null

        val lower = raw.lowercase(Locale.US)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        val host = raw.substringAfter("://").substringBefore('/').substringBefore('?')
        // A scheme with nothing after it is a typo, not an address; and a host has to have
        // at least a dot or be a bare hostname on the LAN.
        if (host.isBlank() || host.startsWith(":")) return null
        return raw
    }

    /**
     * A label for the calendar when the feed doesn't name itself: the host, minus the noise.
     * Better than "Imported calendar", and it is what the row will say for years.
     */
    fun labelFor(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            .removePrefix("www.")
        return host.takeIf { it.isNotBlank() } ?: "Subscribed calendar"
    }
}
