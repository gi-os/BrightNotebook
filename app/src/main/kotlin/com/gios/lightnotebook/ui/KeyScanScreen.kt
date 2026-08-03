package com.gios.lightnotebook.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gios.lightnotebook.ui.theme.LightBarItem
import com.gios.lightnotebook.ui.theme.LightIcons
import com.gios.lightnotebook.ui.theme.LightRule
import com.gios.lightnotebook.ui.theme.LightText
import com.gios.lightnotebook.ui.theme.LightTextVariant
import com.gios.lightnotebook.ui.theme.LightThemeTokens
import com.gios.lightnotebook.ui.theme.LightTopBar
import com.gios.lightnotebook.ui.theme.gridUnitsAsDp
import com.gios.lightnotebook.ui.theme.lightInset
import com.gios.lightnotebook.ui.theme.verticalGridUnitsAsDp
import com.gios.lightnotebook.util.ApiKeyQr
import com.gios.lightnotebook.util.CalendarUrl
import com.gios.lightnotebook.util.QrAnalyzer
import java.util.concurrent.Executors

/**
 * Scans the Anthropic key off the companion page at
 * <https://gi-os.github.io/LightNotebook/> — a 100-character key is not something to type
 * on a phone keyboard.
 *
 * The scanner is in-app rather than an intent into zxing-android-embedded: it decodes
 * frames with [QrAnalyzer] and draws nothing but a reticle, so the screen stays in the
 * LightOS idiom instead of flashing up somebody else's Material activity.
 */
@Composable
fun KeyScanScreen(
    onKey: (String) -> Unit,
    onBack: () -> Unit,
) = QrScanScreen(
    title = "SCAN KEY",
    hint = "Point at the QR on gi-os.github.io/LightNotebook",
    wrongHint = "That code isn't an API key.",
    accept = { ApiKeyQr.keyIn(it) },
    onValue = onKey,
    onBack = onBack,
)

/**
 * Scans a calendar feed URL. Same screen, different thing accepted.
 *
 * A published feed URL is a hundred-odd characters with a random secret in the middle —
 * exactly the sort of string that is unreasonable to type on this phone and trivial to
 * scan off the computer that generated it.
 */
@Composable
fun CalendarScanScreen(
    onUrl: (String) -> Unit,
    onBack: () -> Unit,
) = QrScanScreen(
    title = "SCAN CALENDAR",
    hint = "Point at the calendar's QR code.",
    wrongHint = "That code isn't a calendar address.",
    accept = { CalendarUrl.feedIn(it) },
    onValue = onUrl,
    onBack = onBack,
)

/**
 * The scanner itself. What counts as a valid payload is the caller's business: [accept]
 * returns the cleaned-up value or null, and null keeps the camera running rather than
 * ending the scan on a poster.
 */
@Composable
private fun QrScanScreen(
    title: String,
    hint: String,
    wrongHint: String,
    accept: (String) -> String?,
    onValue: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LightThemeTokens.colors

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

    // Set when a code decodes but isn't a key, which is what happens if you point this at
    // a poster. Saying so beats saving a URL as an API key.
    var wrongCode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LightTopBar(
            title = title,
            left = LightBarItem.Icon(LightIcons.Back, sizeUnits = 1.6f, onClick = onBack),
        )
        LightRule()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                QrCamera(
                    onDecoded = { payload ->
                        val value = accept(payload)
                        if (value != null) {
                            onValue(value)
                            true
                        } else {
                            // Keep the camera running: a poster in frame should not end
                            // the scan, it should just say so.
                            wrongCode = true
                            false
                        }
                    },
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(13f.gridUnitsAsDp())
                        .border(1.dp, colors.content),
                )
                LightText(
                    text = if (wrongCode) wrongHint else hint,
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
            } else {
                LightEmptyState("Notebook needs the camera to scan a code.")
            }
        }
    }
}

/**
 * Preview plus analysis, bound once. Decoding runs on its own thread and only the newest
 * frame is kept, so a slow decode never queues up behind the camera.
 */
@Composable
private fun QrCamera(onDecoded: (String) -> Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var handled by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val provider = runCatching { future.get() }.getOrNull()
                        ?: return@addListener
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        QrAnalyzer { text ->
                            // The analyzer thread can deliver the same code several times
                            // before the first result has navigated away; the flag clears
                            // again if the caller didn't accept it.
                            if (!handled) {
                                handled = true
                                previewView.post { if (!onDecoded(text)) handled = false }
                            }
                        },
                    )
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}
