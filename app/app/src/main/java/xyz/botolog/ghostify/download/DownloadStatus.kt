package xyz.botolog.ghostify.download

/**
 * Per-track download lifecycle status (mirrors `songs.status` in the Room schema,
 * PROJECT.md §4). Transition legality is enforced by [SongStateMachine].
 *
 *   PENDING -> QUEUED -> DOWNLOADING -> DOWNLOADED | FAILED | CANCELED
 */
enum class DownloadStatus {
    PENDING,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
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

/** Playlist-level status (mirrors `playlists.status`, PROJECT.md §4). */
enum class PlaylistStatus {
    NEW,
    READY,
    DOWNLOADING,
    ERROR
}
