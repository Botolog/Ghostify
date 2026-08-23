package xyz.botolog.ghostify.data.model

/**
 * Download lifecycle of a single track within a playlist.
 *
 * State transitions follow a finite state machine:
 * ```
 *   PENDING → QUEUED → DOWNLOADING → DOWNLOADED
 *                ↓          ↓
 *             CANCELED    FAILED → (retry) → QUEUED
 *                            ↓
 *                         REMOVED
 * ```
 * The download manager owns all transitions; repositories never modify status
 * directly except for batch resets during re-sync.
 */
enum class SongStatus {
    /** Newly added, not queued for download yet. */
    PENDING,

    /** Enqueued into the download queue but download has not started. */
    QUEUED,

    /** Currently being downloaded. */
    DOWNLOADING,

    /** MP3 stored on disk; [SongEntity.filePath][xyz.botolog.ghostify.data.db.entity.SongEntity.filePath] is non-null. */
    DOWNLOADED,

    /** Download failed (retryable). */
    FAILED,

    /** User cancelled the download; recoverable back to [PENDING]. */
    CANCELED,

    /** Track removed from the Spotify playlist or deleted from disk. */
    REMOVED;
}
