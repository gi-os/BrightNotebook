package com.gios.lightnotebook.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import kotlinx.coroutines.delay

/** How long the offer stands before it takes silence for an answer. */
private const val SHOWN_MS = 4_000L

/** A crash is worth a longer look; it is also the one offer you cannot make again from nothing. */
private const val SHOWN_CRASH_MS = 8_000L

/** Long enough for the fade to finish before the caller tears the composable down. */
private const val FADE_MS = 350

/**
 * A small offer in the corner, rather than a sheet across the screen.
 *
 * This replaced a modal sheet asking "did you mean to send an error report?", which was the wrong
 * shape for the question. The gesture that raises it is one the phone can misread, so the cost of
 * being wrong is paid *every* time it is wrong — and a sheet that covers what you were reading, on
 * a 3.92" screen, to ask about something you did not ask about, is a bad trade against a report
 * that might not exist.
 *
 * So the offer is small, it sits out of the way, and **silence is an answer**: ignore it for four
 * seconds and it fades. Nothing is lost by ignoring it — the same report is always available from
 * the settings screen, and an unsent crash log is offered again on the next launch. Only the tap
 * costs anything, and only the tap opens the sheet.
 */
@Composable
fun ReportChip(reason: ReportReason, onOpen: () -> Unit, onExpire: () -> Unit) {
    val colors = LightThemeTokens.colors
    var shown by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(if (reason == ReportReason.Crashed) SHOWN_CRASH_MS else SHOWN_MS)
        shown = false
    }
    // Torn down only after the fade has played out, or it would vanish rather than fade —
    // which on this panel reads as a glitch, from the feature whose job is glitches.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(FADE_MS.toLong())
        onExpire()
    }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
    ) {
        Box(
            Modifier
                .background(colors.background)
                .border(1.dp, colors.content)
                .lightClickable(onClick = onOpen)
                .padding(
                    horizontal = 0.8f.gridUnitsAsDp(),
                    vertical = 0.5f.verticalGridUnitsAsDp(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = when (reason) {
                    ReportReason.Crashed -> "IT CRASHED · SEND?"
                    else -> "SEND ERROR?"
                },
                variant = LightTextVariant.Button,
                maxLines = 1,
            )
        }
    }
}
