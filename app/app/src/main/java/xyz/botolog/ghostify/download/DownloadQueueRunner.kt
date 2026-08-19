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
 * @param concurrencySupplier callable that returns the current concurrency limit.
 *   Called at the start of each run so runtime settings changes take effect
 *   immediately without restarting the app.
 */
class DownloadQueueRunner(
    private val repo: DownloadRepository,
    private val downloader: TrackDownloader,
    private val concurrencySupplier: () -> Int = { 1 },
) {

    /** Convenience constructor for tests (fixed concurrency). */
    constructor(
        repo: DownloadRepository,
        downloader: TrackDownloader,
        concurrency: Int,
    ) : this(repo, downloader, { concurrency })

    suspend fun run(playlistId: String): RunOutcome {
        Timber.i("DownloadQueueRunner.run(short): START")
        val result = run(playlistId, cancellationToken = { false }, onProgress = {}, onSongProgress = { _, _ -> })
        Timber.i("DownloadQueueRunner.run(short): returning $result")
        return result
    }

    suspend fun run(
        playlistId: String,
        cancellationToken: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
        onSongProgress: (songId: String, fraction: Float) -> Unit = { _, _ -> },
    ): RunOutcome {
        Timber.i("DownloadQueueRunner.run: START for playlist=$playlistId")
        recoverInFlight(playlistId)

        val selected = DownloadSelector.select(repo.songsFor(playlistId))
        if (selected.isEmpty()) {
            Timber.i("DownloadQueueRunner.run: no songs selected, returning IDLE")
            withContext(NonCancellable) {
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
                Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
                onProgress(snapshot(repo, playlistId, DownloadRunState.IDLE))
            }
            return RunOutcome.IDLE
        }

        repo.setPlaylistStatus(playlistId, PlaylistStatus.DOWNLOADING)
        Timber.d("DownloadQueueRunner: state changed to DOWNLOADING for playlist=$playlistId")
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

        repo.setStatuses(selected.map { it.id }, DownloadStatus.QUEUED)
        onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

        var outcome = RunOutcome.COMPLETED
        val concurrency = concurrencySupplier().coerceAtLeast(1)
        val semaphore = Semaphore(concurrency)

        try {
            coroutineScope {
                selected.map { song ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            if (cancellationToken()) throw UserCanceledException()

                            repo.setStatus(song.id, DownloadStatus.DOWNLOADING)
                            Timber.d("DownloadQueueRunner: song ${song.id} state changed to DOWNLOADING")
                            val current = repo.getSong(song.id) ?: song
                            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))

                            val result = try {
                                downloader.download(current) { fraction -> onSongProgress(current.id, fraction) }
                            } catch (e: UserCanceledException) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Timber.e(e, "DownloadQueueRunner: download FAILED for song ${song.id}")
                                TrackDownloadResult(
                                    songId = song.id,
                                    error = DownloadError.Generic(e.message ?: "Download failed"),
                                )
                            }

                            when {
                                result.error is DownloadError.Canceled -> {
                                    repo.setStatus(song.id, DownloadStatus.CANCELED, error = result.error.message)
                                    Timber.d("DownloadQueueRunner: song ${song.id} state changed to CANCELED")
                                }
                                result.isSuccess -> {
                                    repo.setStatus(song.id, DownloadStatus.DOWNLOADED, filePath = result.filePath)
                                    Timber.d("DownloadQueueRunner: song ${song.id} state changed to DOWNLOADED")
                                }
                                else -> {
                                    repo.setStatus(song.id, DownloadStatus.FAILED, error = result.error?.message)
                                    Timber.d("DownloadQueueRunner: song ${song.id} state changed to FAILED")
                                }
                            }
                            onProgress(snapshot(repo, playlistId, DownloadRunState.RUNNING))
                        }
                    }
                }.awaitAll()

                if (cancellationToken()) throw UserCanceledException()
            }
        } catch (e: UserCanceledException) {
            Timber.i("DownloadQueueRunner.run: user cancelled")
            outcome = RunOutcome.CANCELED
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId)
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
                Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            }
        } catch (e: CancellationException) {
            Timber.i("DownloadQueueRunner.run: coroutine cancelled")
            withContext(NonCancellable) {
                cleanupOnCancel(repo, playlistId)
                repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
                Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            }
            throw e
        }

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
        Timber.i("DownloadQueueRunner.run: returning $outcome")
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
