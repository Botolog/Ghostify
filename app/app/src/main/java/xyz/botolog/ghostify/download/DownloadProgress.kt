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
    /**
     * Count of songs that are actually DOWNLOADED (a strict subset of [done]:
     * FAILED and CANCELED are terminal but not downloaded).
     *
     * Precomputed here so UI layers can show a downloaded count without an
     * additional O(N) pass over [perSong] on every emission. Additive and
     * defaulted so existing construction sites stay source-compatible.
     */
    val downloaded: Int = 0,
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
        var done = 0
        var downloaded = 0
        // Single pass: no intermediate Pair allocations and no second scan for
        // the downloaded count (progress is emitted thousands of times per run).
        val perSong = LinkedHashMap<String, SongProgress>(total)
        for (song in songs) {
            if (song.status.isTerminal) done++
            if (song.status == DownloadStatus.DOWNLOADED) downloaded++
            perSong[song.id] = buildSongProgress(song, fractions)
        }
        val overallPercent = computeOverallPercent(total, done)
        return DownloadProgress(
            playlistId = playlistId,
            state = state,
            total = total,
            done = done,
            downloaded = downloaded,
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
    ): SongProgress {
        val fraction = fractions[song.id]
            ?: if (song.status == DownloadStatus.DOWNLOADED) FULL_FRACTION else ZERO_FRACTION
        return SongProgress(
            songId = song.id,
            status = song.status,
            fraction = fraction,
            error = song.error,
        )
    }
}

/**
 * Incremental, in-memory [DownloadProgress] source for one playlist run.
 *
 * The queue runner keeps a single instance alive for the whole run and mutates
 * it as each status transition happens, so emitting a snapshot never has to
 * re-read the playlist from the database and re-sort it (the old O(N²) pattern
 * did exactly that twice per track). The full playlist is loaded once at the
 * start of the run; after that everything is derived from memory.
 *
 * Mutations and snapshot building are guarded by a single lock so the bounded
 * worker pool can update it concurrently and every snapshot is internally
 * consistent. Snapshot construction is cheap (a map build over already-computed
 * [SongProgress] values, no database access and no sorting).
 *
 * @param playlistId the playlist being tracked.
 * @param songs the full playlist song list, loaded once when the run starts.
 */
internal class DownloadProgressTracker(
    private val playlistId: String,
    songs: List<SongRecord>,
) {

    private val lock = Any()
    private val songs = songs.toMutableList()
    private val indexById = HashMap<String, Int>(songs.size).apply {
        songs.forEachIndexed { index, song -> put(song.id, index) }
    }

    /** Marks one song's status (and optional error) in the in-memory model. */
    fun markStatus(songId: String, status: DownloadStatus, error: String? = null) {
        synchronized(lock) {
            val index = indexById[songId] ?: return
            songs[index] = songs[index].copy(status = status, error = error)
        }
    }

    /** Marks several songs as QUEUED (mirrors `setStatuses(ids, QUEUED)`). */
    fun markQueued(songIds: Collection<String>) {
        synchronized(lock) {
            songIds.forEach { id ->
                val index = indexById[id] ?: return@forEach
                songs[index] = songs[index].copy(status = DownloadStatus.QUEUED)
            }
        }
    }

    /**
     * Applies a finished [TrackDownloadResult], mirroring exactly the status and
     * error the queue runner just wrote to the database for that song.
     */
    fun applyResult(songId: String, result: TrackDownloadResult) {
        when {
            result.error is DownloadError.Canceled ->
                markStatus(songId, DownloadStatus.CANCELED, result.error.message)
            result.isSuccess ->
                markStatus(songId, DownloadStatus.DOWNLOADED, null)
            else ->
                markStatus(songId, DownloadStatus.FAILED, result.error?.message)
        }
    }

    /**
     * Mirrors the queue runner's cancellation cleanup: in-flight songs become
     * CANCELED and still-queued songs go back to PENDING, so the final canceled
     * snapshot matches what was just persisted.
     */
    fun applyCancelCleanup(cancelErrorMessage: String) {
        synchronized(lock) {
            for (index in songs.indices) {
                val song = songs[index]
                songs[index] = when (song.status) {
                    DownloadStatus.DOWNLOADING ->
                        song.copy(status = DownloadStatus.CANCELED, error = cancelErrorMessage)
                    DownloadStatus.QUEUED ->
                        song.copy(status = DownloadStatus.PENDING)
                    else -> song
                }
            }
        }
    }

    /** Builds an immutable snapshot from the current in-memory state. */
    fun snapshot(state: DownloadRunState): DownloadProgress = synchronized(lock) {
        DownloadProgressCalculator.fromSongs(playlistId, songs, state)
    }
}
