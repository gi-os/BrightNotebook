package com.gios.lightnotebook.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Whether BrightControl is drawing the on-screen box for every app, so this one should not.
 *
 * BrightControl v3.65 grew a banner of its own: it reads the shade through a notification listener
 * and puts the same box over the screen for whatever posted. It is drawn off the very notification
 * [Notifier.post] raises a moment earlier — so with both switched on, a reminder was one buzz and
 * **two boxes**, one landing on top of the other.
 *
 * ### Why this one stands down and not that one
 *
 * BrightControl knows about every app on the phone; this app knows about reminders. And the two
 * boxes do the same things: this one shows the entry and its time and opens the day on a tap,
 * BrightControl's shows the same two lines and sends the same `contentIntent`, which *is* this
 * app's open-the-day intent. Nothing is lost — the close button here becomes a swipe up there,
 * which is the gesture the shape already suggested.
 *
 * BrightControl's box also arrives without an activity. [com.gios.lightnotebook.ui.ReminderAlertActivity]
 * has to be one, because an ordinary overlay window sits below the keyguard and cannot wake a
 * sleeping phone. BrightControl is an accessibility service, so it draws above the keyguard and
 * wakes the panel with a wake lock instead — which means the reminder for something at nine now
 * lights a face-down phone without an activity marking the keyguard occluded.
 *
 * ### What does not change
 *
 * The buzz and the shade notification. Both happen before the gate in [ReminderReceiver], and both
 * must keep happening: the notification is the record BrightControl reads and LightGlance's dots
 * count, and if this app went quiet as well as boxless, a phone where BrightControl's own listener
 * grant had lapsed would say nothing at all about a reminder.
 */
object AlertOwner {

    /** The package that may claim the box. Only BrightControl; nothing else is asked. */
    private const val CONTROL = "com.gios.lightcontrol"

    private const val PREFS = "alerts"
    private const val KEY_OWNED = "alerts_owned"

    /**
     * Whether to leave the box to BrightControl.
     *
     * Two tests, and the second is not paranoia. A remembered yes from an app that has since been
     * uninstalled would silence this app's box permanently, with nothing on the phone to explain
     * why — so the claim is only honoured while the claimant is still installed. Removing
     * BrightControl gives this app its box back on the next reminder, with no setting to find.
     */
    fun ownedElsewhere(context: Context): Boolean {
        if (!prefs(context).getBoolean(KEY_OWNED, false)) return false
        return runCatching {
            context.packageManager.getPackageInfo(CONTROL, 0)
            true
        }.getOrDefault(false)
    }

    internal fun remember(context: Context, owned: Boolean) {
        prefs(context).edit().putBoolean(KEY_OWNED, owned).apply()
    }

    /**
     * Its own small file rather than the repository's.
     *
     * A `BroadcastReceiver` has a few seconds and a wakelock that ends with `onReceive`, and the
     * repository's preferences arrive alongside a Room database this has no reason to open. One
     * boolean in a file of its own loads in microseconds and cannot pull anything else in with it.
     */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Hears BrightControl say who is drawing the box.
 *
 * A broadcast rather than this app asking, because asking would be a binder call on the path a
 * reminder fires on — inside a receiver that is already racing its own wakelock. The cost of the
 * other direction is staleness, which BrightControl pays for by sending it often and unprompted: on
 * its every launch, the moment its listener grant lands, and at boot. So a missed broadcast
 * corrects itself rather than sticking.
 *
 * Nothing verifies the sender, and nothing needs to. The worst a forged one can do is stop this app
 * drawing its own box — the buzz and the notification are never gated on it — and a signature
 * permission would prove nothing anyway, since these apps' signing key is public.
 */
class AlertOwnerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        AlertOwner.remember(context, intent.getBooleanExtra(EXTRA_OWNED, false))
    }

    companion object {
        const val ACTION = "com.gios.lightcontrol.action.ALERTS_OWNED"
        const val EXTRA_OWNED = "owned"
    }
}
