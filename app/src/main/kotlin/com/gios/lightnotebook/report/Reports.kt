package com.gios.lightnotebook.report

import android.content.Context
import android.os.Build
import com.gios.lightnotebook.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What went wrong, in the order the chips appear.
 *
 * Two labels because the two readers are different: `chip` has to fit a third of a 3.92" screen,
 * `label` is read weeks later in an issue title with no phone in front of you.
 */
enum class Symptom(val chip: String, val label: String, val slug: String) {
    Crashed("CLOSED", "It closed itself", "crash"),
    Froze("FROZE", "It stopped responding", "freeze"),
    Wrong("LOOKS OFF", "Something looks wrong", "render"),
    Slow("SLOW", "It was very slow", "slow"),
    Other("OTHER", "Something else", "other"),
}

/** A report on its way out: exactly the three fields the issues API wants. */
data class Report(val title: String, val body: String, val labels: List<String>)

/**
 * Shake-to-report, from the phone to a GitHub issue.
 *
 * Reports queue on disk first and are posted afterwards, always — not as a fallback for being
 * offline. A phone that reports a freeze is by definition a phone that was just misbehaving, and
 * a report that exists only in flight is the one report guaranteed to be lost. The queue is also
 * why the send button can close the sheet immediately: nothing the user sees depends on a socket.
 *
 * The screenshot rides inside the issue body as base64 rather than being committed to the repo.
 * That is a deliberate trade: attaching a file would need `contents: write` on the token, and the
 * token ships inside a sideloaded APK where anyone can read it out. Kept to `issues: write`, the
 * worst a lifted token can do is write junk into one private tracker.
 */
object Reports {

    private const val DIR = "reports"
    private const val MAX_QUEUED = 20
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http by lazy {
        OkHttpClient.Builder()
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** True when this build can actually send. False means reports pile up in the queue. */
    fun canSend(): Boolean = BuildConfig.REPORT_TOKEN.isNotBlank()

    fun pendingCount(context: Context): Int = queued(context).size

    /**
     * Turn what the sheet collected into an issue body.
     *
     * @param shot base64 PNG, or null when there is no screenshot or it would not fit.
     */
    fun compose(
        context: Context,
        symptom: Symptom,
        note: String,
        screen: String,
        crash: String?,
        shot: String?,
        failure: Failure? = null,
    ): Report {
        val version = versionName(context)
        val headline = note.trim().takeIf { it.isNotEmpty() }?.let { first(it) } ?: symptom.label
        val body = buildString {
            appendLine("### What happened")
            appendLine()
            appendLine(symptom.label + (note.trim().takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""))
            appendLine()
            if (failure != null) {
                appendLine("### What the app itself reported")
                appendLine()
                appendLine("Could not ${failure.what}.")
                if (!failure.detail.isNullOrBlank()) {
                    appendLine()
                    appendLine("```")
                    appendLine(failure.detail)
                    appendLine("```")
                }
                appendLine()
            }
            appendLine("### Where")
            appendLine()
            appendLine("On the `$screen` screen.")
            appendLine()
            appendLine("### Build")
            appendLine()
            appendLine("| | |")
            appendLine("|-|-|")
            appendLine("| App | Notebook $version |")
            appendLine("| Package | ${context.packageName} |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) |")
            appendLine("| Device | ${Build.MANUFACTURER} ${Build.MODEL} |")
            appendLine("| Firmware | ${Build.DISPLAY} |")
            appendLine("| Reported | ${stamp()} |")
            appendLine("| Free space | ${megabytes(context.filesDir.freeSpace)} |")
            appendLine("| Heap | ${megabytes(usedHeap())} of ${megabytes(Runtime.getRuntime().maxMemory())} |")
            appendLine()
            appendLine("### Last crash")
            appendLine()
            if (crash.isNullOrBlank()) {
                appendLine("None — the app did not die, so this is a glitch and not a stack trace.")
            } else {
                appendLine("```")
                appendLine(crash.trim())
                appendLine("```")
            }
            if (shot != null) {
                appendLine()
                appendLine("<details><summary>Screenshot (base64 PNG, greyscale)</summary>")
                appendLine()
                appendLine("```")
                appendLine(shot)
                appendLine("```")
                appendLine()
                appendLine("</details>")
            }
        }
        val labels = buildList {
            add("notebook")
            add(if (!crash.isNullOrBlank()) "crash" else symptom.slug)
            // Worth separating: the app noticed this one on its own, so it is reproducible
            // from the detail rather than from somebody remembering what they were doing.
            if (failure != null) add("self-reported")
        }
        return Report(
            title = "Notebook $version — $headline",
            body = body,
            labels = labels,
        )
    }

    /** Write it down. Never throws: losing a report must not become a second crash. */
    fun enqueue(context: Context, report: Report) {
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            // Oldest out first if the queue has been filling up offline. A stale report is
            // worth less than the one being written right now.
            queued(context).dropLast(MAX_QUEUED - 1).forEach { it.delete() }
            val json = JSONObject().apply {
                put("title", report.title)
                put("body", report.body)
                put("labels", JSONArray(report.labels))
            }
            File(dir, "${System.currentTimeMillis()}.json").writeText(json.toString())
        }
    }

    /**
     * Post everything waiting, oldest first. Returns how many left the phone.
     *
     * Stops at the first one that fails for a reason a later attempt could fix — no point
     * burning the rest of the queue against an outage — but keeps going past one that is
     * simply unpostable, which would otherwise wedge everything behind it forever.
     */
    suspend fun flush(context: Context): Int = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext 0
        var sent = 0
        for (file in queued(context)) {
            val report = runCatching {
                val o = JSONObject(file.readText())
                Report(
                    title = o.getString("title"),
                    body = o.getString("body"),
                    labels = List(o.getJSONArray("labels").length()) { o.getJSONArray("labels").getString(it) },
                )
            }.getOrNull()
            if (report == null) {
                file.delete()
                continue
            }
            when (post(report)) {
                Outcome.Sent -> {
                    file.delete()
                    sent++
                }
                Outcome.Unpostable -> file.delete()
                Outcome.Later -> return@withContext sent
            }
        }
        sent
    }

    private enum class Outcome { Sent, Later, Unpostable }

    private fun post(report: Report): Outcome {
        val body = JSONObject().apply {
            put("title", report.title)
            put("body", report.body)
            put("labels", JSONArray(report.labels))
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.REPORT_REPO}/issues")
            .header("Authorization", "Bearer ${BuildConfig.REPORT_TOKEN}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Outcome.Sent
                    // 422 is a body GitHub will never accept, 413 one it will never take.
                    // Anything else — no network, a rate limit, a token that has not been
                    // put in the build yet — is worth another go on the next launch.
                    response.code == 422 || response.code == 413 -> Outcome.Unpostable
                    else -> Outcome.Later
                }
            }
        }.getOrDefault(Outcome.Later)
    }

    /** Oldest first, which is the order they should be read in. */
    private fun queued(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun first(note: String): String =
        note.lineSequence().first().trim().let { if (it.length > 72) it.take(69) + "…" else it }

    private fun versionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.let { "v$it" } ?: "v?"

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun megabytes(bytes: Long): String =
        if (bytes >= 1_073_741_824) String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        else String.format(Locale.US, "%d MB", bytes / 1_048_576)

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
}
