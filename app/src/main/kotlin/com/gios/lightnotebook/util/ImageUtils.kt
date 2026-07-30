package com.gios.lightnotebook.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

object ImageUtils {

    /** Rotate upright per EXIF so the pixels we send match what was on screen. */
    fun normalizeUpright(bytes: ByteArray): Bitmap {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * Cap the long edge. Handwriting is the demanding case — a wall planner packs a lot of
     * small biro into each square, and the difference between a 3 and an 8 is a few pixels —
     * so this is deliberately higher than a printed receipt would need. Past this the extra
     * pixels cost tokens and latency without making a single word more legible.
     */
    fun downscaled(src: Bitmap, maxEdge: Int = 2200): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90): File {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    /** Reads the capture back off disk, straightens it and shrinks it in place. */
    fun prepareForUpload(file: File) {
        runCatching {
            val bytes = file.readBytes()
            val upright = normalizeUpright(bytes)
            saveJpeg(downscaled(upright), file)
        }
    }
}
