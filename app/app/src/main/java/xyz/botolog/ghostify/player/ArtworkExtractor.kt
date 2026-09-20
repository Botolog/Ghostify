package xyz.botolog.ghostify.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Extracts embedded album art (front cover) from an audio file's tags.
 *
 * `spotdl` embeds the Spotify cover into each MP3, so the artwork comes straight from the
 * local file and no network is involved. Abstracted as a [fun interface] so the controller
 * is testable with a fake extractor.
 */
fun interface ArtworkExtractor {

    /**
     * Extracts embedded artwork from the audio file at [filePath].
     *
     * @param filePath absolute path to the audio file.
     * @return the embedded picture bytes (typically JPEG), or null if none / unreadable.
     */
    fun extractArtwork(filePath: String): ByteArray?
}

/**
 * Default implementation backed by `android.media.MediaMetadataRetriever`.
 *
 * Always defensive: a corrupt/truncated file must never crash queue building, so any failure
 * is swallowed and reported as "no artwork".
 */
class MediaMetadataRetrieverArtworkExtractor : ArtworkExtractor {

    override fun extractArtwork(filePath: String): ByteArray? {
        Timber.i("ArtworkExtractor.extractArtwork: START filePath=$filePath")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val picture = retriever.embeddedPicture
            Timber.i("ArtworkExtractor.extractArtwork: returning ${picture?.size} bytes")
            picture
        } catch (t: RuntimeException) {
            Timber.e(t, "ArtworkExtractor: extractArtwork FAILED")
            null
        } catch (t: Exception) {
            Timber.e(t, "ArtworkExtractor: extractArtwork FAILED")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Extracts embedded artwork and downscales to [maxWidth] pixels wide.
     *
     * Returns compressed JPEG bytes suitable for caching on disk, or null if
     * no artwork is found or extraction fails.
     */
    fun extractAndDownscale(filePath: String, maxWidth: Int = 1280, quality: Int = 80): ByteArray? {
        val raw = extractArtwork(filePath) ?: return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)

            val sampleSize = calculateInSampleSize(options, maxWidth)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOptions) ?: return null

            val scaled = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val scaledHeight = (bitmap.height * ratio).toInt()
                val result = Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
                if (result !== bitmap) bitmap.recycle()
                result
            } else {
                bitmap
            }

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            scaled.recycle()
            output.toByteArray()
        } catch (t: Exception) {
            Timber.e(t, "ArtworkExtractor: extractAndDownscale FAILED")
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqWidth || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqWidth && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
