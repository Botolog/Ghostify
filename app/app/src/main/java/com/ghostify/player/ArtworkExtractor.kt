package com.ghostify.player

import android.media.MediaMetadataRetriever

/**
 * Extracts embedded album art (front cover) from an audio file's tags.
 *
 * `spotdl` embeds the Spotify cover into each MP3, so the artwork comes straight from the
 * local file and no network is involved. Abstracted as a [fun interface] so the controller
 * is testable with a fake extractor.
 */
fun interface ArtworkExtractor {
    /** @return the embedded picture bytes (typically JPEG), or null if none / unreadable. */
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
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.embeddedPicture
        } catch (_: RuntimeException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
