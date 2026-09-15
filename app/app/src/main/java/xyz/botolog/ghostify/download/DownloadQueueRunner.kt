package xyz.botolog.ghostify.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Outcome of a download run.
 */
enum class RunOutcome {
    /** Nothing to download. */
    IDLE,

    /** Every selected song was processed. */
    COMPLETED,

    /** Run was canceled by the user (or WorkManager) mid-flight. */
    CANCELED,
}

/** Internal signal for a cooperative, user-requested cancel. */
internal class UserCanceledException : Exception("Download canceled by user")

/**
 * The core, Android-free state machine that drives a per-playlist download run.
 *
 * Responsibilities:
 *  - crash recovery: any QUEUED/DOWNLOADING/CANCELED leftovers from a previous
 *    (possibly killed) run are reset to PENDING before picking up work (T-051);
 *  - selection: only PENDING + FAILED, in playlist order (T-042/043/045);
 *  - transitions: PENDING -> QUEUED -> DOWNLOADING -> DOWNLOADED | FAILED, via
 *    [SongStateMachine], never an invalid jump (T-044);
 *  - partial failure isolation: one bad track -> FAILED, the run continues (T-047);
 *  - cancellation: the current track becomes CANCELED, everything still QUEUED is
 *    put back to PENDING and the run ends -> no zombie work (T-049);
 *  - progress: emits a [DownloadProgress] after every state change and forwards
 *    per-track fractions (T-046).
 *
 * Runs under whichever coroutine calls it (the WorkManager worker in production,
 * a test scope on the JVM). Pure Kotlin + coroutines: no Android APIs.
 *
 * @param repo the repository for reading and writing song/playlist state.
 * @param downloader the single-track downloader.
 * @param concurrencySupplier callable that returns the current concurrency limit.
 *   Called at the start of each run so runtime settings changes take effect
 *   immediately without restarting the app.
 */
