package com.gios.lightnotebook.ui.theme

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf

/**
 * The Light Phone III design language, ported from `lightphone/light-sdk` (MIT licence,
 * © 2026 The Light Phone — see LICENSE-light-sdk) so that a plain sideloaded APK looks
 * and behaves like a tool built against the SDK.
 *
 * Three ideas carry most of the look:
 *
 *  - **A 27 x 31 grid.** Every size and gap is a fraction of the screen rather than a
 *    fixed dp, which is how LightOS keeps its proportions on a 3.92" panel.
 *  - **A named type scale, scaled by screen height.** The sizes below are the LP3's own
 *    design pixels; [designVerticalPxToSp] converts them against a 600px baseline.
 *  - **Three colours only.** Background, content, secondary content. The panel is
 *    greyscale, so state has to be carried by inversion, brackets or weight.
 */

/* ---------------- grid ---------------- */

object LightGrid {
    const val WIDTH = 27
    const val HEIGHT = 31
}

@Composable
fun Float.gridUnitsAsDp(): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp.toFloat() / LightGrid.WIDTH * this).dp
}

@Composable
fun Float.verticalGridUnitsAsDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return (screenHeightDp.toFloat() / LightGrid.HEIGHT * this).dp
}

private const val FONT_VERTICAL_SCALE_BASELINE_PX = 600f

@Composable
fun Float.designVerticalPxToSp(): TextUnit {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / FONT_VERTICAL_SCALE_BASELINE_PX).sp
}

@Composable
fun Float.designVerticalPxToDp(): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / FONT_VERTICAL_SCALE_BASELINE_PX).dp
}

/* ---------------- colours ---------------- */

@Immutable
data class LightColors(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
    /** Formatting marks that must be present but must not compete with the words. */
    val contentFaint: Color,
    /** Hairlines between rows. Not an SDK token; a notebook is a list of things. */
    val rule: Color,
)

object LightThemeColors {
    val Dark = LightColors(
        background = Color.Black,
        content = Color.White,
        contentSecondary = Color(0xFFBBBBBB),
        contentFaint = Color(0xFF5E5E5E),
        rule = Color(0xFF262626),
    )
}

/* ---------------- typography ---------------- */

@Immutable
data class LightTypography(
    val title: TextStyle,
    val subtitle: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val copy: TextStyle,
    val button: TextStyle,
    val paragraph: TextStyle,
    val paragraphWide: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
    val superfine: TextStyle,
    val micro: TextStyle,
)

/** Mirrors the LP3 table in LightOS's own `style/index.ts`, unscaled. */
private fun buildTypography(fontFamily: FontFamily): LightTypography = LightTypography(
    title = TextStyle(
        fontSize = 115.sp, fontFamily = fontFamily, fontWeight = FontWeight.Light,
        lineHeight = (115 * 1.10).sp,
    ),
    subtitle = TextStyle(
        fontSize = 52.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (52 * 1.20).sp,
    ),
    heading = TextStyle(
        fontSize = 38.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (38 * 1.35).sp,
    ),
    subheading = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (30 * 0.03).sp, lineHeight = (30 * 1.25).sp,
    ),
    copy = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (30 * 1.50).sp,
    ),
    button = TextStyle(
        fontSize = 30.sp, fontFamily = fontFamily, fontWeight = FontWeight.Medium,
        letterSpacing = (30 * 0.15).sp, lineHeight = (30 * 1.10).sp,
    ),
    paragraph = TextStyle(
        fontSize = 24.5.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (24.5 * 1.25).sp,
    ),
    paragraphWide = TextStyle(
        fontSize = 25.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.02).sp, lineHeight = (25 * 1.30).sp,
    ),
    detail = TextStyle(
        fontSize = 20.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (20 * 1.45).sp,
    ),
    fine = TextStyle(
        fontSize = 25.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.03).sp, lineHeight = (25 * 1.15).sp,
    ),
    superfine = TextStyle(
        fontSize = 16.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (16 * 1.20).sp,
    ),
    micro = TextStyle(
        fontSize = 8.sp, fontFamily = fontFamily, fontWeight = FontWeight.Normal,
        lineHeight = (8 * 1.20).sp,
    ),
)

