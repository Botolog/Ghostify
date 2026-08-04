package com.ghostify.download

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

enum class RunOutcome {
    /** Nothing to download. */
    IDLE,
    /** Every selected song was processed. */
    COMPLETED,
    /** Run was cancelled by the user (or WorkManager) mid-flight. */
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
 * @param concurrency number of tracks to download in parallel (1 = sequential,
 *   the original behaviour). Must be >= 1.
 */
class DownloadQueueRunner(
    private val repo: DownloadRepository,
    private val downloader: TrackDownloader,
    private val concurrency: Int = 1,
) {

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, was $concurrency" }
    }

    suspend fun run(playlistId: String): RunOutcome =
        run(playlistId, cancellationToken = { false }, onProgress = {}, onSongProgress = { _, _ -> })

    suspend fun run(
        playlistId: String,
        cancellationToken: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
        onSongProgress: (songId: String, fraction: Float) -> Unit = { _, _ -> },
    ): RunOutcome {
        recoverInFlight(playlistId)

        val selected = DownloadSelector.select(repo.songsFor(playlistId))
        if (selected.isEmpty()) {
            withContext(NonCancellable) {
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
                onProgress(snapshot(repo, playlistId, DownloadRunState.IDLE))
            }
            return RunOutcome.IDLE
        }

        repo.setPlaylistStatus(playlistId, PlaylistStatus.DOWNLOADING)
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

        repo.setStatuses(selected.map { it.id }, DownloadStatus.QUEUED)
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

        var outcome = RunOutcome.COMPLETED
        val semaphore = Semaphore(concurrency)

        try {
            coroutineScope {
                selected.map { song ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            if (cancellationToken()) throw UserCanceledException()

                            repo.setStatus(song.id, DownloadStatus.DOWNLOADING)
                            val current = repo.getSong(song.id) ?: song
                            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

                            val result = try {
                                downloader.download(current) { fraction -> onSongProgress(current.id, fraction) }
                            } catch (e: UserCanceledException) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TrackDownloadResult(
                                    songId = song.id,
                                    error = DownloadError.Generic(e.message ?: "Download failed"),
                                )
                            }

                            when {
                                result.error is DownloadError.Canceled ->
                                    repo.setStatus(song.id, DownloadStatus.CANCELED, error = result.error.message)
                                result.isSuccess ->
                                    repo.setStatus(song.id, DownloadStatus.DOWNLOADED, filePath = result.filePath)
                                else ->
                                    repo.setStatus(song.id, DownloadStatus.FAILED, error = result.error?.message)
                            }
                            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))
                        }
                    }
                }.awaitAll()

                if (cancellationToken()) throw UserCanceledException()
            }
        } catch (e: UserCanceledException) {
            outcome = RunOutcome.CANCELED
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId)
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId)
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            }
            throw e
        }

        withContext(NonCancellable) {
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            onProgress(
                snapshot(
                    repo,
                    playlistId,
                    if (outcome == RunOutcome.CANCELED) DownloadRunState.CANCELED else DownloadRunState.COMPLETED,
                ),
            )
        }
        return outcome
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
            .forEach { repo.setStatus(it.id, DownloadStatus.CANCELED, error = "Download canceled") }
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
}