class DownloadQueueRunner(
    private val repo: DownloadRepository,
    private val downloader: TrackDownloader,
    private val concurrencySupplier: () -> Int = { 1 },
) {

    /**
     * Convenience constructor for tests (fixed concurrency).
     *
     * @param repo the repository.
     * @param downloader the single-track downloader.
     * @param concurrency fixed concurrency limit.
     */
    constructor(
        repo: DownloadRepository,
        downloader: TrackDownloader,
        concurrency: Int,
    ) : this(repo, downloader, { concurrency })

    /**
     * Runs a download for the given playlist with default (no-op) callbacks.
     *
     * @param playlistId the playlist to download.
     * @return the outcome of the run.
     */
    suspend fun run(playlistId: String): RunOutcome {
        Timber.i("DownloadQueueRunner.run(short): START")
        val result = run(playlistId, cancellationToken = { false }, onProgress = {}, onSongProgress = { _, _ -> })
        Timber.i("DownloadQueueRunner.run(short): returning $result")
        return result
    }

    /**
     * Runs a full download for the given playlist.
     *
     * @param playlistId the playlist to download.
     * @param cancellationToken returns `true` when the user requests cancellation.
     * @param onProgress called after every state change with a full progress snapshot.
     * @param onSongProgress called with per-track download fractions (0f..1f).
     * @return the outcome of the run.
     */
    suspend fun run(
        playlistId: String,
        cancellationToken: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
        onSongProgress: (songId: String, fraction: Float) -> Unit = { _, _ -> },
    ): RunOutcome {
        Timber.i("DownloadQueueRunner.run: START for playlist=$playlistId")
        recoverInFlight(playlistId)

        val selected = selectSongs(playlistId)
            ?: return idleOutcome(playlistId, onProgress)

        preparePlaylist(playlistId, selected, onProgress)
        return executeDownloadRun(playlistId, selected, cancellationToken, onProgress, onSongProgress)
    }

    private suspend fun selectSongs(playlistId: String): List<SongRecord>? {
        val selected = DownloadSelector.select(repo.songsFor(playlistId))
        if (selected.isEmpty()) {
            Timber.i("DownloadQueueRunner.run: no songs selected, returning IDLE")
            return null
        }
        return selected
    }

    private suspend fun idleOutcome(
        playlistId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): RunOutcome {
        withContext(NonCancellable) {
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            onProgress(snapshot(repo, playlistId, DownloadRunState.IDLE))
        }
        return RunOutcome.IDLE
    }

    private suspend fun preparePlaylist(
        playlistId: String,
        selected: List<SongRecord>,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        repo.setPlaylistStatus(playlistId, PlaylistStatus.DOWNLOADING)
        Timber.d("DownloadQueueRunner: state changed to DOWNLOADING for playlist=$playlistId")
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

        repo.setStatuses(selected.map { it.id }, DownloadStatus.QUEUED)
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))
    }

    private suspend fun executeDownloadRun(
        playlistId: String,
        selected: List<SongRecord>,
        cancellationToken: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ): RunOutcome {
        var outcome = RunOutcome.COMPLETED
        val concurrency = concurrencySupplier().coerceAtLeast(MIN_CONCURRENCY)
        val semaphore = Semaphore(concurrency)

        try {
            downloadAllTracks(playlistId, selected, cancellationToken, semaphore, onProgress, onSongProgress)
            checkCancellation(cancellationToken)
        } catch (e: UserCanceledException) {
            Timber.i("DownloadQueueRunner.run: user cancelled")
            outcome = RunOutcome.CANCELED
            cancelCleanup(repo, playlistId)
        } catch (e: CancellationException) {
            Timber.i("DownloadQueueRunner.run: coroutine cancelled")
            cancelCleanup(repo, playlistId)
            throw e
        }

        finalizeRun(playlistId, outcome, onProgress)
        Timber.i("DownloadQueueRunner.run: returning $outcome")
        return outcome
    }

    private suspend fun downloadAllTracks(
        playlistId: String,
        selected: List<SongRecord>,
        cancellationToken: () -> Boolean,
        semaphore: Semaphore,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ) {
        coroutineScope {
            selected.map { song ->
                async {
                    downloadSingleTrack(playlistId, song, cancellationToken, semaphore, onProgress, onSongProgress)
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadSingleTrack(
        playlistId: String,
        song: SongRecord,
        cancellationToken: () -> Boolean,
        semaphore: Semaphore,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ) {
        semaphore.withPermit {
            currentCoroutineContext().ensureActive()
            if (cancellationToken()) throw UserCanceledException()

            repo.setStatus(song.id, DownloadStatus.DOWNLOADING)
            Timber.d("DownloadQueueRunner: song ${song.id} state changed to DOWNLOADING")
            val current = repo.getSong(song.id) ?: song
            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

            val result = downloadTrackOrCatch(current, onSongProgress)
            applyTrackResult(song.id, result)
            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))
        }
    }

    private suspend fun downloadTrackOrCatch(
        song: SongRecord,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ): TrackDownloadResult = try {
        downloader.download(song) { fraction -> onSongProgress(song.id, fraction) }
    } catch (e: UserCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Timber.e(e, "DownloadQueueRunner: download FAILED for song ${song.id}")
        TrackDownloadResult(
            songId = song.id,
            error = DownloadError.Generic(e.message ?: DOWNLOAD_FAILED_MESSAGE),
        )
    }

    private suspend fun applyTrackResult(songId: String, result: TrackDownloadResult) {
        when {
            result.error is DownloadError.Canceled -> {
                repo.setStatus(songId, DownloadStatus.CANCELED, error = result.error.message)
                Timber.d("DownloadQueueRunner: song $songId state changed to CANCELED")
            }
            result.isSuccess -> {
                repo.setStatus(songId, DownloadStatus.DOWNLOADED, filePath = result.filePath, lyrics = result.lyrics)
                Timber.d("DownloadQueueRunner: song $songId state changed to DOWNLOADED")
            }
            else -> {
                repo.setStatus(songId, DownloadStatus.FAILED, error = result.error?.message)
                Timber.d("DownloadQueueRunner: song $songId state changed to FAILED")
            }
        }
    }

    private fun checkCancellation(cancellationToken: () -> Boolean) {
        if (cancellationToken()) throw UserCanceledException()
    }

    private suspend fun cancelCleanup(
        repo: DownloadRepository,
        playlistId: String,
    ) {
        withContext(NonCancellable) {
            cleanupOnCancel(repo, playlistId)
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
        }
    }

    private suspend fun finalizeRun(
        playlistId: String,
        outcome: RunOutcome,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        withContext(NonCancellable) {
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            onProgress(
                snapshot(
                    repo,
                    playlistId,
                    if (outcome == RunOutcome.CANCELED) DownloadRunState.CANCELED else DownloadRunState.COMPLETED,
                ),
            )
        }
    }

    private suspend fun recoverInFlight(playlistId: String) {
        repo.songsFor(playlistId)
            .filter { it.status.isRecoverable }
            .forEach { repo.setStatus(it.id, DownloadStatus.PENDING) }
    }

    private suspend fun cleanupOnCancel(
        repo: DownloadRepository,
        playlistId: String,
    ) {
        repo.songsFor(playlistId)
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .forEach { repo.setStatus(it.id, DownloadStatus.CANCELED, error = CANCEL_ERROR_MESSAGE) }
        repo.songsFor(playlistId)
            .filter { it.status == DownloadStatus.QUEUED }
            .forEach { repo.setStatus(it.id, DownloadStatus.PENDING) }
    }

    private suspend fun snapshot(
        repo: DownloadRepository,
        playlistId: String,
        state: DownloadRunState,
    ): DownloadProgress {
        val songs = repo.songsFor(playlistId).sortedBy { it.position }
        return DownloadProgressCalculator.fromSongs(playlistId, songs, state)
    }

    private companion object {
        const val MIN_CONCURRENCY = 1
        const val DOWNLOAD_FAILED_MESSAGE = "Download failed"
        const val CANCEL_ERROR_MESSAGE = "Download canceled"
    }
}
