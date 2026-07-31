package com.gios.lightnotebook.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightCombinedClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.DayTimeline
import com.gios.lightnotebook.util.OnThisDay
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.NoteDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.gios.lightnotebook.util.Steps
import androidx.compose.foundation.layout.fillMaxHeight
import com.gios.lightnotebook.util.DayLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import com.gios.lightnotebook.util.PhotoTiles
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.FlowRow
import com.gios.lightnotebook.util.AgendaRow

/**
 * A moment of a day that has happened: one photograph, or a burst of them.
 *
 * Photographs sit **in** the day rather than in a strip above it, because a day that has gone
 * is a diary page and the pictures are most of what happened on it. What decides the shape is
 * how many there are, and that matters more than it sounds: a single picture gets the full
 * width, the way a photograph in a diary does, while a burst becomes a row of thumbnails.
 *
 * The burst case is not a nicety. The strip this replaced was bounded by construction — always
 * one row tall, whatever the day held — and full-width pictures gave that up: eleven shots of
 * the same thing would be eleven screens of scrolling, with everything written that day pushed
 * out of reach underneath. Clustering ([DayTimeline.cluster]) puts the bound back.
 */
@Composable
fun TimelinePhotos(
    item: DayTimeline.Item.Photos,
    photosById: Map<Long, DevicePhoto>,
    onOpen: (DevicePhoto) -> Unit,
    onAttach: (DevicePhoto) -> Unit,
) {
    val resolved = remember(item, photosById) { item.photos.mapNotNull { photosById[it.id] } }
    if (resolved.isEmpty()) return

    // Asked for at the size it is actually drawn, so a full-width picture gets a bigger thumbnail
    // than a tile and neither is scaled up on a panel this small.
    val density = LocalDensity.current
    // Edge to edge: a photograph of a moment in your day should be the moment, not a card of it.
    val fullWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.6f.verticalGridUnitsAsDp(),
            ),
    ) {
        // The time above the picture, not beside it. A left-hand gutter wide enough for
        // "14:30" costs a fifth of a 3.92" panel on every row of the day, and the entries
        // below already carry their times on the right.
        Row(
            Modifier.fillMaxWidth(PAGE_PHOTO_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = clockOf(item.minutes),
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
            if (!item.single) {
                LightText(
                    text = "–" + clockOf(item.untilMinutes) +
                        "  ·  ${resolved.size}",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
            }
        }
        Spacer(Modifier.padding(top = 0.3f.verticalGridUnitsAsDp()))

        if (item.single) {
            val photo = resolved.single()
            PhotoFrame(
                photo = photo,
                requestPx = fullWidthPx,
                // Not the full width. A photograph pinned to a page has paper around it, and edge
                // to edge reads as a website hero rather than as something someone stuck in a book.
                // 4:3 is held explicitly rather than left to the bitmap: a portrait shot would
                // otherwise be taller than the screen and a day of them could not be scrolled past.
                modifier = Modifier
                    .fillMaxWidth(PAGE_PHOTO_WIDTH)
                    .aspectRatio(1f / PhotoTiles.FRAME_ASPECT)
                    .tiltedLike(photo.id, 0),
                onClick = { onOpen(photo) },
                onLongClick = { onAttach(photo) },
            )
        } else {
            // **Tiled like a page of a photo book, not laid out like a contact sheet.** Rows of
            // different counts mean pictures of different sizes, which is most of what makes a
            // group of photographs read as a page rather than as a filmstrip. The arrangement is
            // fixed for a given count ([PhotoTiles]) so adding a photograph does not reshuffle the
            // page while you are looking at it.
            val ranges = remember(resolved.size) { PhotoTiles.rowRanges(resolved.size) }
            // Centred as a block, and each row centred inside it, so a tail row of two under a row
            // of three sits under the middle of the page rather than shoved against its left edge.
            BoxWithConstraints(
                Modifier.fillMaxWidth(PAGE_PHOTO_WIDTH),
                contentAlignment = Alignment.Center,
            ) {
                val blockWidth = maxWidth
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ranges.forEach { range ->
                        val inRow = range.last - range.first + 1
                        val rowHeight = blockWidth * PhotoTiles.rowHeightFraction(inRow)
                        val cellPx = with(density) { (blockWidth / inRow).roundToPx() }
                        Row(
                            Modifier
                                // The row is only as wide as the pictures on it, so a short last
                                // row centres instead of stretching to fill.
                                .width(blockWidth * inRow / ranges.maxOf { it.last - it.first + 1 })
                                .height(rowHeight)
                                .padding(vertical = TILE_GAP_UNITS.verticalGridUnitsAsDp()),
                        ) {
                            range.forEach { index ->
                                val photo = resolved[index]
                                PhotoFrame(
                                    photo = photo,
                                    requestPx = cellPx,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = TILE_GAP_UNITS.gridUnitsAsDp())
                                        // Index across the whole block, not within the row, so the
                                        // alternation carries down the page instead of restarting
                                        // and putting two identical leans either side of a row end.
                                        .tiltedLike(photo.id, index),
                                    onClick = { onOpen(photo) },
                                    onLongClick = { onAttach(photo) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * How wide a photograph sits on the page.
 *
 * Seventy per cent, centred. A photograph in a book has paper around it; edge to edge reads as a
 * website hero rather than as something someone stuck down.
 */
private const val PAGE_PHOTO_WIDTH = 0.7f

/** Air between tiles, so a group reads as separate prints rather than as one sheet. */
private const val TILE_GAP_UNITS = 0.22f

/** The most a photograph leans, in degrees. Past this it stops being charm and becomes a bug. */
private const val MAX_TILT_DEGREES = 1.8f

/**
 * A slight lean, as if it were stuck down by hand.
 *
 * **The sign alternates with position, and only the size comes from the picture.** An angle derived
 * purely from the photograph's id looked wrong far more often than it looked charming: two
 * neighbours leaning the same way read as a crooked page rather than a hand-placed one, and a run
 * of three in the same direction reads as a bug. Alternating guarantees every pair opposes, which
 * is the arrangement that looks deliberate — the eye reads two mirrored leans as balance and two
 * parallel ones as a mistake.
 *
 * The magnitude still comes from the id, so the page is varied rather than a zigzag of identical
 * angles, and it is stable: the same photograph leans the same amount every time the day is opened,
 * and gaining a picture above it does not change how much it leans — only, deliberately, which way.
 *
 * Kept under two degrees. Past that it stops being charm and becomes a layout bug, and across a
 * grid of tiles the misalignment compounds at the edges.
 */
private fun Modifier.tiltedLike(id: Long, index: Int): Modifier {
    // 0.55..1.0 of the maximum, so no photograph sits perfectly straight among leaning ones and
    // none of them reaches the full angle unless its id says so.
    val magnitude = MIN_TILT_FRACTION +
        ((id % 5L).toInt() / 4f) * (1f - MIN_TILT_FRACTION)
    val direction = if (index % 2 == 0) 1f else -1f
    return graphicsLayer { rotationZ = direction * magnitude * MAX_TILT_DEGREES }
}

/** No photograph sits perfectly straight among leaning ones; it reads as the odd one out. */
private const val MIN_TILT_FRACTION = 0.55f

/**
 * One photograph, loaded off the main thread and cached in bytes by [PhotoLibrary].
 *
 * `remember(photo.id)` and not `remember`: a lazy list recycles composables, so without the key
 * a frame holds the previous photograph's bitmap for a frame every time the day changes, which
 * reads as the wrong picture on the wrong day.
 *
 * The thumbnail is requested at the frame's real pixel size, so a full-width picture asks for a
 * bigger one than a burst thumbnail does and neither is scaled up.
 */
@Composable
private fun PhotoFrame(
    photo: DevicePhoto,
    /** The pixel size to ask MediaStore for. Known by the caller, which knows how big it drew. */
    requestPx: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LightThemeTokens.colors
    var bitmap by remember(photo.id, requestPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo.id, requestPx) {
        bitmap = withContext(Dispatchers.IO) { PhotoLibrary.thumbnail(context, photo, requestPx) }
    }

    Box(
        modifier
            // An outline while it loads, never a filled block: on a 1-bit panel a grey
            // rectangle reads as a broken image, an empty frame as one on its way.
            .border(1.dp, colors.rule)
            .lightCombinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "A photograph from this day",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The line between what has happened and what has not.
 *
 * Drawn only on today, and only with something on both sides of it — a rule above everything or
 * below everything is a rule with nothing on one side, which reads as a mistake rather than as
 * the time. See [DayTimeline.nowLineIndex].
 */
@Composable
fun NowLine() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.5f.verticalGridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = "NOW", variant = LightTextVariant.Superfine)
        Box(Modifier.padding(start = 0.6f.gridUnitsAsDp()).weight(1f)) { LightRule() }
    }
}

/**
 * The offer to read the library, in place of the day's photographs when the permission is
 * missing.
 *
 * Asked for here rather than at first launch, matching how this app already treats
 * notifications: a day is the only thing that wants photographs, so this is the screen where
 * the question makes sense and can be answered with the reason visible on it.
 */
@Composable
fun PhotoPermissionRow(onAsk: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .lightClickable(onClick = onAsk)
            .padding(
                horizontal = lightInset(),
                vertical = 0.7f.verticalGridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "SHOW THE DAY'S PHOTOS",
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
    }
}

/**
 * The shape of the day, in one line under its title.
 *
 * Bookends and daylight together, because separately neither earns a row of its own on a 3.92"
 * panel and together they are the two facts that frame everything below: when you were up, and
 * how much light there was to be up in.
 *
 * Nothing is shown for a day with only one thing on it — "6:40 to 6:40" is not a day, it is the
 * row already on screen.
 */
/**
 * The time between two moments, drawn as the room it took.
 *
 * If you photographed something at eight and the next thing happened at two, they did not happen
 * next to each other, and stacking them as adjacent rows tells a lie about the day. So the emptiness
 * is drawn — compressed hard, because six hours at true scale is six screens of nothing, but never
 * so hard that a long wait stops looking longer than a short one. See [DayLayout].
 *
 * Past an hour it also says how long: at this compression an hour and five hours look more alike
 * than they are, and the number is what stops the squashing from lying.
 */
@Composable
fun TimeGap(units: Float, gapMinutes: Int, modifier: Modifier = Modifier) {
    if (units <= 0f) return
    val colors = LightThemeTokens.colors
    val label = DayLayout.labelFor(gapMinutes)

    Box(
        modifier
            .fillMaxWidth()
            .height(units.verticalGridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        // A hairline down the middle of the emptiness, so a gap reads as time passing rather than
        // as a layout mistake. Faint: it is the absence of events, and it should not compete with
        // them.
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(colors.rule),
        )
        if (label != null) {
            // Set on the background so the rule appears to run behind it rather than through it.
            LightText(
                text = label,
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier
                    .background(colors.background)
                    .padding(vertical = 0.15f.verticalGridUnitsAsDp()),
            )
        }
    }
}

/**
 * The things that are true of the whole day, next to its date.
 *
 * A birthday, a holiday, a trip — these have no time, so a timeline has nowhere honest to put them.
 * Placed first among the moments they read as the first thing that happened, which is exactly what
 * they are not. They belong with the date instead: both describe the day rather than a point in it.
 *
 * Wrapped rather than truncated, because three all-day things on one day is normal and a "+2" for
 * something that is the day's whole character is the wrong thing to hide.
 */
@Composable
fun AllDayRow(
    entries: List<DayTimeline.Item.Entry>,
    onOpen: (AgendaRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    FlowRow(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.35f.verticalGridUnitsAsDp(),
            ),
    ) {
        entries.forEach { entry ->
            LightText(
                text = entry.row.title,
                variant = LightTextVariant.Superfine,
                modifier = Modifier
                    .padding(end = 0.7f.gridUnitsAsDp())
                    .lightClickable { onOpen(entry.row) },
            )
        }
    }
}

/**
 * "Started the day at 07:12" — the first thing that happened, at the top of the day.
 *
 * Written out rather than shown as a time in a corner, because it is the opening line of a page
 * about a day rather than a field. Its counterpart sits at the very bottom, and between them the
 * day is laid out in the order it happened.
 *
 * Times are read back to the clock: eight hours into a day that began at four is noon, and a late
 * night reads as "01:40" rather than as the twenty-one hours it is measured in.
 */
@Composable
fun DayOpening(minutes: Int?, modifier: Modifier = Modifier) {
    if (minutes == null) return
    DayEdgeLine("STARTED THE DAY AT " + clockOf(minutes), modifier)
}

/** "Ended the day at 23:40", at the foot of the page. Absent while the day is still going. */
@Composable
fun DayClosing(minutes: Int?, unfinished: Boolean, modifier: Modifier = Modifier) {
    if (minutes == null || unfinished) return
    DayEdgeLine("ENDED THE DAY AT " + clockOf(minutes), modifier)
}

@Composable
private fun DayEdgeLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.6f.verticalGridUnitsAsDp()),
    ) {
        LightText(text = text, variant = LightTextVariant.Superfine, lighten = true)
    }
}

private fun clockOf(minutesIntoDay: Int): String =
    NoteDates.clock(JournalDay.clockMinutes(minutesIntoDay)).orEmpty()

@Composable
fun DayShape(
    stats: NotebookViewModel.DayStats,
    modifier: Modifier = Modifier,
) {
    // Daylight and the day's bookends used to live here and now live on the planner, where a
    // vertical band down a cell says more than a line of text: pan a year and you see the winter.
    // What is left here is what the phone noticed about *you* rather than about the day.
    val parts = buildList {
        stats.steps?.takeIf { it > 0 }?.let { add("${Steps.format(it)} STEPS") }
        if (stats.usageGranted) {
            add("${stats.use.unlocks} PICKED UP")
            val minutes = stats.use.screenOnMinutes
            if (minutes > 0) {
                add(if (minutes >= 60) "${minutes / 60}H ${minutes % 60}M ON" else "${minutes}M ON")
            }
        }
    }
    if (parts.isEmpty()) return

    Row(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.4f.verticalGridUnitsAsDp(),
            ),
    ) {
        LightText(
            text = parts.joinToString("  ·  "),
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
    }
}

/**
 * The day's steps, hour by hour.
 *
 * **A total cannot answer the interesting question.** "Eight thousand steps" says nothing about a
 * day; a spike between two and four says you walked somewhere. So the graph is the point and the
 * number is the caption, rather than the other way round.
 *
 * Drawn as bars in a `Canvas` — twenty-four composables that recompose together would be absurd for
 * something this small, and the bars need to share one scale anyway.
 */
@Composable
fun StepGraph(hours: List<Int>, total: Int?, modifier: Modifier = Modifier) {
    if (hours.isEmpty() || hours.all { it == 0 }) return
    val colors = LightThemeTokens.colors
    val peak = hours.max().coerceAtLeast(1)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.6f.verticalGridUnitsAsDp()),
    ) {
        LightText(
            text = if (total != null) "${Steps.format(total)} STEPS" else "STEPS",
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4f.verticalGridUnitsAsDp())
                .padding(top = 0.3f.verticalGridUnitsAsDp()),
        ) {
            val slot = size.width / hours.size
            // A gap of a fifth of a slot, so the bars read as separate hours rather than as one
            // filled shape — at this width a solid block says nothing about when you walked.
            val barWidth = (slot * 0.8f).coerceAtLeast(1f)
            hours.forEachIndexed { index, steps ->
                if (steps <= 0) return@forEachIndexed
                // Square root, not linear: one big walk would otherwise flatten every other hour of
                // the day to nothing, and the shape of the day is the thing being drawn.
                val height = size.height * kotlin.math.sqrt(steps.toFloat() / peak)
                drawRect(
                    color = colors.content,
                    topLeft = Offset(index * slot, size.height - height),
                    size = Size(barWidth, height),
                )
            }
        }
    }
}

/**
 * What the phone cannot tell you, and the one command that fixes it.
 *
 * LightOS has no Settings screens, so an appop cannot be granted by sending the user anywhere — the
 * only route is adb, and an app that silently shows nothing is indistinguishable from a quiet day.
 */
@Composable
fun StatsGrantRow(onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .lightClickable(onClick = onCopy)
            .padding(horizontal = lightInset(), vertical = 0.7f.verticalGridUnitsAsDp()),
    ) {
        LightText(
            text = "SCREEN TIME NEEDS ONE ADB COMMAND",
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
    }
}

/**
 * The same date, in the years before it — a small row at the very bottom of the day.
 *
 * At the bottom and deliberately quiet: it is the least urgent thing on the screen and the most
 * rewarding to come across, which is the wrong order to put at the top. One photograph per year,
 * the year under it, and only years that have one — a row of empty frames reads as a broken
 * feature rather than as a year you took no pictures in.
 */
@Composable
fun OnThisDayRow(
    past: List<Pair<OnThisDay.PastDay, DevicePhoto>>,
    onOpen: (DevicePhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (past.isEmpty()) return
    val edge = PAST_THUMB_UNITS.verticalGridUnitsAsDp()
    val edgePx = with(LocalDensity.current) { edge.roundToPx() }

    Column(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.8f.verticalGridUnitsAsDp(),
            ),
    ) {
        LightText(text = "ON THIS DAY", variant = LightTextVariant.Superfine, lighten = true)
        Spacer(Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()))
        LazyRow {
            items(past, key = { it.first.year }) { (day, photo) ->
                Column(Modifier.padding(end = 0.5f.gridUnitsAsDp())) {
                    PhotoFrame(
                        photo = photo,
                        requestPx = edgePx,
                        modifier = Modifier.size(edge),
                        onClick = { onOpen(photo) },
                        onLongClick = { onOpen(photo) },
                    )
                    LightText(
                        text = day.year.toString(),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.2f.verticalGridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

private const val PAST_THUMB_UNITS = 4.2f
