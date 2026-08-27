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
import androidx.compose.ui.text.style.TextOverflow
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
import com.gios.lightnotebook.util.AppUse
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.gios.lightnotebook.util.AgendaRow
import androidx.compose.foundation.layout.Arrangement
import com.gios.lightnotebook.data.DayWeather
import com.gios.lightnotebook.util.WeatherCodes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.offset
import com.gios.lightnotebook.ui.theme.LightIcon
import com.gios.lightnotebook.ui.theme.ownsHorizontalDrag
import com.gios.lightnotebook.ui.theme.LightIcons

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
            // Generous, and the point of it: a page of a photo book is mostly margin. No horizontal
            // padding — the seventy per cent width below *is* the margin, and adding both makes the
            // pictures narrower on one axis than the layout says.
            .padding(vertical = 1.6f.verticalGridUnitsAsDp()),
        // **Load-bearing.** Every child below sets its own width as a fraction, so without this
        // they align to the start and the whole page sits against the left edge. This was set once
        // and then lost to a later edit, which is exactly what the left lean was.
        horizontalAlignment = Alignment.CenterHorizontally,
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
                // **The photograph's own shape.** Everything was 4:3 landscape, so a portrait shot —
                // which on a phone is most of them — lost the top and bottom of itself to a centre
                // crop. Capped, because an unbounded portrait is taller than the panel and a day of
                // them cannot be scrolled past.
                modifier = Modifier
                    .fillMaxWidth(PAGE_PHOTO_WIDTH)
                    .aspectRatio((photo.aspect ?: LANDSCAPE).coerceAtLeast(TALLEST))
                    .tiltedLike(photo.id, 0),
                onClick = { onOpen(photo) },
                onLongClick = { onAttach(photo) },
            )
        } else {
            // **A highlight, then a pile you scroll through.**
            //
            // If you starred one of the day's photographs, that is the picture of the day and it gets
            // the large frame with a star on it — the rest sit below as a pile. Nothing starred and
            // the pile is all there is, which is the common case and is why the highlight is
            // conditional rather than "the first one, always".
            val highlight = remember(resolved) { resolved.firstOrNull { it.starred } }
            if (highlight != null) {
                // **The star belongs on the picture, and the picture fills its frame.**
                //
                // Both of those were wrong. The star was aligned to an outer box rather than to the
                // frame, so on a tall photograph it sat above the image; and the frame is clamped by
                // `TALLEST`, which for a very tall photograph is *less* tall than the picture — so
                // `Fit` letterboxed it and left a border inside the outline. One box now carries the
                // shape, the lean, the photograph and the star together, so the star cannot drift off
                // the print and the print cannot float inside its own frame.
                Box(
                    Modifier
                        .fillMaxWidth(PAGE_PHOTO_WIDTH)
                        .aspectRatio((highlight.aspect ?: LANDSCAPE).coerceAtLeast(TALLEST))
                        .tiltedLike(highlight.id, 0),
                ) {
                    PhotoFrame(
                        photo = highlight,
                        requestPx = fullWidthPx,
                        modifier = Modifier.matchParentSize(),
                        onClick = { onOpen(highlight) },
                        onLongClick = { onAttach(highlight) },
                    )
                    LightIcon(
                        icon = LightIcons.Star,
                        size = 1.6f,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(0.4f.gridUnitsAsDp()),
                    )
                }
                Spacer(Modifier.padding(top = 0.6f.verticalGridUnitsAsDp()))
            }

            // Rows of neat tiles read as a contact sheet, and a fixed grid can only ever show as many
            // photographs as it has cells. One overlapping row that scrolls sideways solves both, and
            // is one row tall however many there are.
            val rest = remember(resolved, highlight) {
                if (highlight == null) resolved else resolved.filter { it.id != highlight.id }
            }
            if (rest.isNotEmpty()) {
                val edge = PILE_PRINT_UNITS.verticalGridUnitsAsDp()
                // The longest side a print can be, since a landscape one is wider than it is tall and
                // asking for its height would fetch a thumbnail too small and scale it up.
                val edgePx = with(density) { (edge * LANDSCAPE * PILE_LARGEST).roundToPx() }
                // **A fixed height, and this is the bug it fixes.** Prints vary in size, so a row
                // that sizes itself to its children was as tall as whichever ones happened to be
                // composed — and scrolling a taller print into view grew the row and shoved the
                // whole pile up or down. That was the popping. Tall enough for the largest print
                // plus the stagger it can be pushed by, and now nothing about it depends on where
                // the scroll happens to be.
                val rowHeight = edge * PILE_LARGEST + edge * PILE_STAGGER * 2
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        // **The drag is ours.** The day pane watches for horizontal swipes on the
                        // Initial pass, which reaches an ancestor before a descendant, so without
                        // this the carousel never received a single pointer event and simply would
                        // not scroll. See `ownsHorizontalDrag`.
                        // The fixed height above already carries room for the stagger, which
                        // `offset` reserves none of on its own — without that room a nudged print
                        // drew over the text beneath the pile.
                        .ownsHorizontalDrag(),
                    // Negative spacing: each print laps over the one before it. This is the whole
                    // look — with a positive gap it is a filmstrip again.
                    horizontalArrangement = Arrangement.spacedBy(-edge * PILE_LAP),
                    contentPadding = PaddingValues(horizontal = lightInset()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(rest, key = { _, photo -> photo.id }) { index, photo ->
                        PhotoFrame(
                            photo = photo,
                            requestPx = edgePx,
                            modifier = Modifier
                                // One height, each print its own width. Square prints cropped every
                                // portrait photograph to its middle, which on a phone is most of them.
                                // A common height with varying widths is also what a real pile looks
                                // like: prints are different shapes and lie along one edge.
                                .height(edge * sizeFor(photo.id))
                                .aspectRatio(photo.aspect ?: LANDSCAPE)
                                // Prints dropped on a page do not share a baseline, and a row that
                                // does reads as a strip however much each one leans.
                                .offset(y = edge * staggerFor(index, photo.id))
                                // Bolder in a pile than alone. A single photograph on a page wants a
                                // hint of a lean; overlapping prints need enough angle that the
                                // overlap reads as stacking rather than as a misaligned grid.
                                .tiltedLike(photo.id, index, boldness = PILE_TILT_BOLDNESS),
                            onClick = { onOpen(photo) },
                            onLongClick = { onAttach(photo) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tallest a photograph is allowed to be, as width over height.
 *
 * 0.72 is a little narrower than a phone's own 3:4 portrait, which is deliberate: it keeps nearly all
 * of a portrait shot while stopping a single picture from filling the whole panel and turning the day
 * into a slideshow you have to scroll through.
 */
/**
 * How wide a photograph sits on the page.
 *
 * Seventy per cent, centred. A photograph in a book has paper around it; edge to edge reads as a
 * website hero rather than as something someone stuck down.
 */
private const val PAGE_PHOTO_WIDTH = 0.7f

/** 4:3, the shape assumed for a photograph whose own dimensions MediaStore did not report. */
private const val LANDSCAPE = 4f / 3f

private const val TALLEST = 0.72f

/**
 * How big a print in the pile is.
 *
 * Larger than the old filmstrip thumbnails, because these overlap: a print with a fifth of itself
 * behind its neighbour needs the extra size to still be a picture of something.
 */
private const val PILE_PRINT_UNITS = 7.2f

/** How far each print laps over the one before it, as a fraction of its own size. */
private const val PILE_LAP = 0.18f

/**
 * Prints in a pile lean this much further than a photograph standing on its own.
 *
 * Raised from 2.2: with each print keeping its own shape and sitting at its own height, a stronger
 * angle reads as a handful of photographs dropped on a page. A single photograph keeps the gentle
 * version, because one leaning picture with nothing to lean against just looks crooked.
 */
private const val PILE_TILT_BOLDNESS = 3.4f

/**
 * The most a print sits above or below the line, as a fraction of its own height.
 *
 * A fifth. At seven per cent the row still read as a strip.
 */
private const val PILE_STAGGER = 0.2f

/** How much bigger or smaller one print is than another. Slight — it is a pile, not a collage. */
private const val PILE_SIZE_VARIATION = 0.06f

/**
 * The largest a print grows, so a thumbnail is requested big enough for the biggest of them, and so
 * the row can be given a height that does not depend on which prints are composed.
 */
private const val PILE_LARGEST = 1f + PILE_SIZE_VARIATION

/**
 * How far up or down one print sits.
 *
 * **The direction alternates with position and only the amount comes from the photograph** — the same
 * split as the lean, and for the same reason it was needed there. Taking both from the id looked
 * batched, because MediaStore ids increment by one and any division buckets a run of neighbours
 * together: seven in a row landed at the same height, which read as three up then three down rather
 * than as a scatter. Alternating guarantees every print opposes the one beside it.
 *
 * The amount still comes from the id, so the heights are uneven rather than a zigzag of identical
 * offsets, and a given photograph sits at the same height every time the day is opened. `% 7` with no
 * division, so consecutive ids give different amounts.
 */
private fun staggerFor(index: Int, id: Long): Float {
    val amount = MIN_STAGGER_FRACTION +
        ((id % 7L).toInt() / 6f) * (1f - MIN_STAGGER_FRACTION)
    val direction = if (index % 2 == 0) -1f else 1f
    return direction * amount * PILE_STAGGER
}

/** No print sits exactly on the line; one that did would read as the odd one out. */
private const val MIN_STAGGER_FRACTION = 0.35f

/**
 * How big one print is relative to the rest.
 *
 * **Every print differs, and by less than before.** `(id / 3) % 5` gave a run of three neighbours the
 * same size and a range of ±12%, which was both rare enough to look accidental and wide enough that
 * the extremes read as mistakes rather than as variety. `% 5` with no division changes on every
 * consecutive id, and ±6% is enough to see without any print looking wrong.
 *
 * From the id, so the same photograph is the same size every time the day is opened. A random one
 * would resize the pile as you scrolled past it.
 */
private fun sizeFor(id: Long): Float =
    1f + ((((id % 5L).toInt()) - 2) / 2f) * PILE_SIZE_VARIATION

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
private fun Modifier.tiltedLike(id: Long, index: Int, boldness: Float = 1f): Modifier {
    // 0.55..1.0 of the maximum, so no photograph sits perfectly straight among leaning ones and
    // none of them reaches the full angle unless its id says so.
    val magnitude = MIN_TILT_FRACTION +
        ((id % 5L).toInt() / 4f) * (1f - MIN_TILT_FRACTION)
    val direction = if (index % 2 == 0) 1f else -1f
    return graphicsLayer { rotationZ = direction * magnitude * MAX_TILT_DEGREES * boldness }
}

/** No photograph sits perfectly straight among leaning ones; it reads as the odd one out. */
private const val MIN_TILT_FRACTION = 0.55f

/**
 * The most a photograph leans on its own, in degrees.
 *
 * Small: anything the eye reads as *crooked* rather than as *hand-placed* looks like a layout bug.
 * Prints in a pile multiply this by [PILE_TILT_BOLDNESS], where the overlap gives the angle something
 * to be about.
 */
private const val MAX_TILT_DEGREES = 1.8f

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
                // **Crop, and it costs nothing.** Where the frame is the photograph's own shape — a
                // print in the pile, a picture on this day — Crop and Fit are the same operation and
                // nothing is lost. Where the frame has been clamped for being absurdly tall, Crop
                // fills it and Fit leaves a white border inside the outline, which reads as a broken
                // image. Only the viewer wants Fit, because there the whole picture is the point.
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
fun TimeGap(
    units: Float,
    gapMinutes: Int,
    /** Where the gap starts, in minutes into the journal day, so it knows which hours it crosses. */
    fromMinutes: Int,
    modifier: Modifier = Modifier,
) {
    if (units <= 0f) return
    val colors = LightThemeTokens.colors

    // **The hours you passed through, down the left.** Without them a long stretch of nothing says
    // only "later", and the compression means you cannot judge it by eye. These are *not* evenly
    // spaced in real time — the page is not linear and deliberately so — they are the boundaries
    // this gap actually crossed, spread across the room the gap was given.
    val hours = remember(fromMinutes, gapMinutes) { DayLayout.hoursCrossed(fromMinutes, gapMinutes) }

    Box(
        modifier
            .fillMaxWidth()
            .height(units.verticalGridUnitsAsDp()),
    ) {
        // A hairline down the emptiness, so a gap reads as time passing rather than as a layout
        // mistake. Faint: it is the absence of events and should not compete with them.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(1.dp)
                .background(colors.rule),
        )

        if (hours.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .padding(start = lightInset()),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                hours.forEach { hour ->
                    LightText(
                        text = DayLayout.hourLabel(hour),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                    )
                }
            }
        }

        DayLayout.labelFor(gapMinutes)?.let { label ->
            LightText(
                text = label,
                variant = LightTextVariant.Superfine,
                lighten = true,
                // Set on the background so the rule appears to run behind it rather than through.
                modifier = Modifier.align(Alignment.Center).background(colors.background),
            )
        }
    }
}

/**
 * How often the phone was picked up, as a mention in the margin.
 *
 * It was a full row with a glyph and a time, which put "Picked up 14 times" at the same weight as a
 * doctor's appointment — and a day whose loudest line is how often you looked at your phone is a
 * day this app has misread. It is the same shape as the music span now: a short rule and one quiet
 * line, in the margin, because that is what it is. Background, not an event.
 */
@Composable
fun PickupsMention(item: DayTimeline.Item.Pickups, modifier: Modifier = Modifier) {
    val colors = LightThemeTokens.colors
    val until = if (item.untilMinutes > item.minutes) {
        " until " + NoteDates.clock(JournalDay.clockMinutes(item.untilMinutes))
    } else {
        ""
    }
    val phrase = (
        if (item.times == 1) "Picked the phone up" else "Picked the phone up ${item.times} times"
        ) + until

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.4f.verticalGridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(1.6f.verticalGridUnitsAsDp())
                .background(colors.rule),
        )
        LightText(
            text = phrase,
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
        )
    }
}

/**
 * Who you texted, as a mention in the margin.
 *
 * It was a full row with a glyph, a time and a count, which is the shape this app uses for an
 * appointment — so "Talked to Alex" sat on the day at the same weight as a doctor's appointment,
 * and a day with four threads on it had four of them. Texting somebody is not something you have
 * to be somewhere for. It is the background of a day, the same as picking the phone up and the
 * same as having music on, and it gets the same shape those do: a short rule and one quiet line.
 *
 * Everything the row said survives the demotion — who, how many, whether they answered, when it
 * started — because none of that was the problem. Only the weight was.
 */
@Composable
fun TalkedMention(item: DayTimeline.Item.Talked, modifier: Modifier = Modifier) {
    val colors = LightThemeTokens.colors
    val who = if (item.isGroup) "Talked in ${item.name}" else "Talked to ${item.name}"
    // Whether they answered, because talking *at* somebody and talking *with* them are different
    // days. Said only when they did not: "no reply" is the fact, a reply is the ordinary case.
    val count = if (item.messages == 1) "1 message" else "${item.messages} messages"
    val phrase = listOfNotNull(
        who,
        count,
        if (item.theyReplied) null else "no reply",
    ).joinToString(" · ") + ", " + NoteDates.clock(JournalDay.clockMinutes(item.minutes)).orEmpty()

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.4f.verticalGridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(1.6f.verticalGridUnitsAsDp())
                .background(colors.rule),
        )
        LightText(
            text = phrase,
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
        )
    }
}

