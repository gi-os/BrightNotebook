package com.gios.lightnotebook.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photographing a page by asking Roll to do it.
 *
 * Roll (`gi-os/LightCamera`) is a better camera than the one this app carried: its capture is
 * pinned to 4000x3000 rather than the sensor's full 50MP, which is the difference between a
 * shutter that responds and the one-to-three-second lag every review of this phone complains
 * about, and it has focus, exposure and a level. None of that is worth reimplementing here to
 * photograph a sheet of paper.
 *
 * **Plain, deliberately.** The scan is going to Claude to be read, and Roll's filter dial might
 * be resting on Game Boy — a dithered page is illegible, and the failure would present as "the
 * notebook can't read my handwriting" with nothing on screen to explain it. Roll serves a
 * capture request with no filter unless the caller passes [EXTRA_ALLOW_FILTER], and the scan
 * path never passes it. Attaching a photograph to a note is the case that will.
 *
 * The in-app camera stays as the fallback, because Roll may not be installed and photographing
 * a page is not a feature worth losing to that.
 */
object RollCapture {

    /** Roll's package. Not resolved by intent: this is a preference for one camera. */
    const val ROLL_PACKAGE = "com.gios.lightcamera"

    /**
     * Roll's own opt-in extra, spelled out here because the two apps share no code.
     *
     * A string constant copied between repositories is exactly the kind of thing that drifts,
     * and the reason a shared module is worth building — until there is one, this is the pair
     * of places that have to agree. Roll's side is `MainActivity.EXTRA_ALLOW_FILTER`.
     */
    const val EXTRA_ALLOW_FILTER = "com.gios.lightcamera.extra.ALLOW_FILTER"

    private const val AUTHORITY_SUFFIX = ".captures"

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ROLL_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * The intent that hands [file] to Roll to fill, or null when Roll cannot serve it.
     *
     * Explicitly addressed to Roll rather than left implicit. An implicit `IMAGE_CAPTURE` would
     * open a chooser, or silently reach the stock camera with the slow shutter this exists to
     * avoid — and on a phone with one user who installed Roll on purpose, a chooser is a
     * question with a known answer.
     */
    fun intentFor(context: Context, file: File, allowFilter: Boolean = false): Intent? {
        if (!installed(context)) return null
        val uri = runCatching { uriFor(context, file) }.getOrNull() ?: return null

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            setPackage(ROLL_PACKAGE)
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            if (allowFilter) putExtra(EXTRA_ALLOW_FILTER, true)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Checked before it is returned, not caught after it is fired: an unresolved explicit
        // intent throws ActivityNotFoundException from inside the launcher, where the fallback
        // to the in-app camera can no longer be chosen. Roll being installed is not the same as
        // Roll answering this action — an older build might not.
        if (intent.resolveActivity(context.packageManager) == null) return null

        // The flags cover the app that receives the intent; this grant is belt and braces for
        // the case where the receiving process reads the uri from a service or a second
        // activity, which the flags do not follow.
        context.grantUriPermission(
            ROLL_PACKAGE,
            uri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        return intent
    }

    /** Released once the photograph has been read, so the hole does not stay open. */
    fun revoke(context: Context, file: File) {
        runCatching {
            context.revokeUriPermission(
                uriFor(context, file),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    /**
     * Whether Roll actually wrote something.
     *
     * `RESULT_OK` is not enough on its own: a camera can hand back OK having written nothing,
     * and an empty file then reaches the vision parser as a zero-byte JPEG, which fails with a
     * decode error rather than with anything a user could act on.
     */
    fun wrote(file: File): Boolean = file.exists() && file.length() > 0L
}
