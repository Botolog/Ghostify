package com.ghostify.viewmodel

/**
 * Domain models exposed by the ViewModels. These mirror PROJECT.md §4 (the
 * Room `playlists` / `songs` tables) but are deliberately defined inside this
 * component so the whole state layer can be driven by fakes on the JVM. The
 * app maps its Room entities onto these types via the repository adapter.
 */

/** Lifecycle state of a saved playlist (PROJECT.md §4). */
enum class PlaylistStatus {
    NEW,
    READY,
    DOWNLOADING,
    ERROR,
}

/** Download lifecycle of a single track (PROJECT.md §4). */
enum class SongStatus {
    PENDING,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    REMOVED,
}

/** A saved playlist as shown in the Library and Playlist screens. */
data class PlaylistSummary(
    val id: String,
    val spotifyId: String,
    val name: String,
    val owner: String? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val status: PlaylistStatus = PlaylistStatus.NEW,
    val createdAt: Long,
    val lastSyncedAt: Long? = null,
)

/** A track belonging to a playlist. */
data class Track(
    val id: String,
    val playlistId: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val durationMs: Int = 0,
    val coverUrl: String? = null,
    val filePath: String? = null,
    val status: SongStatus = SongStatus.PENDING,
    val position: Int = 0,
)

// ---------------------------------------------------------------------------
// Download progress (streamed from the DownloadManager, PROJECT.md §6.1).
// ---------------------------------------------------------------------------

/** High-level state of one download run. */
enum class DownloadRunState {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELED,
}

/** Per-song progress as surfaced to the UI. */
data class SongProgress(
    val songId: String,
    val status: SongStatus,
    /** 0f..1f fraction of the current track downloaded. */
    val fraction: Float = 0f,
    val error: String? = null,
)

/**
 * Progress payload streamed to the UI. `overallPercent` is `done / total`
 * including FAILED songs (PROJECT.md §6.1).
 */
data class DownloadProgress(
    val playlistId: String,
    val state: DownloadRunState,
    val total: Int,
    val done: Int,
    val overallPercent: Float,
    val perSong: Map<String, SongProgress> = emptyMap(),
)
