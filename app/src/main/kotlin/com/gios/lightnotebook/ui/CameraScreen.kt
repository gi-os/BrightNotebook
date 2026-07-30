package com.gios.lightnotebook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightnotebook.camera.AfState
import com.gios.lightnotebook.camera.FlashMode
import com.gios.lightnotebook.camera.PageCamera
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightBottomBar
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import java.io.File
import kotlinx.coroutines.launch

/**
 * Point it at a page.
 *
 * A real capture now, through [PageCamera] — a full-resolution `ImageCapture`, tap-to-focus,
 * and flash. It used to grab the frame already on screen, which is preview resolution focused
 * on whatever the camera guessed at, and is why biro came back as `[?]`.
 */
@Composable
fun CameraScreen(
    hint: String,
    newFile: () -> File,
    onCaptured: (File, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val colors = LightThemeTokens.colors
    val density = LocalDensity.current

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
            LightBottomBar(
                listOf(
                    LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.9f, onClick = onCancel),
                    null,
                    null,
                ),
            )
        }
        return
    }

    val camera = remember { PageCamera(context) }
    val afState by camera.afState.collectAsStateWithLifecycle()
    val focusPoint by camera.focusPoint.collectAsStateWithLifecycle()
    val flash by camera.flash.collectAsStateWithLifecycle()
    val ready by camera.ready.collectAsStateWithLifecycle()
    var capturing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { camera.release() } }

    fun capture() {
        if (capturing || !ready) return
        capturing = true
        scope.launch {
            val result = runCatching { camera.capture() }
            result.fold(
                onSuccess = { page ->
                    val out = newFile()
                    runCatching { out.writeBytes(page.jpeg) }
                        .onSuccess { onCaptured(out, page.rotationDegrees) }
                        .onFailure {
                            capturing = false
                            failed = "Could not save that photo."
                        }
                },
                onFailure = {
                    capturing = false
                    // The shutter failing is worth saying out loud rather than looking broken.
                    failed = "The camera didn't take that one. Try again."
                },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        // FILL_CENTER matches what the capture will contain closely enough that
                        // what you framed is what gets read.
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        camera.bind(lifecycleOwner, view)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { position ->
                            camera.focusAt(position.x, position.y)
                        }
                    },
            )

            // The focus bracket, drawn where you tapped and reflecting what the lens reports:
            // scanning, locked, or gave up. Read from CONTROL_AF_STATE, so it snaps when the
            // lens does rather than when a future completes.
            focusPoint?.let { (x, y) ->
                val bracket = 22.dp
                val half = with(density) { bracket.toPx() / 2f }
                Box(
                    Modifier
                        .offset { IntOffset((x - half).toInt(), (y - half).toInt()) }
                        .size(bracket)
                        .border(
                            width = 2.dp,
                            color = when (afState) {
                                AfState.Locked -> colors.content
                                AfState.Failed -> colors.contentFaint
                                else -> colors.contentSecondary
                            },
                        ),
                )
            }

            LightText(
                text = failed ?: when {
                    capturing -> "Reading…"
                    !ready -> "Starting the camera…"
                    afState == AfState.Failed -> "Couldn't focus there. Tap somewhere else."
                    else -> hint
                },
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = lightInset(),
                        end = lightInset(),
                        bottom = 1f.verticalGridUnitsAsDp(),
                    ),
            )
        }
        LightRule()
        LightBottomBar(
            items = listOf(
                LightBarItem.Icon(LightIcons.Close, sizeUnits = 1.9f, onClick = onCancel),
                LightBarItem.Icon(
                    icon = LightIcons.Camera,
                    sizeUnits = 2.4f,
                    // Dimmed while the shutter is busy, so a second tap is visibly a no-op.
                    lighten = capturing || !ready,
                    onClick = ::capture,
                ),
                LightBarItem.Icon(
                    icon = when (flash) {
                        FlashMode.Off -> LightIcons.FlashOff
                        FlashMode.On -> LightIcons.FlashOn
                        FlashMode.Auto -> LightIcons.FlashAuto
                    },
                    sizeUnits = 1.9f,
                    onClick = {
                        camera.cycleFlash()
                        failed = null
                    },
                ),
            ),
        )
    }
}