private val FallbackTypography = buildTypography(FontFamily.Default)

@Composable
private fun rememberLightTypography(): LightTypography {
    val fam = remember { akkuratFamilyOrDefault() }
    return remember(fam) { buildTypography(fam) }
}

val LocalLightColors = staticCompositionLocalOf { LightThemeColors.Dark }
val LocalLightTypography = staticCompositionLocalOf { FallbackTypography }

object LightThemeTokens {
    val colors: LightColors
        @Composable get() = LocalLightColors.current

    val typography: LightTypography
        @Composable get() = LocalLightTypography.current
}

/* ---------------- theme ---------------- */

private fun LightColors.toMaterialScheme(): ColorScheme = darkColorScheme(
    background = background,
    surface = background,
    onBackground = content,
    onSurface = content,
    primary = content,
    onPrimary = background,
    secondary = contentSecondary,
    onSecondary = background,
    surfaceVariant = background,
    onSurfaceVariant = contentSecondary,
    outline = rule,
)

/**
 * The LP3 panel is black and white; there is no light scheme here because there is no
 * setting on the phone that would ask for one.
 */
@Composable
fun LightNotebookTheme(content: @Composable () -> Unit) {
    val colors = LightThemeColors.Dark
    CompositionLocalProvider(
        LocalLightColors provides colors,
        LocalLightTypography provides rememberLightTypography(),
    ) {
        // Material is kept underneath purely so M3 sheets and dialogs inherit the palette.
        MaterialTheme(colorScheme = colors.toMaterialScheme(), content = content)
    }
}

/* ---------------- touch ---------------- */

object LightHaptics {
    /** Tuned for the LP3's slow motor, same as the SDK. */
    fun click(context: Context) {
        runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

/**
 * Clickable with no ripple and no press state, buzzing on finger-down the way LightOS
 * does. A ripple would be the single most un-Light thing in the app.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    haptics: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val buzz = enabled && haptics
    // **The buzz belongs on the tap, not on the touch.** It used to fire from `awaitFirstDown`,
    // which cannot tell a tap from the first moment of a scroll — so dragging a finger down a list
    // buzzed once for every row it passed under. Raised from the click itself instead, which also
    // keeps `clickable`'s semantics rather than replacing them with a raw gesture detector.
    clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
    ) {
        if (buzz) LightHaptics.click(context)
        onClick()
    }
}

/**
 * Horizontal swipes, for moving between dates.
 *
 * A drag rather than a fling: the threshold is a fraction of the screen, so a slow deliberate
 * push works as well as a flick, and a vertical scroll underneath is untouched because only
 * horizontal drags are consumed. [onLeft] is a swipe towards the left, which by the usual
 * reading means "forward".
 */
fun Modifier.lightHorizontalSwipe(
    onLeft: () -> Unit,
    onRight: () -> Unit,
): Modifier = composed {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    var travelled by remember { mutableFloatStateOf(0f) }
    pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                when {
                    travelled <= -threshold -> onLeft()
                    travelled >= threshold -> onRight()
                }
                travelled = 0f
            },
            onDragCancel = { travelled = 0f },
        ) { _, delta ->
            travelled += delta
        }
    }
}

private const val SWIPE_THRESHOLD_DP = 48

/** How far a pinch has to close before it counts as backing out of something. */
private const val PINCH_OUT_RATIO = 0.75f

/**
 * The day view's gestures: slide sideways for the next or previous day, pinch out to leave.
 *
 * Written against [PointerEventPass.Initial] and arbitrating by hand, which is the point.
 * A `detectHorizontalDragGestures` on the same node as a scrolling list loses: the list sees
 * the drag first on the main pass and claims it, so sideways swipes did nothing. Here the
 * direction is decided once per gesture — past the slop, whichever axis is winning takes the
 * whole gesture — and a horizontal decision is consumed before the list ever sees it, while a
 * vertical one is left entirely alone so scrolling still feels native.
 */
