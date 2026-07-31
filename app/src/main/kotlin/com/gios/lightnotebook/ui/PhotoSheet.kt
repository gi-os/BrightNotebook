package com.gios.lightnotebook.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import java.io.File

/**
 * The photograph a transcription came from.
 *
 * This is the answer to "did it really say that". The page is kept when Claude reads it —
 * see `NotebookRepository.newCaptureFile` — precisely so a wrong word can be checked rather
 * than argued with, and every row that came from a camera can get back to it.
 *
 * Decoded at a quarter size: it is being looked at on a 3.92" panel to confirm a word or a
 * date, and a full-resolution bitmap of a sheet of paper is megabytes of heap for no more
 * legibility than this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSheet(path: String?, onDismiss: () -> Unit) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    val bitmap = remember(path) {
        if (path == null) return@remember null
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        runCatching {
            // Two kinds of string arrive here and both have to work. A page Claude read is a
            // file in `filesDir`; a photograph filed off the day's filmstrip is a MediaStore
            // `content://` uri, which `decodeFile` cannot open — it returns null silently, so
            // the failure looked like "that photo is gone" rather than like a bug.
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            } else {
                File(path).takeIf { it.exists() }?.let {
                    BitmapFactory.decodeFile(it.absolutePath, options)
                }
            }
        }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = null,
    ) {
        Column(
            Modifier.padding(
                horizontal = lightInset(),
                vertical = 1f.verticalGridUnitsAsDp(),
            ),
        ) {
            LightText("THE PHOTO", LightTextVariant.Superfine, lighten = true)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 20f.verticalGridUnitsAsDp())
                    .padding(top = 0.6f.verticalGridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    LightText(
                        text = "That photo is gone.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "The photographed page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
