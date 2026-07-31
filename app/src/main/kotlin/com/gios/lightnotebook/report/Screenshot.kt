package com.gios.lightnotebook.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.Window
import java.io.ByteArrayOutputStream

/**
 * The screen as it was at the moment of the shake.
 *
 * `PixelCopy` off the window rather than `View.draw` into a canvas: the composition draws through
 * a hardware canvas, and the software path comes back with the text and none of the images. It
 * also copies only this app's own window, which is the whole reason no permission is involved —
 * there is no screen capture here, just the app looking at itself.
 *
 * The result goes into the issue body as base64, so it has a hard size ceiling. A screenshot at
 * 360px of a black-and-white interface is 10-20KB, which fits; the ladder below is for the
 * occasional photograph-heavy screen that does not.
 */
object Screenshot {

    /** Base64 inflates by 4/3, and a GitHub issue body stops at 65536 characters. */
    private const val BUDGET_BYTES = 30_000
    private val WIDTHS = intArrayOf(360, 280, 200)

    /**
     * Grab the window. The callback always runs, with null when the copy failed — a report
     * without a picture is still a report, so nothing here is allowed to be fatal.
     */
    fun capture(window: Window, onResult: (Bitmap?) -> Unit) {
        val view = window.peekDecorView()
        val width = view?.width ?: 0
        val height = view?.height ?: 0
        if (width <= 0 || height <= 0) {
            onResult(null)
            return
        }
        val target = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return onResult(null)

        runCatching {
            PixelCopy.request(
                window,
                target,
                { result -> onResult(if (result == PixelCopy.SUCCESS) target else null) },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure {
            // Thrown, not reported, when the window has no surface yet.
            onResult(null)
        }
    }

    /** Base64 PNG small enough to sit in an issue body, or null if even 200px will not fit. */
    fun encode(source: Bitmap): String? {
        for (width in WIDTHS) {
            val bytes = shrink(source, width) ?: continue
            if (bytes.size <= BUDGET_BYTES) {
                return Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        return null
    }

    private fun shrink(source: Bitmap, width: Int): ByteArray? = runCatching {
        val height = (source.height.toFloat() * width / source.width).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            // The panel is greyscale but the buffer is not, and colour costs bytes that buy
            // nothing here. Notebook can put the phone into colour mode, so this matters.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(
            source,
            android.graphics.Rect(0, 0, source.width, source.height),
            android.graphics.Rect(0, 0, width, height),
            paint,
        )
        ByteArrayOutputStream().use { stream ->
            out.compress(Bitmap.CompressFormat.PNG, 100, stream)
            out.recycle()
            stream.toByteArray()
        }
    }.getOrNull()
}
