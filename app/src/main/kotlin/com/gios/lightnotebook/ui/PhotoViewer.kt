package com.gios.lightnotebook.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gios.lightnotebook.data.DevicePhoto
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.lightClickable
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.NoteDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * A photograph, full screen and **in colour**.
 *
 * The day is grey because the rest of the phone is, and that is right — but a photograph opened to
 * look at is the one thing on this device with hues in it, so the viewer lifts LightOS's forced
 * greyscale for exactly as long as it is open. See [ColorMode]: it needs a one-time adb grant and
 * degrades to grey without it rather than breaking.
 *
 * Swipes across the day's photographs rather than showing only the one tapped, because that is what
 * anyone does after opening the first: the day is a roll of film, not a set of separate files.
 */
@Composable
fun PhotoViewer(
    photos: List<DevicePhoto>,
    initial: DevicePhoto,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return
    val startIndex = remember(initial.id, photos) {
        photos.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = startIndex) { photos.size }

    // Colour for as long as this is composed, and grey again the instant it is not.
    ColourEffect(enabled = true)
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            // Black behind, not the theme background: a photograph should sit on nothing.
            .background(Color.Black)
            // Tap anywhere to leave. No chrome — a close button on a 3.92" panel is a button over
            // the picture, and the picture is the whole point of being here.
            .lightClickable(haptics = false, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(state = pager, Modifier.fillMaxSize()) { page ->
            FullPhoto(photos[page])
        }

        val current = photos.getOrNull(pager.currentPage)
        if (current != null) {
            LightText(
                text = NoteDates.clock(
                    JournalDay.clockMinutes(current.minutesOfDay(ZoneId.systemDefault())),
                ).orEmpty(),
                variant = LightTextVariant.Superfine,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black),
            )
        }
    }
}

/**
 * One photograph at as much resolution as the panel can use.
 *
 * Decoded with `inSampleSize` rather than at full size: a 12MP JPEG is around 48MB of ARGB and this
 * screen is a thousand pixels across, so decoding it whole would be most of the app's heap for no
 * visible difference. The sample factor is computed from the screen rather than fixed, so the same
 * code is right whatever it is opened on.
 */
@Composable
private fun FullPhoto(photo: DevicePhoto) {
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo.id, widthPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(photo.uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= widthPx && sample < 16) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(photo.uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "A photograph",
                // Fit, not crop: this is the picture being looked at, so none of it is cut off.
                contentScale = ContentScale.Fit,
                // Wrapped and centred rather than filling: `fillMaxSize` with Fit leaves the
                // drawn image centred *within its own bounds*, which is only the same thing when
                // the bounds are the screen — and here they are not once insets are involved.
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