/**
 * Regions that own horizontal drags, so the day does not steal them.
 *
 * [lightDayGestures] watches on `PointerEventPass.Initial`, and Initial travels **ancestor to
 * descendant** — so the day pane sees a horizontal drag before any child does and consumes it. That
 * is right almost everywhere: it is what lets a swipe move the day whether it starts over text, a
 * photograph or empty space. It is wrong over something that scrolls sideways itself, which simply
 * never received a single event.
 *
 * A child cannot pre-empt an ancestor on Initial, so the ancestor has to be told. Each sideways
 * scroller registers its bounds here with [ownsHorizontalDrag] and the day skips claiming a drag that
 * began inside one.
 */
val LocalHorizontalDragOwners = staticCompositionLocalOf { mutableListOf<Rect>() }

/**
 * Claim horizontal drags starting inside this composable for itself.
 *
 * Bounds in root coordinates, since that is the space pointer positions arrive in. Removed on
 * disposal, or a recycled row in a lazy list would leave a dead rectangle behind that quietly
 * disabled day-swiping over whatever took its place.
 */
fun Modifier.ownsHorizontalDrag(): Modifier = composed {
    val owners = LocalHorizontalDragOwners.current
    var mine by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(Unit) {
        onDispose { mine?.let(owners::remove) }
    }
    onGloballyPositioned { coordinates ->
        mine?.let(owners::remove)
        val rect = coordinates.boundsInRoot()
        mine = rect
        owners.add(rect)
    }
}

fun Modifier.lightDayGestures(
    /** Every horizontal pixel of the drag, so the caller can move the planner with it. */
    onSlide: (Float) -> Unit,
    /** Fingers up after a horizontal drag: settle on whichever day is now on screen. */
    onSlideEnd: () -> Unit,
    onPinchOut: () -> Unit,
): Modifier = composed {
    val density = LocalDensity.current
    val slop = with(density) { DAY_SLOP_DP.dp.toPx() }
    val owners = LocalHorizontalDragOwners.current

    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Started over something that scrolls sideways itself. Vertical drags and pinches are
            // still ours — only the horizontal claim is given up, which is the one that collides.
            val theirs = owners.any { it.contains(down.position) }
            var dx = 0f
            var dy = 0f
            var zoom = 1f
            var axis = Axis.Undecided
            var fired = false
            var slid = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break

                val pan = event.calculatePan()
                dx += pan.x
                dy += pan.y

                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    if (!fired && zoom < PINCH_OUT_RATIO) {
                        fired = true
                        onPinchOut()
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (axis == Axis.Undecided) {
                    axis = when {
                        abs(dx) > slop && abs(dx) > abs(dy) * DAY_AXIS_BIAS -> Axis.Horizontal
                        abs(dy) > slop -> Axis.Vertical
                        else -> Axis.Undecided
                    }
                }

                if (axis == Axis.Horizontal && theirs) {
                    // Left entirely alone: not consumed, so the child receives it on Main.
                    continue
                }

                if (axis == Axis.Horizontal) {
                    event.changes.forEach { it.consume() }
                    onSlide(pan.x)
                    slid = true
                }
            }
            if (slid) onSlideEnd()
        }
    }
}

private enum class Axis { Undecided, Horizontal, Vertical }

private const val DAY_SLOP_DP = 12
private const val DAY_AXIS_BIAS = 1.3f

/**
 * Same, with a long press. Long press is where a note's own actions live — pin, move,
 * delete — so that the list itself stays a list of notes and nothing else.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.lightCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    // Same as above: the haptic is raised from the gesture that actually happened, so scrolling
    // past a day full of photographs is silent.
    combinedClickable(
        interactionSource = null,
        indication = null,
        onLongClick = {
            LightHaptics.click(context)
            onLongClick()
        },
    ) {
        LightHaptics.click(context)
        onClick()
    }
}


