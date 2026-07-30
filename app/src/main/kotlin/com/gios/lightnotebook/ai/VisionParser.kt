package com.gios.lightnotebook.ai

import android.util.Base64
import com.gios.lightnotebook.util.NoteDates
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One dated thing read off a photographed planner. Times are minutes from midnight. */
data class ParsedEvent(
    val title: String,
    val epochDay: Long,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
)

/** What the photo turned out to be. */
sealed interface Vision {
    data class Note(val title: String, val body: String) : Vision
    data class Events(val events: List<ParsedEvent>) : Vision
    data class Failed(val reason: String) : Vision
}

/**
 * How to read the photo. AUTO is the normal path — the model is better at telling a
 * planner from a page of prose than a menu of modes would be. CALENDAR is only used when
 * the camera was opened from the calendar tab, where the intent is already known.
 */
enum class ReadMode { AUTO, CALENDAR }

/**
 * Reads a photographed page with Claude Haiku vision using the user's own key.
 *
 * One request does both the classification and the extraction. Two round trips would
 * double the latency on a phone that is already slow to focus, and the model has to
 * look at the whole page to tell a planner from a page of prose anyway.
 */
object VisionParser {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val MAX_RETRIES = 2
    private const val MAX_EVENTS = 60

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun read(imageFile: File, apiKey: String, mode: ReadMode = ReadMode.AUTO): Vision {
        if (apiKey.isBlank()) return Vision.Failed("No API key set — add one in Settings.")
        var last: Exception? = null
        repeat(MAX_RETRIES) {
            try {
                return requestOnce(imageFile, apiKey, mode)
            } catch (e: Exception) {
                last = e
            }
        }
        return Vision.Failed(last?.message?.take(140) ?: "Could not read that photo.")
    }

    /* ---------- prompt ---------- */

    private fun prompt(mode: ReadMode): String {
        val today = NoteDates.isoDate(NoteDates.today())
        val instruction = when (mode) {
            ReadMode.AUTO -> "Decide which of the two it is."
            ReadMode.CALENDAR -> "Treat it as a calendar or schedule, never as prose."
        }
        return """
            Today is $today.

            This is a photo of either (a) a page of handwritten or printed notes, or
            (b) a calendar, planner, schedule or list of dated events. $instruction

            Return ONLY valid JSON. No markdown fence, no backticks, no commentary.

            For notes:
            {"kind":"note","title":"short title, <=60 chars","body":"the text"}

            For calendars:
            {"kind":"calendar","events":[
              {"title":"what it is","date":"YYYY-MM-DD","start":"9:00 AM","end":"10:30 AM"}
            ]}

            Note rules:
            - Transcribe what is written. Do not summarise, correct or add anything.
            - Keep the line breaks that are on the page.
            - Where the page has a bulleted list, start those lines with "- ".
            - Where the page has a numbered list, start those lines with "1. ", "2. " and so on.
            - Where words are underlined, circled or clearly emphasised, wrap them in **like this**.
            - title is your own short summary, or the heading if the page has one.

            This is usually handwriting, so:
            - Read it as handwriting first. Cursive, print and a mix of both are all normal, and
              so are letters that join or break in the middle of a word.
            - Use the rest of the line, and the rest of the page, to settle an ambiguous letter.
              Handwriting is only legible in context: the same mark is an 'a' in one word and an
              'o' in another, and a word you have already read elsewhere on the page is the best
              evidence for what this one says.
            - Keep the writer's own spelling, abbreviations and shorthand — "w/", "Tues", "&",
              "appt". Do not expand or tidy them.
            - Faint pencil, biro that skips, and writing over ruled or squared lines are all
              still readable. Try before giving up on a word.
            - Crossed-out words are not part of the text. Skip them.
            - Words squeezed in above a line, or in a margin with an arrow, belong where the
              writer pointed them.
            - Use [?] for a word you genuinely cannot make out, and only then. One [?] per
              unreadable word, not per line. Never invent a plausible word to fill a gap.

            Calendar rules:
            - One entry per dated thing written on the page, handwriting included: a wall
              planner is mostly biro, and a day's square may hold two or three scrawled lines.
            - date must be YYYY-MM-DD. If the page shows a month and year, use them.
              If it shows only a day number, use the month and year visible elsewhere on
              the page. If nothing on the page gives a year, pick the occurrence nearest
              to today.
            - start and end are clock times as printed, or null when none is written.
            - Do not invent times for all-day items. Leave both null.
            - Skip the printed day-of-week headers and the grid itself; only real entries.

            If the photo is blank, blurred beyond reading, or holds nothing to record,
            return {"error":"unreadable"}.
        """.trimIndent()
    }