/**
 * Music, drawn as something that was going on rather than something that happened.
 *
 * **This is the one thing on a day that runs alongside everything else.** A photograph is a moment
 * and an appointment is a moment, but you listened to a record *while* doing those — so it is drawn
 * as a duration in the margin, with a rule down its length, rather than as another row in the
 * sequence pretending the afternoon stopped for it.
 *
 * Set against the left edge and quiet, because it is the background of the day. The exact overlap is
 * not drawn: the page is compressed non-linearly, so a bar of the true height would be a lie in the
 * other direction. Saying how long it went on, at the point it started, is honest and readable.
 */
@Composable
fun ListeningSpan(item: DayTimeline.Item.Listening, modifier: Modifier = Modifier) {
    val colors = LightThemeTokens.colors
    val minutes = (item.untilMinutes - item.minutes).coerceAtLeast(0)

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.4f.verticalGridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A short rule stands in for the length of it — an actual bar cannot be drawn to scale on a
        // page whose scale changes.
        Box(
            Modifier
                .width(2.dp)
                .height(1.6f.verticalGridUnitsAsDp())
                .background(colors.rule),
        )
        LightText(
            text = phraseFor(item, minutes),
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(start = 0.6f.gridUnitsAsDp()),
        )
    }
}

/**
 * "Listened to Talk Talk, Slowdive and Cocteau Twins and 4 more for 2h".
 *
 * The artists are named most-played first, and only a few of them: three is enough to recognise an
 * afternoon, and past that it stops being a sentence and becomes a list. The rest are a count,
 * because "and 4 more" says something ("it was on shuffle") that four extra names do not.
 *
 * A run of one artist just names them, which is the common and the most useful case.
 */
