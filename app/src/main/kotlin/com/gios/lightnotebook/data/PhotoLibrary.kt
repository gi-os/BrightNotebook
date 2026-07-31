package com.gios.lightnotebook.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import com.gios.lightnotebook.util.JournalDay
import com.gios.lightnotebook.util.PhotoDays
import java.time.ZoneId

/** One photograph on the phone, as the calendar needs it. */
data class DevicePhoto(
    val id: Long,
    val uri: Uri,
    val epochDay: Long,
    /** Milliseconds, already reconciled by [PhotoDays.instantMs]. */
    val takenAt: Long,
    /** The file's name, which is how Roll records a star. Blank when MediaStore had none. */
    val name: String = "",
    /** Pixel dimensions, so a portrait photograph is not cropped into a landscape frame. */
    val width: Int = 0,
    val height: Int = 0,
    /** Starred in Roll. The day gives one of these the large frame. */
    val starred: Boolean = false,
) {
    /**
     * Width over height, or null when MediaStore did not say.
     *
     * The whole reason this is carried: everything was drawn 4:3 landscape, so a portrait photograph
     * lost most of itself to a centre crop — which on a phone, where most photographs are portrait,
     * is nearly all of them.
     */
    val aspect: Float? get() = if (width > 0 && height > 0) width.toFloat() / height else null

    /**
     * Minutes into its journal day, for sitting a photograph among timed entries.
     *
     * Measured from the cutover, not from midnight, so a photograph taken at one in the morning
     * lands near the *bottom* of the night it belonged to rather than at the top of the next day.
     */
    fun minutesOfDay(zone: ZoneId): Int = JournalDay.minutesInto(takenAt, epochDay, zone)
}

/**
 * The phone's photographs, read straight out of MediaStore.
 *
 * **There is no bridge to Roll here, and that is the point.** Roll already writes every
 * exposure to `DCIM/Camera` through MediaStore, so the notebook can ask the system what was
 * photographed on a day and get an answer that includes Roll's pictures, the stock camera's,
 * a screenshot, and anything else — without Roll shipping a provider, without a `<queries>`
 * entry, and without the two apps having to agree on anything or be released together. The
 * cheapest integration is the one where neither app knows the other exists.
 *
 * Reads every image on the device rather than scoping to `DCIM`, matching Roll's own default:
 * scoping hid screenshots and anything another app had saved, and those read as photos
 * missing rather than as a deliberate omission.
 */
object PhotoLibrary {

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /** The read permission for this SDK level; they were renamed at 33. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Which days in the window have at least one photograph.
     *
     * Its keys are also the answer to "which days have photographs", so the mark, the cell
     * background and the day's photographic bookends all come from one query.
     */
    /**
     * A per-day summary for the planner: one photograph to draw, and when the day's photographs
     * started and stopped.
     *
     * The first and last are here rather than computed from a list of photographs because the
     * planner's window can be a year wide, and holding every row of a year to find two times per
     * day would be absurd. One pass over one cursor answers all of it.
     */
    data class DaySummary(
        val cover: DevicePhoto,
        val firstMinutes: Int,
        val lastMinutes: Int,
        val count: Int,
        /** Whether the cover is one you starred, rather than merely the earliest. */
        val coverStarred: Boolean = false,
    )

    fun summaries(
        context: Context,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /** File names starred in Roll. A starred photograph wins the cell over an earlier one. */
        starred: Set<String> = emptySet(),
    ): Map<Long, DaySummary> {
        val out = HashMap<Long, DaySummary>()
        query(context, fromDay, toDay, zone) { id, day, ms, name, w, h ->
            val photo = DevicePhoto(id, ContentUris.withAppendedId(collection, id), day, ms, name, w, h)
            val minutes = photo.minutesOfDay(zone)
            val existing = out[day]
            out[day] = if (existing == null) {
                DaySummary(photo, minutes, minutes, 1, name in starred)
            } else {
                val isStar = name in starred
                DaySummary(
                    // **A star beats the clock.** Otherwise the earliest of the day is the cover, so
                    // a cell shows the day starting; but if you went to the trouble of starring one,
                    // that is the picture of the day and it should be the one on the calendar. Among
                    // several starred, the earliest still wins, so the rule stays stable.
                    cover = when {
                        isStar && !existing.coverStarred -> photo
                        isStar == existing.coverStarred && ms < existing.cover.takenAt -> photo
                        else -> existing.cover
                    },
                    firstMinutes = minOf(existing.firstMinutes, minutes),
                    lastMinutes = maxOf(existing.lastMinutes, minutes),
                    count = existing.count + 1,
                    coverStarred = existing.coverStarred || isStar,
                )
            }
        }
        return out
    }

