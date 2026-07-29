package com.gios.lightnotebook.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX analyzer that decodes QR codes off the luminance (Y) plane with ZXing core.
 * Ported from `gi-os/LightQR`.
 *
 * Pure Java, which is the point: LightOS ships without Play Services, so ML Kit is not
 * an option, and zxing-android-embedded's own scanner drags in a whole Activity with
 * Material chrome that looks nothing like the phone.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val source = PlanarYUVLuminanceSource(
                bytes,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text?.let(onResult)
        } catch (_: Exception) {
            // No code in this frame. That is the common case, not an error.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
