package xyz.botolog.ghostify.download

/** High-level state of a download run, surfaced in the UI. */
enum class DownloadRunState {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELED,
}

/** Per-song progress as seen by the UI. */
data class SongProgress(
    val songId: String,
    val status: DownloadStatus,
    /** 0f..1f fraction of the current track that has been downloaded. */
    val fraction: Float = 0f,
    val error: String? = null,
)

/** The progress payload streamed to the UI via a `Flow<DownloadProgress>`. */
data class DownloadProgress(
    val playlistId: String,
    val state: DownloadRunState,
    val total: Int,
    /** Count of songs in a terminal state (DOWNLOADED + FAILED + CANCELED). */
    val done: Int,
    /** 0f..100f. done / total, FAILED counts as done (T-046). */
    val overallPercent: Float,
    val playlistStatus: PlaylistStatus,
    val perSong: Map<String, SongProgress>,
)

/**
 * Pure function used to derive [DownloadProgress] from a repository snapshot.
 * Because the overall % is always recomputed from the database's authoritative
 * statuses, it stays correct across process death and partial failures.
 */
object DownloadProgressCalculator {

    fun fromSongs(
        playlistId: String,
        songs: List<SongRecord>,
        state: DownloadRunState = DownloadRunState.IDLE,
        playlistStatus: PlaylistStatus = if (state == DownloadRunState.RUNNING) {
            PlaylistStatus.DOWNLOADING
        } else {
            PlaylistStatus.READY
        },
        fractions: Map<String, Float> = emptyMap(),
    ): DownloadProgress {
        val total = songs.size
        val done = songs.count { it.status.isTerminal }
        val overallPercent = if (total == 0) 100f else done * 100f / total
        val perSong = songs.associate { s ->
            s.id to SongProgress(
                songId = s.id,
                status = s.status,
                fraction = fractions[s.id] ?: if (s.status == DownloadStatus.DOWNLOADED) 1f else 0f,
                error = s.error,
            )
        }
        return DownloadProgress(
            playlistId = playlistId,
            state = state,
            total = total,
            done = done,
            overallPercent = overallPercent,
            playlistStatus = playlistStatus,
            perSong = perSong,
        )
    }
}
