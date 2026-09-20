package xyz.botolog.ghostify.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import xyz.botolog.ghostify.data.model.PlaylistOrigin

/**
 * UI-facing state models for the Ghostify screens.
 *
 * These deliberately mirror (but do not duplicate) the Room entities in the `database`
 * component — they are the immutable, render-ready projection the ViewModels expose.
 * Keeping them here lets the UI component compile and be tested against plain Kotlin
 * fakes without any Android/Database dependency.
 */

/** Status of a playlist as shown on the Library screen. */
enum class PlaylistStatus { NEW, READY, DOWNLOADING, ERROR }

/** Status of an individual song as shown on the Playlist detail screen. */
enum class SongStatus { PENDING, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/**
 * A playlist row on the Library screen.
 *
 * @property id database primary key.
 * @property name display name of the playlist.
 * @property owner owner / creator name.
 * @property coverUrl URL of the cover image, or `null` if unavailable.
 * @property trackCount total number of tracks in the playlist.
 * @property downloadedCount number of tracks with a local file.
 * @property status current download / sync status.
 * @property origin platform the playlist originates from — drives row tinting.
 * @property progressPercent explicit overall download progress `0..100`, or `null` when idle.
 * @property lastSyncedAt epoch millis of the last successful sync, or `null` if never synced.
 */
@Stable
data class PlaylistUi(
    val id: String,
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val coverArtLocalPath: String? = null,
    val trackCount: Int,
    val downloadedCount: Int,
    val status: PlaylistStatus,
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
    val progressPercent: Int? = null,
    val lastSyncedAt: Long? = null,
)

/**
 * A single track row on the Playlist detail screen.
 *
 * @property id database primary key.
 * @property spotifyId Spotify track id.
 * @property title track title.
 * @property artists comma-separated artist names.
 * @property album album name, or empty if unknown.
 * @property durationMs track duration in milliseconds.
 * @property status download / playback status.
 * @property position zero-based sort position within the playlist.
 */
@Stable
data class TrackUi(
    val id: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val status: SongStatus,
    val position: Int,
    val coverUrl: String? = null,
    val coverArtLocalPath: String? = null,
)

/**
 * Metadata preview shown in the Add-playlist dialog before saving.
 *
 * @property name playlist name.
 * @property owner owner / creator name.
 * @property coverUrl URL of the cover image, or `null` if unavailable.
 * @property trackCount number of tracks in the playlist.
 */
data class PlaylistPreview(
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val trackCount: Int,
)

/**
 * A single item in the Player's queue drawer.
 *
 * @property title track title.
 * @property artist artist name.
 * @property durationMs track duration in milliseconds.
 * @property isCurrent `true` if this is the currently playing item.
 */
data class QueueItem(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isCurrent: Boolean = false,
)

/**
 * The now-playing track on the Player screen.
 *
 * @property title track title.
 * @property artist artist name.
 * @property album album name.
 * @property coverUrl artwork — may be a `ByteArray` (embedded) or `String` (HTTP URL).
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: Any?,
    val id: String = "",
    val spotifyId: String = "",
    val ytId: String? = null,
    val filePath: String? = null,
    val status: String = "",
    val error: String? = null,
    val lyrics: String? = null,
    val durationMs: Long = 0L,
    val position: Int = 0,
    val lyricsSource: String? = null,
    val lyricsEdited: Boolean = false,
    val ytUrl: String? = null,
    val ytName: String? = null,
    val ytChannel: String? = null,
    val bitrate: Int? = null,
    val fileSize: Long? = null,
    val downloadedAt: Long? = null,
)

/**
 * Cache statistics shown on the Settings screen.
 *
 * @property fileCount number of orphan files in the cache.
 * @property sizeBytes total size of orphan files in bytes.
 */
data class CacheStats(
    val fileCount: Int,
    val sizeBytes: Long,
) {
    /** Human-readable size string (e.g. "12.3 MB" or "456 KB"). */
    val sizeText: String
        get() {
            val mb = sizeBytes / BYTES_PER_MB
            return if (mb >= 1) SIZE_MB_FORMAT.format(mb) else "${sizeBytes / BYTES_PER_KB} KB"
        }

    companion object {
        /** Conversion factor from bytes to megabytes. */
        private const val BYTES_PER_MB = 1024.0 * 1024.0

        /** Conversion factor from bytes to kilobytes. */
        private const val BYTES_PER_KB = 1024

        /** Format string for megabyte values with one decimal place. */
        private const val SIZE_MB_FORMAT = "%.1f MB"
    }
}

/**
 * Download bitrate options (kbps).
 *
 * @property kbps numeric bitrate in kilobits per second.
 * @property label human-readable label for the UI.
 */
enum class Bitrate(val kbps: Int, val label: String) {
    LOW(128, "128 kbps"),
    MEDIUM(192, "192 kbps"),
    HIGH(320, "320 kbps"),
}
