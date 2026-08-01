package com.ghostify.data.model

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

    /** Track removed from the Spotify playlist / deleted from disk. */
    REMOVED
}
