package com.gios.lightnotebook.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.data.PhotoLibrary
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.ui.theme.lightCombinedClickable
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The photographs taken on a day, in a strip above the day's entries.
 *
 * This is the cheapest thing in the app and possibly the most useful: it needs no bridge to
 * Roll, no content provider, no agreement between the two apps and no coordinated release.
 * Roll writes to MediaStore because that is what a camera does, so a calendar that asks the
 * system "what was photographed on the 30th" gets Roll's pictures for free — along with the
 * stock camera's, a screenshot, and anything else. See [PhotoLibrary].
 *
 * A horizontal strip rather than a wrapped grid, for one reason: the day screen's job is the
 * list of things on the day, and a grid of a heavy day's forty photographs would push every
 * entry off a 3.92" panel. A strip is bounded by construction — always one row tall, whatever
 * the day held.
 */
@Composable
fun Filmstrip(
    photos: List<DevicePhoto>,
    onOpen: (DevicePhoto) -> Unit,
    onAttach: (DevicePhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return

    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = lightInset(),
                end = lightInset(),
                top = 0.5f.verticalGridUnitsAsDp(),
                bottom = 0.5f.verticalGridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The count sits in the margin rather than under a heading of its own: a strip of
        // pictures already announces itself, and a row of chrome above it would cost more
        // vertical space than the pictures do.
        LightText(
            text = photos.size.toString(),
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(end = 0.6f.gridUnitsAsDp()),
        )
        LazyRow(Modifier.weight(1f)) {
            items(photos, key = { it.id }) { photo ->
                Thumbnail(
                    photo = photo,
                    edge = THUMB_UNITS.verticalGridUnitsAsDp(),
                    onClick = { onOpen(photo) },
                    onLongClick = { onAttach(photo) },
                )
                Spacer(Modifier.width(0.4f.gridUnitsAsDp()))
            }
        }
    }
}

private const val THUMB_UNITS = 5.4f

/**
 * One frame.
 *
 * Loaded off the main thread and held in [PhotoLibrary]'s byte-sized cache, so scrolling the
 * strip a second time costs nothing. `remember(photo.id)` rather than `remember`: a `LazyRow`
 * recycles composables, so without the key a frame keeps the previous photo's bitmap for a
 * frame every time the day changes — which reads as the wrong picture on the wrong day.
 *
 * A tap opens the picture; a long press files it against the day as an entry, which is the
 * only way a photograph becomes something you can give a time or a reminder to.
 */
@Composable
private fun Thumbnail(
    photo: DevicePhoto,
    edge: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LightThemeTokens.colors
    val edgePx = with(LocalDensity.current) { edge.roundToPx() }
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo.id, edgePx) {
        bitmap = withContext(Dispatchers.IO) {
            PhotoLibrary.thumbnail(context, photo, edgePx)
        }
    }

    Box(
        Modifier
            .size(edge)
            // An outline while it loads, never a filled block: on a 1-bit panel a grey
            // rectangle reads as a broken image, an empty frame reads as one on its way.
            .border(1.dp, colors.rule)
            .lightCombinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "A photograph from this day",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(edge),
            )
        }
    }
}

/**
 * The offer to read the library, shown in the strip's place when the permission is missing.
 *
 * Asked for here rather than at first launch, matching how this app already treats
 * notifications: the calendar is the only thing that wants photographs, so this is the screen
 * where the question makes sense and can be answered with a reason visible on it.
 */
@Composable
fun FilmstripPermissionRow(onAsk: () -> Unit, modifier: Modifier = Modifier) {
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
