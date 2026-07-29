package com.gios.lightnotebook.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.gios.lightnotebook.MainActivity
import com.gios.lightnotebook.notify.Notifier
import com.gios.lightnotebook.ui.theme.LightIcon
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightNotebookTheme
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp

/**
 * The box a reminder puts on screen, and the thing that lights the panel.
 *
 * An activity rather than an overlay window, and for one reason: an overlay sits below the
 * keyguard, so it cannot wake a sleeping phone — which is the case that matters, a
 * reminder for something at nine while the phone is face-down on a desk.
 * `showWhenLocked` + `turnScreenOn` are set in the manifest for a cold start and again
 * here for a re-use through [onNewIntent]. Ported from LightChat's heads-up box.
 *
 * A floating top strip, not a full-screen window: a full-screen one would swallow every
 * touch on the phone for as long as it was up.
 */
class ReminderAlertActivity : ComponentActivity() {

    // Named `heading`, not `title`: `title` collides with Activity's own getTitle/setTitle
    // and Kotlin reads that as an accidental override.
    private var heading by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var epochDay: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setGravity(Gravity.TOP)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        read(intent)
        if (isFinishing) return

        setContent {
            LightNotebookTheme {
                val colors = LightThemeTokens.colors
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .lightClickable { openDay() },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = lightInset(),
                                vertical = 1f.verticalGridUnitsAsDp(),
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.padding(end = lightInset())) {
                            LightText(
                                text = heading,
                                variant = LightTextVariant.Copy,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LightText(subtitle, LightTextVariant.Detail, lighten = true)
                        }
                        LightIcon(
                            icon = LightIcons.Close,
                            size = 1.6f,
                            modifier = Modifier.lightClickable { finish() },
                        )
                    }
                    LightRule()
                }
            }
        }
    }

    /** A second reminder while the box is up: swap the content, restart the timer. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        read(intent)
    }

    private fun read(intent: Intent?) {
        heading = intent?.getStringExtra(EXTRA_TITLE).orEmpty().trim().take(120)
        subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        epochDay = intent?.getLongExtra(Notifier.EXTRA_EPOCH_DAY, 0L) ?: 0L
        if (heading.isBlank()) {
            finish()
            return
        }
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, VISIBLE_MS)
    }

    private fun openDay() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifier.EXTRA_EPOCH_DAY, epochDay),
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(dismiss)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "reminderTitle"
        const val EXTRA_SUBTITLE = "reminderSubtitle"

        /** Long enough to read two lines, short enough not to sit in front of anything. */
        private const val VISIBLE_MS = 6_000L
    }
}
