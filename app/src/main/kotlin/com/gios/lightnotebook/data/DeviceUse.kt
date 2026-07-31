package com.gios.lightnotebook.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.gios.lightnotebook.util.ScreenUse

/**
 * How much the phone was picked up and looked at, from the system's own usage events.
 *
 * **Retroactive, which is what makes this worth having.** Android keeps weeks of usage events, so a
 * day from last month can be answered without this app having been running for it — unlike the step
 * counter, which remembers nothing. Nothing is recorded here; it is all a query.
 *
 * The permission is an appop rather than a runtime permission, and there is no dialog to show for
 * it. On a phone with a Settings app the user would be sent to Usage Access; LightOS has no such
 * screen, so it is an adb grant and the app says so plainly:
 *
 *     adb shell appops set com.gios.lightnotebook GET_USAGE_STATS allow
 */
object DeviceUse {

    const val GRANT_COMMAND =
        "adb shell appops set com.gios.lightnotebook GET_USAGE_STATS allow"

    /**
     * Whether the appop is held.
     *
     * `unsafeCheckOpNoThrow` rather than trying a query and seeing if it comes back empty: without
     * the appop `queryEvents` returns **no events and no error**, which is indistinguishable from a
     * day you genuinely did not touch the phone. Asking directly is the difference between "not
     * granted" and "you had a quiet Sunday".
     */
    fun granted(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * One day's unlocks and screen time.
     *
     * The query begins **two hours before** the window. That is not caution, it is required: the
     * screen may have come on before midnight and stayed on, and the fold needs to know the state
     * at the boundary rather than guess it. Without the lookback a night spent up past midnight
     * reads as no screen time at all until the next time the phone was locked.
     */
    fun forDay(context: Context, windowStartMs: Long, windowEndMs: Long): ScreenUse.Result {
        if (!granted(context)) return ScreenUse.EMPTY
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return ScreenUse.EMPTY

        return runCatching {
            val lookback = windowStartMs - LOOKBACK_MS
            val events = manager.queryEvents(lookback, windowEndMs)
            val collected = ArrayList<ScreenUse.Event>(256)
            var onAtStart = false
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val kind = kindOf(event.eventType) ?: continue
                if (event.timeStamp < windowStartMs) {
                    // Before the day: not counted, but it tells us the state at midnight.
                    if (kind == ScreenUse.Kind.ScreenOn) onAtStart = true
                    if (kind == ScreenUse.Kind.ScreenOff) onAtStart = false
                    continue
                }
                collected.add(ScreenUse.Event(event.timeStamp, kind))
            }

            ScreenUse.fold(collected, windowStartMs, windowEndMs, onAtStart)
        }.getOrDefault(ScreenUse.EMPTY)
    }

    /**
     * Which events matter.
     *
     * `KEYGUARD_HIDDEN` is the unlock — an actual pick-up-and-look. `SCREEN_INTERACTIVE` is *not*
     * an unlock and is deliberately not counted as one: a notification lights the panel without
     * anyone touching it, and counting those inflates the one number here that is meant to be
     * honest about how often you reach for the phone.
     */
    private fun kindOf(eventType: Int): ScreenUse.Kind? = when (eventType) {
        UsageEvents.Event.KEYGUARD_HIDDEN -> ScreenUse.Kind.Unlocked
        UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenUse.Kind.ScreenOn
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenUse.Kind.ScreenOff
        else -> null
    }

    private const val LOOKBACK_MS = 2L * 60L * 60L * 1000L
}
