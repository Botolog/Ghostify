package xyz.botolog.ghostify.data.model

/** Download lifecycle of a single track within a playlist. */
enum class SongStatus {
    /** Newly added, not queued yet. */
    PENDING,

    /** Enqueued into the download queue but not started. */
    QUEUED,

    /** Currently being downloaded. */
    DOWNLOADING,

    /** MP3 on disk; `file_path` is non-null. */
    DOWNLOADED,

    /** Download failed (retryable). */
    FAILED,

    /** User cancelled the download; recoverable back to PENDING. */
    CANCELED,

    /** Track removed from the Spotify playlist / deleted from disk. */
    REMOVED
}