private fun phraseFor(item: DayTimeline.Item.Listening, minutes: Int): String {
    val howLong = when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> null
    }
    val named = item.artists.filter { it.isNotBlank() }
    val who = when {
        named.isEmpty() -> "music"
        // An Oxford-less list, then the leftovers. "A, B and C and 4 more" is clumsy read aloud and
        // clear on a screen, which is the one that matters here.
        item.moreArtists > 0 -> named.joinToString(", ") + " and ${item.moreArtists} more"
        named.size == 1 -> named.single()
        else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
    }
    return if (howLong == null) "Listened to $who" else "Listened to $who for $howLong"
}

/**
 * The all-day things, as a section at the top of the day.
 *
 * They were a wrapped strip of small words under the date, which is where all-day entries *belong*
 * — they describe the whole day rather than a point in it — but not what they are worth. "Alex's
 * birthday" and "Flying to Chicago" are events, and drawn at superfine size in a flow row they read
 * as tags on the date rather than as things that are happening.
 *
 * So: a labelled section, one full row each, above the scroll and outside it. Above, because a
 * day's whole-day facts are the frame you read the rest of it inside; outside the scroll, because
 * they are the frame and a frame that slides away with the page is not one. A holiday keeps its
 * glyph — the same one the grid draws in the corner of the cell, so the tree on the 25th and the
 * words "Christmas Day" are visibly the same fact.
 */
