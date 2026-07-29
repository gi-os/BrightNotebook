package com.gios.lightnotebook.util

/**
 * Pulls an Anthropic key out of a scanned QR payload.
 *
 * A QR code in the wild is far more likely to be a URL than an API key, so anything not
 * shaped like a key is rejected and the scanner keeps looking. Saving a poster's link as
 * the API key would only surface later as an authentication failure with no explanation.
 *
 * Android-free so it can be tested off-device.
 */
object ApiKeyQr {

    private const val PREFIX = "sk-ant-"
    private const val SCHEME = "anthropic:"
    private const val MIN_LENGTH = 24

    fun keyIn(payload: String?): String? {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // The companion page can hand over a bare key or a scheme-prefixed one.
        val body = if (raw.startsWith(SCHEME, ignoreCase = true)) {
            raw.removePrefix(raw.take(SCHEME.length)).trim()
        } else {
            raw
        }
        if (!body.startsWith(PREFIX)) return null
        if (body.length < MIN_LENGTH) return null
        if (body.any { it.isWhitespace() }) return null
        return body
    }
}
