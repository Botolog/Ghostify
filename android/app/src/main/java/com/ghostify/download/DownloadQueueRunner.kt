package com.ghostify.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 */
class DownloadQueueRunner(
    private val repo: DownloadRepository,
    private val downloader: TrackDownloader,
) {

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
        var current: SongRecord? = null
        try {
            for (song in selected) {
                currentCoroutineContext().ensureActive()
                if (cancellationToken()) throw UserCanceledException()

                repo.setStatus(song.id, DownloadStatus.DOWNLOADING)
                current = repo.getSong(song.id) ?: song
                val downloading = current
                onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

                val result = try {
                    downloader.download(downloading) { fraction -> onSongProgress(downloading.id, fraction) }
                } catch (e: UserCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Never let a single-track failure crash the run (T-052/053).
                    TrackDownloadResult(
                        songId = song.id,
                        error = DownloadError.Generic(e.message ?: "Download failed"),
                    )
                }

                // Commit the terminal state before honouring any cancellation that
                // may have raced in while the download was running.
                when {
                    result.error is DownloadError.Canceled ->
                        repo.setStatus(song.id, DownloadStatus.CANCELED, error = result.error.message)
                    result.isSuccess ->
                        repo.setStatus(song.id, DownloadStatus.DOWNLOADED, filePath = result.filePath)
                    else ->
                        repo.setStatus(song.id, DownloadStatus.FAILED, error = result.error?.message)
                }
                current = null
                onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

                if (cancellationToken()) throw UserCanceledException()
            }
        } catch (e: UserCanceledException) {
            outcome = RunOutcome.CANCELED
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId, current)
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            }
        } catch (e: CancellationException) {
            // e.g. WorkManager cancelled the worker. Clean up state so nothing is
            // left stuck, then propagate.
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId, current)
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
        current: SongRecord?,
    ) {
        current?.let {
            repo.setStatus(it.id, DownloadStatus.CANCELED, error = "Download canceled")
        }
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