    /**
     * The journal day of the oldest photograph on the phone, or null when there are none.
     *
     * One row, sorted by the system rather than by us: this is asked to decide how far back it is
     * worth fetching weather for, and reading every image on the device to find the oldest would be
     * absurd for a single number.
     */
    fun earliestDay(context: Context, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!granted(context)) return null
        val projection = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // COALESCE because DATE_TAKEN is null for anything without EXIF, and DATE_ADDED is seconds.
        val order = "COALESCE(NULLIF(${MediaStore.Images.Media.DATE_TAKEN}, 0), " +
            "${MediaStore.Images.Media.DATE_ADDED} * 1000) ASC LIMIT 1"
        return runCatching {
            context.contentResolver.query(collection, projection, null, null, order)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val taken = c.getLong(0).takeIf { !c.isNull(0) }
                val added = c.getLong(1).takeIf { !c.isNull(1) }
                PhotoDays.instantMs(taken, added)?.let { PhotoDays.localEpochDay(it, zone) }
            }
        }.getOrNull()
    }

    /** Every photograph on one day, in the order they were taken. */
    fun photosOn(
        context: Context,
        epochDay: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /** File names starred in Roll, so the day knows which picture to give the big frame. */
        starred: Set<String> = emptySet(),
    ): List<DevicePhoto> {
        val out = mutableListOf<DevicePhoto>()
        query(context, epochDay, epochDay, zone) { id, day, ms, name, w, h ->
            out.add(
                DevicePhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    epochDay = day,
                    takenAt = ms,
                    name = name,
                    width = w,
                    height = h,
                    starred = name.isNotBlank() && name in starred,
                ),
            )
        }
        // Sorted here rather than in the query: the sort key is the *reconciled* instant, and
        // SQL cannot reconcile two columns in two units. A day holds few enough photographs
        // that this costs nothing.
        return out.sortedBy { it.takenAt }
    }

    /**
     * The one cursor both readers use.
     *
     * The `WHERE` clause is loose on purpose — it matches on either timestamp column — and
     * [PhotoDays.dayIfWithin] makes the real decision. A row whose `DATE_TAKEN` is 0 or was
     * written in seconds still has a correct `DATE_ADDED`, because MediaStore sets that one
     * itself; selecting on `DATE_TAKEN` alone would silently drop those photographs.
     */
    private inline fun query(
        context: Context,
        fromDay: Long,
        toDay: Long,
        zone: ZoneId,
        crossinline row: (id: Long, epochDay: Long, takenAt: Long, name: String, w: Int, h: Int) -> Unit,
    ) {
        if (!granted(context)) return
        val window = PhotoDays.windowMs(fromDay, toDay, zone)
        val startMs = window.first
        val endMs = window.last + 1
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val selection =
            "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?)" +
                " OR (${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)"
        val args = arrayOf(
            startMs.toString(),
            endMs.toString(),
            (startMs / 1000L).toString(),
            (endMs / 1000L).toString(),
        )

        runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val wCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext()) {
                    val taken = takenCol.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    val added = addedCol.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                    val day = PhotoDays.dayIfWithin(taken, added, fromDay, toDay, zone) ?: continue
                    val ms = PhotoDays.instantMs(taken, added) ?: continue
                    val name = nameCol.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                    val w = wCol.takeIf { it >= 0 }?.let { c.getLong(it).toInt() } ?: 0
                    val h = hCol.takeIf { it >= 0 }?.let { c.getLong(it).toInt() } ?: 0
                    row(c.getLong(idCol), day, ms, name, w, h)
                }
            }
        }
        // A revoked permission, an unmounted volume or a provider that died mid-query are all
        // "no photographs" as far as a calendar is concerned. None is worth a message.
    }

    /**
     * Thumbnails, cached.
     *
     * No image-loading library, matching Roll: `loadThumbnail` asks MediaStore for the
     * thumbnail it already has, returns it oriented, and costs a fraction of decoding a 12MP
     * JPEG. The cache is sized in **bytes** rather than in entries, because the whole reason
     * it exists is the heap on a phone with 4GB and a 3.92" panel.
     */
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Keyed on the id *and* the size asked for.
     *
     * The same photograph is drawn at two very different sizes — full width as a moment of a
     * day, small in a burst — and an id-only key hands back whichever was loaded first. That is
     * a burst thumbnail stretched across the screen, or a screen-sized bitmap held to draw a
     * frame a few millimetres wide.
     */
    fun thumbnail(context: Context, photo: DevicePhoto, edgePx: Int): Bitmap? {
        val key = photo.id.toString() + "@" + edgePx
        cache.get(key)?.let { return it }
        if (Build.VERSION.SDK_INT < 29) return null
        val bitmap = runCatching {
            context.contentResolver.loadThumbnail(photo.uri, Size(edgePx, edgePx), null)
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** Dropped when the library changes underneath us, so a deleted photo stops being drawn. */
    fun clearCache() = cache.evictAll()
}