@Composable
fun AllDaySection(
    entries: List<DayTimeline.Item.Entry>,
    onOpen: (AgendaRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        LightSectionLabel(if (entries.size == 1) "ALL DAY" else "ALL DAY · ${entries.size}")
        entries.forEach { entry ->
            val holiday = entry.row.holidayId?.let { LightIcons.holiday(it) }
            LightListRow(
                title = entry.row.title,
                sub = entry.row.subtitle,
                leading = holiday ?: LightIcons.Calendar,
                // Filled, the same as a timed entry down the page: one rule — a calendar entry is
                // white — is a rule you can read off the screen. Two, with all-day quietly
                // exempted, is a difference nobody can account for and everybody notices.
                inverted = true,
                onClick = { onOpen(entry.row) },
            )
            LightRule()
        }
    }
}

/**
 * What the weather did, or is expected to.
 *
 * The tense is the whole point: a day that has gone says "It rained", a day still to come says
 * "Rain". Same field, two different questions, and writing "Rain" on last Tuesday would be a diary
 * that had not noticed Tuesday happened.
 *
 * Nothing is drawn on an ordinary cloudy day. Most days are cloudy, and a journal that writes
 * "Cloudy" on two hundred of them has said nothing on any — so only the days you would remember get
 * a line. Temperatures come along when they are known, because "It rained, 4 to 9" is a day you can
 * picture and "It rained" is only half of one.
 */
