package xyz.botolog.ghostify.download

/** High-level state of a download run, surfaced in the UI. */
enum class DownloadRunState {
    /** No download run is active. */
    IDLE,

    /** A download run is in progress. */
    RUNNING,

    /** The download run finished successfully. */
    COMPLETED,

    /** The download run was canceled by the user. */
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

    private const val ZERO_FRACTION = 0f
    private const val FULL_FRACTION = 1f
    private const val PERCENT_MULTIPLIER = 100f

    /**
     * Computes a [DownloadProgress] snapshot from the given songs.
     *
     * @param playlistId the playlist being tracked.
     * @param songs the current list of songs from the repository.
     * @param state the current run state.
     * @param playlistStatus the playlist-level status to report.
     * @param fractions per-song download fractions (0f..1f), if known.
     * @return a fully computed [DownloadProgress].
     */
    fun fromSongs(
        playlistId: String,
        songs: List<SongRecord>,
        state: DownloadRunState = DownloadRunState.IDLE,
        playlistStatus: PlaylistStatus = deriveDefaultPlaylistStatus(state),
        fractions: Map<String, Float> = emptyMap(),
    ): DownloadProgress {
        val total = songs.size
        val done = songs.count { it.status.isTerminal }
        val overallPercent = computeOverallPercent(total, done)
        val perSong = songs.associate { song -> buildSongProgress(song, fractions) }
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

    private fun deriveDefaultPlaylistStatus(state: DownloadRunState): PlaylistStatus =
        if (state == DownloadRunState.RUNNING) {
            PlaylistStatus.DOWNLOADING
        } else {
            PlaylistStatus.READY
        }

    private fun computeOverallPercent(total: Int, done: Int): Float =
        if (total == 0) PERCENT_MULTIPLIER else done * PERCENT_MULTIPLIER / total

    private fun buildSongProgress(
        song: SongRecord,
        fractions: Map<String, Float>,
    ): Pair<String, SongProgress> {
        val fraction = fractions[song.id]
            ?: if (song.status == DownloadStatus.DOWNLOADED) FULL_FRACTION else ZERO_FRACTION
        return song.id to SongProgress(
            songId = song.id,
            status = song.status,
            fraction = fraction,
            error = song.error,
        )
    }
}
