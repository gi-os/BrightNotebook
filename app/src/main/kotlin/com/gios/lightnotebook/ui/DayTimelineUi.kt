package com.gios.lightnotebook.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import com.gios.lightnotebook.util.Daylight
import com.gios.lightnotebook.util.OnThisDay
import com.gios.lightnotebook.util.NoteDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // Asked for at the size it is actually drawn, so a full-width picture gets a bigger
    // thumbnail than a burst frame and neither is scaled up on a panel this small.
    val burstEdge = BURST_THUMB_UNITS.verticalGridUnitsAsDp()
    val density = LocalDensity.current
    val burstPx = with(density) { burstEdge.roundToPx() }
    val fullWidthPx = with(density) {
        (LocalConfiguration.current.screenWidthDp.dp - lightInset() * 2).roundToPx()
    }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightText(
                text = NoteDates.clock(item.minutes).orEmpty(),
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
            if (!item.single) {
                LightText(
                    text = "–" + NoteDates.clock(item.untilMinutes).orEmpty() +
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
                // 4:3 held explicitly rather than letting the bitmap decide: a portrait shot
                // is otherwise taller than the screen, and a day of them cannot be scrolled
                // past. The thumbnail is cropped to fit, which is what the roll does too.
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                onClick = { onOpen(photo) },
                onLongClick = { onAttach(photo) },
            )
        } else {
            LazyRow {
                items(resolved, key = { it.id }) { photo ->
                    PhotoFrame(
                        photo = photo,
                        requestPx = burstPx,
                        modifier = Modifier.size(burstEdge),
                        onClick = { onOpen(photo) },
                        onLongClick = { onAttach(photo) },
                    )
                    Spacer(Modifier.width(0.4f.gridUnitsAsDp()))
                }
            }
        }
    }
}

private const val BURST_THUMB_UNITS = 5.4f

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
@Composable
fun DayShape(
    bookends: DayTimeline.Bookends?,
    daylight: Daylight.Result?,
    /** Today's day is unfinished, so its last moment is "so far" rather than an end. */
    unfinished: Boolean,
    modifier: Modifier = Modifier,
) {
    val parts = buildList {
        if (bookends != null) {
            val first = NoteDates.clock(bookends.firstMinutes).orEmpty()
            val last = NoteDates.clock(bookends.lastMinutes).orEmpty()
            add(if (unfinished) "SINCE $first" else "$first – $last")
        }
        when (daylight) {
            is Daylight.Result.Times -> {
                val light = NoteDates.clock(daylight.sunriseMinutes).orEmpty()
                val dark = NoteDates.clock(daylight.sunsetMinutes).orEmpty()
                // Hours and minutes, not "743 minutes". Nobody reads a day in minutes.
                val hours = daylight.daylightMinutes / 60
                val mins = daylight.daylightMinutes % 60
                add("LIGHT $light–$dark · ${hours}H ${mins}M")
            }
            Daylight.Result.AlwaysDay -> add("THE SUN DOES NOT SET")
            Daylight.Result.AlwaysNight -> add("THE SUN DOES NOT RISE")
            null -> Unit
        }
    }
    if (parts.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = lightInset(),
                vertical = 0.4f.verticalGridUnitsAsDp(),
            ),
    ) {
        parts.forEach { part ->
            LightText(text = part, variant = LightTextVariant.Superfine, lighten = true)
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
