package com.gios.lightnotebook.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.gios.lightnotebook.util.AppUse
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
     * Everything a day wants from usage stats, in **one** pass over the events.
     *
     * There are three questions here — how long the screen was on, when it was picked up, and
     * where the time went — and they were three separate `queryEvents` calls over the same
     * window, which is three walks over the same few thousand events and three copies of them
     * materialised. On a day screen that rebuilds when you swipe between days, that is the most
     * expensive thing on the screen and none of it is necessary: the same stream answers all
     * three.
     */
    data class DayUse(
        val screen: ScreenUse.Result,
        val pickupsMs: List<Long>,
        val apps: List<AppUse.Total>,
    )

    fun dayUse(context: Context, windowStartMs: Long, windowEndMs: Long): DayUse {
        val empty = DayUse(ScreenUse.EMPTY, emptyList(), emptyList())
        if (!granted(context)) return empty
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return empty
        return runCatching {
            val events = manager.queryEvents(windowStartMs - LOOKBACK_MS, windowEndMs)
            val screenEvents = ArrayList<ScreenUse.Event>(256)
            val appEvents = ArrayList<AppUse.Event>(256)
            val pickups = ArrayList<Long>()
            var onAtStart = false
            var foregroundAtStart: String? = null
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val before = event.timeStamp < windowStartMs
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val pkg = event.packageName ?: continue
                        val kind = if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            AppUse.Kind.Resumed
                        } else {
                            AppUse.Kind.Paused
                        }
                        if (before) {
                            foregroundAtStart = if (kind == AppUse.Kind.Resumed) pkg else null
                        } else {
                            appEvents.add(AppUse.Event(event.timeStamp, pkg, kind))
                        }
                    }

                    else -> {
                        val kind = kindOf(event.eventType) ?: continue
                        if (kind == ScreenUse.Kind.Unlocked && !before) pickups.add(event.timeStamp)
                        if (before) {
                            // Before the day: not counted, but it tells us the state at the
                            // boundary, which is the whole reason for the lookback.
                            if (kind == ScreenUse.Kind.ScreenOn) onAtStart = true
                            if (kind == ScreenUse.Kind.ScreenOff) onAtStart = false
                        } else {
                            screenEvents.add(ScreenUse.Event(event.timeStamp, kind))
                        }
                    }
                }
            }

            DayUse(
                screen = ScreenUse.fold(screenEvents, windowStartMs, windowEndMs, onAtStart),
                pickupsMs = pickups.sorted(),
                apps = AppUse.fold(appEvents, windowStartMs, windowEndMs, foregroundAtStart),
            )
        }.getOrDefault(empty)
    }

    /**
     * An app's name as the launcher would say it, falling back to the last part of its package.
     *
     * A day that reads "38m COM.GIOS.LIGHTCHAT" is worse than one that says nothing.
     */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull() ?: packageName.substringAfterLast('.')

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
