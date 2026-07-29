package com.gios.lightnotebook.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.VibratorManager
import com.gios.lightnotebook.MainActivity
import com.gios.lightnotebook.R

/**
 * The record half of a reminder: a standard Android notification, plus the buzz.
 *
 * Ported from `gi-os/LightChat`. LightOS posts and lists ordinary notifications, so an
 * importance-HIGH channel is all that is needed for one to appear — and because it is a
 * real notification it also drives LightGlance's dots and stays in the list until read.
 *
 * Vibration is disabled on the channel and done by hand in [buzz], so there is exactly one
 * buzz per reminder whether or not the alert window could be shown, and one place to tune
 * what it feels like. Channel settings are immutable after creation, so changing that
 * later needs a new channel id.
 */
object Notifier {

    private const val CHANNEL_REMINDERS = "reminders"
    private const val ID_BASE = 500

    /** Extra on the tap intent: the day to open. */
    const val EXTRA_EPOCH_DAY = "epochDay"

    fun notificationId(entryId: String): Int =
        ID_BASE + Math.floorMod(entryId.hashCode(), 1_000_000)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_REMINDERS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Calendar reminders"
                enableVibration(false)
            },
        )
    }

    fun post(context: Context, entryId: String, title: String, text: String, epochDay: Long) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = notificationId(entryId)
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notebook)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openDay(context, epochDay, id))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(id, notification)
    }

    fun cancel(context: Context, entryId: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationId(entryId))
    }

    /**
     * A double tick, the same shape LightChat uses. Short enough to read as one event.
     */
    fun buzz(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 30, 80, 30),
                    intArrayOf(0, 180, 0, 180),
                    -1,
                ),
            )
        }
    }

    /** Tapping the reminder opens that day. The request code is the per-entry id, so two
     *  reminders never share a PendingIntent and overwrite each other's extras. */
    private fun openDay(context: Context, epochDay: Long, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EPOCH_DAY, epochDay),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
