package xyz.botolog.ghostify.data.model

/**
 * Lifecycle state of a saved playlist.
 *
 * Drives UI display and download-manager behaviour. Transitions are:
 * ```
 *   NEW → DOWNLOADING → READY
 *              ↓
 *           ERROR → (retry) → DOWNLOADING
 * ```
 */
enum class PlaylistStatus {
    /** Metadata saved, tracks fetched, nothing downloaded yet. */
    NEW,

    /** All tracks present on disk. */
    READY,

    /** A batch of downloads is currently running for this playlist. */
    DOWNLOADING,

    /** A previous sync/download attempt failed partway. */
    ERROR;
}