@Composable
fun DayWeatherLine(weather: DayWeather?, unfinished: Boolean, modifier: Modifier = Modifier) {
    if (weather == null) return
    val kind = weather.kind
    if (!WeatherCodes.notable(kind)) return

    val what = if (unfinished) WeatherCodes.ahead(kind) else WeatherCodes.past(kind)
    val range = listOfNotNull(weather.minC?.let { Math.round(it) }, weather.maxC?.let { Math.round(it) })
    val temperatures = if (range.size == 2) "${range[0]}° to ${range[1]}°" else null

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = lightInset(), vertical = 0.4f.verticalGridUnitsAsDp()),
    ) {
        LightText(
            text = listOfNotNull(what.uppercase(), temperatures).joinToString("  ·  "),
            variant = LightTextVariant.Superfine,
            lighten = true,
        )
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
            // What the screen time went on. "38M CHAT" says more about an afternoon than the
            // total does, and it is the same query answering both.
            addAll(stats.apps)
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
 * Where the screen time went, one line per app.
 *
 * The day's own numbers already carry the three biggest — "38M CHAT" says more about an afternoon
 * than two hours of screen time does — and three was all that fit there. This is the rest of the
 * answer, at the end of the day where there is room for it: every app worth a minute, biggest
 * first, with its longest single sitting beside it.
 *
 * **The longest run is the interesting number.** Thirty-four minutes of a camera app is a walk with
 * a camera; thirty-four minutes in one sitting is something else, and the total alone cannot tell
 * those apart. Shown only when it is most of the total, because otherwise it is noise.
 *
 * Quiet type, like everything else at this end of the day: this is what the phone noticed, not what
 * you did.
 */
@Composable
fun DayAppTime(
    apps: List<AppUse.Slice>,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(vertical = 0.4f.verticalGridUnitsAsDp())) {
        LightSectionLabel("WHERE THE TIME WENT")
        apps.forEach { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = lightInset(),
                        vertical = 0.25f.verticalGridUnitsAsDp(),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = app.label,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.weight(1f),
                )
                LightText(
                    text = buildString {
                        append(minutesLabel(app.minutes))
                        // Only when one sitting was most of it. Otherwise this says "you used it
                        // several times", which is what the absence of it already says.
                        if (app.longestMinutes >= 2 &&
                            app.longestMinutes * 2 >= app.minutes &&
                            app.longestMinutes < app.minutes
                        ) {
                            append(" · longest ")
                            append(minutesLabel(app.longestMinutes))
                        }
                    },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }
}

/** "34m", or "2h 14m". Hours matter above sixty minutes and not below it. */
private fun minutesLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

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
    val edgePx = with(LocalDensity.current) { (edge * LANDSCAPE).roundToPx() }

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
                        // Its own shape here too, or a square frame letterboxes a portrait
                        // photograph inside a visible border and looks like a mistake.
                        modifier = Modifier
                            .height(edge)
                            .aspectRatio(photo.aspect ?: LANDSCAPE),
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