    /* ---------- request ---------- */

    private fun requestOnce(imageFile: File, apiKey: String, mode: ReadMode): Vision {
        val b64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 4000)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject().apply {
                                        put("type", "image")
                                        put(
                                            "source",
                                            JSONObject().apply {
                                                put("type", "base64")
                                                put("media_type", "image/jpeg")
                                                put("data", b64)
                                            },
                                        )
                                    },
                                )
                                .put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt(mode))
                                    },
                                ),
                        )
                    },
                ),
            )
        }.toString()

        val req = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return Vision.Failed(apiError(resp.code, raw))
            val text = textBlock(raw) ?: return Vision.Failed("Empty reply from Claude.")
            val json = objectIn(text) ?: return Vision.Failed("Could not read that photo.")
            return interpret(json, mode)
        }
    }

    /** Surfaces the two failures a user can actually fix, rather than a status code. */
    private fun apiError(code: Int, raw: String): String {
        val message = runCatching {
            JSONObject(raw).getJSONObject("error").getString("message")
        }.getOrNull()
        return when (code) {
            401, 403 -> "That API key was rejected. Check it in Settings."
            429 -> "Rate limited by the API. Try again in a moment."
            else -> message?.take(140) ?: "API error $code."
        }
    }

    /** The reply's first text block; content can also carry other block types. */
    private fun textBlock(raw: String): String? {
        val content = runCatching { JSONObject(raw).getJSONArray("content") }.getOrNull()
            ?: return null
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                return block.optString("text").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /**
     * Pulls the JSON object out of the reply. Models still occasionally wrap it in a
     * fence or a sentence, so take the outermost braces rather than trusting the shape.
     */
    private fun objectIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    /* ---------- interpretation ---------- */

    private fun interpret(json: JSONObject, mode: ReadMode): Vision {
        if (json.optString("error").isNotBlank()) {
            return Vision.Failed("Nothing legible on that page.")
        }

        val kind = json.optString("kind").lowercase()
        val looksLikeCalendar = when (mode) {
            ReadMode.CALENDAR -> true
            ReadMode.AUTO -> kind == "calendar" || json.has("events")
        }

        if (looksLikeCalendar) {
            val events = events(json.optJSONArray("events"))
            // A planner the model found nothing dated on is more useful as a note than
            // as an empty confirmation screen.
            if (events.isEmpty()) {
                return note(json).takeIf { it is Vision.Note }
                    ?: Vision.Failed("No dates found on that page.")
            }
            return Vision.Events(events)
        }

        return note(json)
    }

    private fun note(json: JSONObject): Vision {
        val body = json.optString("body").trim()
        if (body.isBlank()) return Vision.Failed("Nothing legible on that page.")
        val title = json.optString("title").trim().take(60)
        return Vision.Note(title = title, body = body)
    }

    private fun events(array: JSONArray?): List<ParsedEvent> {
        if (array == null) return emptyList()
        val out = mutableListOf<ParsedEvent>()
        for (i in 0 until minOf(array.length(), MAX_EVENTS)) {
            val entry = array.optJSONObject(i) ?: continue
            val title = entry.optString("title").trim()
            val day = NoteDates.parseIsoDate(entry.optString("date"))
            if (title.isBlank() || day == null) continue
            val start = NoteDates.parseClock(entry.optString("start").takeIf { it != "null" })
            val end = NoteDates.parseClock(entry.optString("end").takeIf { it != "null" })
            out.add(
                ParsedEvent(
                    title = title.take(120),
                    epochDay = day,
                    startMinutes = start,
                    endMinutes = end?.takeIf { start != null && it > start },
                ),
            )
        }
        return out.sortedWith(compareBy({ it.epochDay }, { it.startMinutes ?: -1 }))
    }
}
