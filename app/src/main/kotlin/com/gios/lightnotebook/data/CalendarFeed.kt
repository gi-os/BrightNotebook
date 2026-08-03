package com.gios.lightnotebook.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

/**
 * Fetches a subscribed calendar feed. One GET, one string, no cleverness.
 *
 * This is what makes a work calendar possible on a Light Phone III without any of Microsoft
 * or Google's machinery on the phone: something else holds the account and publishes an
 * .ics, and the phone does a plain HTTP GET on a schedule. There is no OAuth here on
 * purpose — the feed URL carries its own secret, and a phone that can only fetch a URL
 * cannot leak a refresh token.
 *
 * Failures return null rather than throwing, because a calendar that could not be reached
 * this hour must leave the events already on the grid alone. [Sync] treats an empty read as
 * a failure for the same reason.
 */
object CalendarFeed {

    private const val TAG = "CalendarFeed"

    /** A published calendar of any sane size fits well inside this. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    // Short timeouts on purpose: this runs from an hourly alarm on a phone that may be
    // asleep on a bad connection, and a stalled read holding the wakelock is worse than a
    // missed refresh.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun fetch(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/calendar, text/plain, */*")
            // Named so a server log line says which device asked, and so a proxy that
            // dislikes empty agents doesn't answer with a login page.
            .header("User-Agent", "LightNotebook/1 (Light Phone III)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "feed answered ${response.code}")
                return@use null
            }
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_BYTES) {
                Log.w(TAG, "feed is ${body.contentLength()} bytes, refusing")
                return@use null
            }
            // Read through a limit as well as trusting the header: a chunked response
            // declares no length at all, so `contentLength()` is -1 and cannot be the only
            // guard. One `read` can also return short, hence the loop.
            body.source().use { source ->
                val buffer = Buffer()
                while (buffer.size < MAX_BYTES) {
                    if (source.read(buffer, MAX_BYTES - buffer.size) == -1L) break
                }
                buffer.readUtf8().takeIf { it.isNotBlank() }
            }
        }
    }.onFailure { Log.w(TAG, "feed fetch failed: ${it.message}") }.getOrNull()
}
