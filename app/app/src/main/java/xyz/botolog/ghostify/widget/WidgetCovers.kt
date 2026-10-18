package xyz.botolog.ghostify.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Bounded cover decoding for the "Playlists" widget's tiles.
 *
 * The player-artwork path may carry one full-size cover; the tile grid is the one place a
 * widget carries **several** covers, so each is first decoded with a power-of-two
 * `inSampleSize` and then scaled down to [WIDGET_TILE_COVER_MAX_PX] before being encoded
 * to a small JPEG. Four ~128px JPEGs cost a few tens of KB instead of several MB of
 * raw bitmaps — the reason the grid can safely show art at all.
 *
 * Only **local files** are read here (the playlist's `coverArtLocalPath`); no network, no
 * coil, and a missing/unreadable file simply yields `null` (the tile falls back to the
 * placeholder glyph).
 */

/** Longest side, in px, of a tile cover sent to the launcher. */
internal const val WIDGET_TILE_COVER_MAX_PX: Int = 128

/** JPEG quality for the re-encoded tile cover. */
private const val TILE_COVER_JPEG_QUALITY = 85

/**
 * Power-of-two `inSampleSize` that brings a [width]×[height] image to at/below
 * [targetPx] on its longer side. Pure and JVM-tested.
 */
internal fun coverSampleSize(width: Int, height: Int, targetPx: Int): Int {
    val target = targetPx.coerceAtLeast(1)
    var w = width.coerceAtLeast(1)
    var h = height.coerceAtLeast(1)
    var sample = 1
    while (w / 2 >= target || h / 2 >= target) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

/**
 * Reads [path], decodes it downscaled and returns small JPEG bytes, or `null` when the
 * file is absent/unreadable/undecodable. Never throws.
 */
internal fun readSmallCoverBytes(path: String?, targetPx: Int = WIDGET_TILE_COVER_MAX_PX): ByteArray? {
    val file = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val options = BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
        val scaled = scaleToFit(decoded, targetPx)
        try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, TILE_COVER_JPEG_QUALITY, out)
            out.toByteArray()
        } finally {
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
        }
    }.getOrNull()
}

/** Scales [bitmap] so neither side exceeds [maxPx]; returns it unchanged when it already fits. */
private fun scaleToFit(bitmap: Bitmap, maxPx: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxPx || longest <= 0) return bitmap
    val ratio = maxPx.toFloat() / longest.toFloat()
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
