package com.gios.lightnotebook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import java.io.File

/**
 * Point it at a page. The frame already on screen is what gets sent — grabbing the
 * preview bitmap is instant, and flat paper does not need a full capture round trip.
 */
@Composable
fun CameraScreen(
    hint: String,
    newFile: () -> File,
    onCaptured: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize()) {
            LightEmptyState("Notebook needs the camera to read a page.", Modifier.weight(1f))
            LightRule()
            LightBottomBar(listOf(LightBarItem.Text("BACK", onClick = onCancel), null, null))
        }
        return
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var captured by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.bindToLifecycle(lifecycleOwner) }

    fun capture() {
        if (captured) return
        val bmp = preview?.bitmap ?: return
        captured = true
        val out = newFile()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onCaptured(out)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        this.controller = controller
                        preview = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            LightText(
                text = hint,
                variant = LightTextVariant.Detail,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        horizontal = lightInset(),
                        vertical = 1f.verticalGridUnitsAsDp(),
                    ),
            )
        }
        LightRule()
        LightBottomBar(
            items = listOf(
                LightBarItem.Text("CANCEL", onClick = onCancel),
                LightBarItem.Text(
                    text = if (captured) "READING" else "CAPTURE",
                    onClick = ::capture,
                ),
                null,
            ),
        )
    }
}
