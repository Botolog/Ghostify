package xyz.botolog.ghostify.download

/**
 * Per-track download lifecycle status (mirrors `songs.status` in the Room schema,
 * PROJECT.md §4). Transition legality is enforced by [SongStateMachine].
 *
 *   PENDING -> QUEUED -> DOWNLOADING -> DOWNLOADED | FAILED | CANCELED
 */
enum class DownloadStatus {
    /** Initial state; track has not been queued yet. */
    PENDING,

    /** Track is enqueued and waiting for a concurrency slot. */
    QUEUED,

    /** Track is actively being downloaded. */
    DOWNLOADING,

    /** Track was successfully downloaded. Terminal state. */
    DOWNLOADED,

    /** Track download failed. Terminal state but eligible for retry. */
    FAILED,

    /** Track download was canceled by the user. Terminal state but recoverable. */
    CANCELED;

    /** A track in a terminal state will never be re-enqueued by `downloadAll`. */
    val isTerminal: Boolean
        get() = this == DOWNLOADED || this == FAILED || this == CANCELED

    /**
     * A track left in this state by a previous (possibly crashed) run can be
     * safely reset to [PENDING] — it was never durably downloaded.
     */
    val isRecoverable: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == CANCELED

    /** States `downloadAll` is allowed to pick up. */
    val isDownloadable: Boolean
        get() = this == PENDING || this == FAILED
}

/**
 * Playlist-level status (mirrors `playlists.status`, PROJECT.md §4).
 *
 * Tracks the aggregate state of a playlist's download run.
 */
enum class PlaylistStatus {
    /** Playlist was just imported; no download run has started yet. */
    NEW,

    /** Playlist is idle; all tracks are in a stable state. */
    READY,

    /** A download run is in progress for this playlist. */
    DOWNLOADING,

    /** Playlist encountered an error during sync or download. */
    ERROR,
}
