package xyz.botolog.ghostify.recovery

/** Mirror of the app's song status values (see PROJECT.md §4). */
enum class SongStatus {
    /** Download not yet started. */
    PENDING,
    /** Download enqueued but not yet running. */
    QUEUED,
    /** Download currently in progress. */
    DOWNLOADING,
    /** Download completed successfully. */
    DOWNLOADED,
    /** Download failed. */
    FAILED,
    /** Song was removed from the playlist. */
    REMOVED,
}

/** Mirror of the app's playlist status values (see PROJECT.md §4). */
enum class PlaylistStatus {
    /** New playlist, not yet synced. */
    NEW,
    /** Playlist synced and ready for download. */
    READY,
    /** Download currently in progress. */
    DOWNLOADING,
    /** An error occurred during sync or download. */
    ERROR,
}

/**
 * Minimal snapshot of one song row needed by the recovery logic.
 *
 * @param id the song's unique identifier.
 * @param status the song's current status.
 */
data class SongState(val id: String, val status: SongStatus)

/**
 * Minimal snapshot of one playlist row needed by the recovery logic.
 *
 * @param id the playlist's unique identifier.
 * @param status the playlist's current status.
 * @param downloadedCount number of songs in this playlist that are DOWNLOADED.
 */
data class PlaylistState(val id: String, val status: PlaylistStatus, val downloadedCount: Int)

/**
 * A song that was in-flight (QUEUED or DOWNLOADING) when the process died.
 *
 * @param id the song's unique identifier to reset.
 */
data class SongReset(val id: String)

/**
 * A playlist that was mid-download when the process died, and its consistent target state.
 *
 * @param id the playlist's unique identifier to reset.
 * @param toStatus the target status to set.
 */
data class PlaylistReset(val id: String, val toStatus: PlaylistStatus)

/**
 * The complete, minimal set of state resets a killed-process recovery should apply.
 *
 * @param songResets songs to reset back to PENDING.
 * @param playlistResets playlists to reset to a consistent status.
 */
data class RecoveryPlan(
    val songResets: List<SongReset> = emptyList(),
    val playlistResets: List<PlaylistReset> = emptyList(),
) {
    /** True when no resets are needed (idempotency check). */
    val isEmpty: Boolean get() = songResets.isEmpty() && playlistResets.isEmpty()
}

/**
 * Result of a recovery run, for logging and UI signaling.
 *
 * @param plan the recovery plan that was applied.
 * @param timestampMs epoch milliseconds when the recovery ran.
 */
data class RecoveryReport(
    val plan: RecoveryPlan,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** Number of songs that were reset. */
    val resetSongCount: Int get() = plan.songResets.size

    /** Number of playlists that were reset. */
    val resetPlaylistCount: Int get() = plan.playlistResets.size
}
