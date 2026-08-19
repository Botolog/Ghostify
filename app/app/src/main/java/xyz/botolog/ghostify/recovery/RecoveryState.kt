package xyz.botolog.ghostify.recovery

/** Mirror of the app's song status values (see PROJECT.md §4). */
enum class SongStatus {
    PENDING,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    REMOVED,
}

/** Mirror of the app's playlist status values (see PROJECT.md §4). */
enum class PlaylistStatus {
    NEW,
    READY,
    DOWNLOADING,
    ERROR,
}

/** Minimal snapshot of one song row needed by the recovery logic. */
data class SongState(val id: String, val status: SongStatus)

/** Minimal snapshot of one playlist row needed by the recovery logic. */
data class PlaylistState(val id: String, val status: PlaylistStatus, val downloadedCount: Int)

/** A song that was in-flight (QUEUED or DOWNLOADING) when the process died. */
data class SongReset(val id: String)

/** A playlist that was mid-download when the process died, and its consistent target state. */
data class PlaylistReset(val id: String, val toStatus: PlaylistStatus)

/** The complete, minimal set of state resets a killed-process recovery should apply. */
data class RecoveryPlan(
    val songResets: List<SongReset> = emptyList(),
    val playlistResets: List<PlaylistReset> = emptyList(),
) {
    val isEmpty: Boolean get() = songResets.isEmpty() && playlistResets.isEmpty()
}

/** Result of a recovery run, for logging and UI signaling. */
data class RecoveryReport(
    val plan: RecoveryPlan,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val resetSongCount: Int get() = plan.songResets.size
    val resetPlaylistCount: Int get() = plan.playlistResets.size
}
