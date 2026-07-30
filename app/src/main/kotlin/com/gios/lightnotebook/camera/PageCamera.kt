package com.gios.lightnotebook.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the lens is actually doing, read from the capture result rather than assumed. */
enum class AfState { Idle, Scanning, Locked, Failed }

enum class FlashMode { Off, On, Auto }

/** A photograph, straight off the sensor. */
class CapturedPage(val jpeg: ByteArray, val rotationDegrees: Int)

/**
 * The camera, ported from `gi-os/LightCamera`'s engine and cut down to the one job here:
 * photographing a page well enough that a model can read the handwriting on it.
 *
 * What this replaces is worth stating, because it explains every choice below. The old capture
 * grabbed `PreviewView.bitmap` — the frame already on screen. That is preview resolution,
 * whatever the preview happened to be focused on, and no flash: fine for a printed receipt,
 * which is where it came from, and the reason biro on lined paper came back as `[?]`.
 *
 * Three things matter now:
 *
 *  - **A real capture at real resolution.** `ImageCapture` with a resolution strategy asking
 *    for a ~3000px long edge. Not the sensor's full 50MP: reading out and encoding 8160x6144
 *    costs this ISP the better part of a second or two, and every pixel above about 3000 is
 *    thrown away by the downscale before upload anyway. `CAPTURE_MODE_MINIMIZE_LATENCY`, and
 *    deliberately *not* zero-shutter-lag — LightCamera found that this camera accepts the mode,
 *    binds without complaint, then fails every `takePicture`.
 *  - **Focus you can aim and verify.** Tap the page and it meters there; the AF state comes from
 *    `CONTROL_AF_STATE` in the capture result, so the bracket on screen snaps when the lens
 *    snaps rather than when a future completes. A page is flat and close, which is exactly where
 *    a centre-weighted guess focuses on the wrong thing.
 *  - **Flash.** Off, on, or auto, applied to the capture. Photographing paper indoors in the
 *    evening is the normal case, not the exception.
 */
// androidx.annotation.OptIn, not Kotlin's: ExperimentalCamera2Interop is a Java-declared marker
// carrying @RequiresOptIn from annotation-experimental, which Kotlin's own @OptIn does not
// recognise — it compiles and warns that it has no effect.
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class PageCamera(private val context: Context) {

    private val _afState = MutableStateFlow(AfState.Idle)
    val afState: StateFlow<AfState> = _afState.asStateFlow()

    /** Where the last focus request was aimed, in view pixels, for drawing the bracket. */
    private val _focusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPoint: StateFlow<Pair<Float, Float>?> = _focusPoint.asStateFlow()

    private val _flash = MutableStateFlow(FlashMode.Off)
    val flash: StateFlow<FlashMode> = _flash.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var owner: LifecycleOwner? = null

    private val captureExecutor = Executors.newSingleThreadExecutor()

    /** The phone's attitude, so a page shot in landscape is not saved on its side. */
    private var lastRotation = Surface.ROTATION_0
    private var orientation: OrientationEventListener? = null

    /**
     * The camera's own account of the focus run. Only a tap opens the window this reports in,
     * so the HAL's continuous hunting does not flicker the bracket.
     */
    private var awaitingFocus = false

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            val state = when (af) {
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                -> AfState.Scanning

                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                -> AfState.Locked

                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AfState.Failed
                else -> AfState.Idle
            }
            // Passive states are the camera thinking to itself; only report them while a tap is
            // outstanding, or the bracket would twitch at rest.
            if (awaitingFocus || state == AfState.Scanning) _afState.value = state
            if (state == AfState.Locked || state == AfState.Failed) awaitingFocus = false
        }
    }

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.previewView = view
        watchOrientation()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                provider = runCatching { future.get() }.getOrNull()
                rebind()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun rebind() {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return

        // 4:3 is the sensor's own shape, and a sheet of paper is closer to it than 16:9 —
        // asking for a wider frame would throw away pixels down the long edge of the page.
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
        // The only reason Camera2Interop is here: the real AF state.
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(resultCallback)
        val preview = previewBuilder.build()

        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(CAPTURE_LONG_EDGE, CAPTURE_LONG_EDGE * 3 / 4),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        val capture = ImageCapture.Builder()
            .setResolutionSelector(captureSelector)
            .setJpegQuality(JPEG_QUALITY)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(lastRotation)
            .build()
        imageCapture = capture
        applyFlash()

        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            preview.setSurfaceProvider(view.surfaceProvider)
            _ready.value = true
        }.onFailure {
            Log.w(TAG, "could not bind the camera: $it")
            _ready.value = false
        }
    }

    /** Rotation is read continuously, so the file is upright however the phone was held. */
    private fun watchOrientation() {
        if (orientation != null) return
        orientation = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    degrees >= 315 || degrees < 45 -> Surface.ROTATION_0
                    degrees < 135 -> Surface.ROTATION_270
                    degrees < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                if (rotation == lastRotation) return
                lastRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }.also { it.enable() }
    }

    fun cycleFlash(): FlashMode {
        val next = when (_flash.value) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        _flash.value = next
        applyFlash()
        return next
    }

    private fun applyFlash() {
        imageCapture?.flashMode = when (_flash.value) {
            FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
            FlashMode.On -> ImageCapture.FLASH_MODE_ON
            FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    /**
     * Tap to focus. [FocusMeteringAction] with AF and AE together, because a page held under a
     * lamp needs the exposure metered where the writing is as much as the focus.
     */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val view = previewView ?: return
        val point = runCatching { view.meteringPointFactory.createPoint(x, y) }.getOrNull() ?: return
        _focusPoint.value = x to y
        _afState.value = AfState.Scanning
        awaitingFocus = true
        val action = FocusMeteringAction
            .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(FOCUS_HOLD_MS, TimeUnit.MILLISECONDS)
            .build()
        runCatching { control.startFocusAndMetering(action) }
    }

    /** Focus the middle of the frame — what a page fills, and what the shutter waits for. */
    fun focusCentre() {
        val view = previewView ?: return
        if (view.width == 0 || view.height == 0) return
        focusAt(view.width * 0.5f, view.height * 0.5f)
    }

    suspend fun capture(): CapturedPage = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("camera not bound"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val page = runCatching {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        CapturedPage(bytes, image.imageInfo.rotationDegrees)
                    }
                    image.close()
                    page.fold(
                        { if (cont.isActive) cont.resume(it) },
                        { if (cont.isActive) cont.resumeWithException(it) },
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            },
        )
    }

    fun release() {
        orientation?.disable()
        orientation = null
        runCatching { provider?.unbindAll() }
        camera = null
        imageCapture = null
        previewView = null
        owner = null
        _ready.value = false
        captureExecutor.shutdown()
    }

    private companion object {
        const val TAG = "PageCamera"

        /**
         * Long edge asked for. Plenty for handwriting — the upload is downscaled to 2200 — and
         * far short of the sensor's 50MP, which would cost a second or two of ISP time per shot.
         */
        const val CAPTURE_LONG_EDGE = 3000
        const val JPEG_QUALITY = 92
        const val FOCUS_HOLD_MS = 5_000L
    }
}
