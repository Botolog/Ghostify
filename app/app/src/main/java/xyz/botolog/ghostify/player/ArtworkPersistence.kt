package xyz.botolog.ghostify.player

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Persists extracted cover art to disk for offline access.
 *
 * Artwork is extracted from downloaded MP3 files, downscaled to 1280px JPEG
 * (~80KB each), and saved to the app's internal files directory under `covers/`.
 */
class ArtworkPersistence(context: Context) {

    private val coversDir: File = File(context.filesDir, "covers").also { it.mkdirs() }

    /**
     * Extracts artwork from an MP3, downscales it, and saves to disk.
     *
     * @param songId unique song identifier (used as filename).
     * @param filePath absolute path to the MP3 file.
     * @return the absolute path to the saved JPEG, or null on failure.
     */
    fun persist(songId: String, filePath: String): String? {
        val extractor = MediaMetadataRetrieverArtworkExtractor()
        val bytes = extractor.extractAndDownscale(filePath) ?: return null
        return try {
            val file = File(coversDir, "$songId.jpg")
            file.writeBytes(bytes)
            Timber.i("ArtworkPersistence: saved ${bytes.size} bytes for song $songId")
            file.absolutePath
        } catch (t: Exception) {
            Timber.e(t, "ArtworkPersistence: FAILED to save for song $songId")
            null
        }
    }

    /**
     * Returns the cover art file for a song, or null if not cached.
     */
    fun coverFor(songId: String): File? {
        val file = File(coversDir, "$songId.jpg")
        return if (file.exists()) file else null
    }

    /**
     * Copies a song's cover art as the playlist cover.
     *
     * @param playlistId the playlist identifier.
     * @param songId the song whose cover to copy.
     * @return the absolute path to the saved playlist cover, or null.
     */
    fun persistPlaylistCover(playlistId: String, songId: String): String? {
        val source = File(coversDir, "$songId.jpg")
        if (!source.exists()) return null
        return try {
            val dest = File(coversDir, "playlist_$playlistId.jpg")
            source.copyTo(dest, overwrite = true)
            Timber.i("ArtworkPersistence: copied cover for playlist $playlistId from song $songId")
            dest.absolutePath
        } catch (t: Exception) {
            Timber.e(t, "ArtworkPersistence: FAILED to copy playlist cover for $playlistId")
            null
        }
    }

    /**
     * Deletes the cover art file for a song.
     */
    fun delete(songId: String) {
        File(coversDir, "$songId.jpg").delete()
    }

    /**
     * Deletes the cover art file for a playlist.
     */
    fun deletePlaylistCover(playlistId: String) {
        File(coversDir, "playlist_$playlistId.jpg").delete()
    }
}
