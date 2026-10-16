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
     * Last-resort fallback only: callers must only invoke this when the playlist
     * has no own cover (`coverUrl` blank and no usable local file), otherwise
     * the playlist's own artwork would be shadowed by a track's artwork after
     * restart (local file is preferred by `coverImageModel`).
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
     * Saves raw image bytes as the playlist's own cover.
     *
     * Used to persist the playlist's own `coverUrl` for offline display, so the
     * playlist never needs to borrow a track's artwork.
     *
     * @param playlistId the playlist identifier.
     * @param bytes the JPEG/PNG bytes to save.
     * @return the absolute path to the saved playlist cover, or null on failure.
     */
    fun savePlaylistCoverBytes(playlistId: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return try {
            val dest = File(coversDir, "playlist_$playlistId.jpg")
            dest.writeBytes(bytes)
            Timber.i("ArtworkPersistence: saved ${bytes.size} bytes for playlist $playlistId")
            dest.absolutePath
        } catch (t: Exception) {
            Timber.e(t, "ArtworkPersistence: FAILED to save playlist cover for $playlistId")
            null
        }
    }

    /**
     * Downloads the playlist's own `coverUrl` and persists it for offline display.
     *
     * @param playlistId the playlist identifier.
     * @param coverUrl the playlist's own remote artwork URL.
     * @param fetcher downloads URL bytes; injectable so JVM tests avoid network.
     * @return the absolute path to the saved playlist cover, or null on failure.
     */
    fun persistPlaylistCoverFromUrl(
        playlistId: String,
        coverUrl: String,
        fetcher: (String) -> ByteArray? = ::fetchUrlBytes,
    ): String? {
        if (coverUrl.isBlank()) return null
        return try {
            val bytes = fetcher(coverUrl.trim())?.takeIf { it.isNotEmpty() } ?: return null
            savePlaylistCoverBytes(playlistId, bytes)
        } catch (t: Exception) {
            Timber.e(t, "ArtworkPersistence: FAILED to download playlist cover for $playlistId")
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

    /**
     * Returns whether the given local playlist cover path is usable.
     *
     * A stale DB path (file deleted out from under Room) must never shadow a
     * good remote URL, mirroring `coverImageModel` precedence.
     */
    fun playlistLocalUsable(localPath: String?): Boolean {
        if (localPath.isNullOrBlank()) return false
        return try {
            val f = File(localPath.trim())
            f.isFile && f.exists()
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Downloads raw bytes for an artwork URL with short timeouts.
 *
 * Never throws: any failure returns `null` so a playlist-cover persist can fall
 * back to the remote URL display instead of failing the download run.
 */
internal fun fetchUrlBytes(url: String): ByteArray? = try {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    conn.instanceFollowRedirects = true
    conn.connect()
    if (conn.responseCode !in 200..299) {
        null
    } else {
        conn.inputStream.use { it.readBytes().takeIf { bytes -> bytes.isNotEmpty() } }
    }
} catch (_: Exception) {
    null
}
