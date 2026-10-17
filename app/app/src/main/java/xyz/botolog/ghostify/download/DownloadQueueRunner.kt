package xyz.botolog.ghostify.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

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
    private val artworkPersistence: xyz.botolog.ghostify.player.ArtworkPersistence? = null,
    private val playlistCoverFetcher: (String) -> ByteArray? = { url ->
        xyz.botolog.ghostify.player.fetchUrlBytes(url)
    },
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
    ) : this(repo, downloader, { concurrency }, null)

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
        // Load the playlist exactly once: recovery, selection and every progress
        // snapshot during the run are derived from this in-memory list instead of
        // re-querying the database per track (the old O(N²) behaviour).
        val songs = recoverInFlight(playlistId)

        val selected = DownloadSelector.select(songs)
        if (selected.isEmpty()) {
            Timber.i("DownloadQueueRunner.run: no songs selected, returning IDLE")
            return idleOutcome(playlistId, songs, onProgress)
        }

        val progress = DownloadProgressTracker(playlistId, songs)
        preparePlaylist(playlistId, selected, progress, onProgress)
        return executeDownloadRun(playlistId, selected, progress, cancellationToken, onProgress, onSongProgress)
    }

    private suspend fun idleOutcome(
        playlistId: String,
        songs: List<SongRecord>,
        onProgress: (DownloadProgress) -> Unit,
    ): RunOutcome {
        withContext(NonCancellable) {
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            onProgress(DownloadProgressCalculator.fromSongs(playlistId, songs, DownloadRunState.IDLE))
        }
        return RunOutcome.IDLE
    }

    private suspend fun preparePlaylist(
        playlistId: String,
        selected: List<SongRecord>,
        progress: DownloadProgressTracker,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        repo.setPlaylistStatus(playlistId, PlaylistStatus.DOWNLOADING)
        Timber.d("DownloadQueueRunner: state changed to DOWNLOADING for playlist=$playlistId")
        onProgress(progress.snapshot(DownloadRunState.RUNNING))

        val selectedIds = selected.map { it.id }
        repo.setStatuses(selectedIds, DownloadStatus.QUEUED)
        progress.markQueued(selectedIds)
        onProgress(progress.snapshot(DownloadRunState.RUNNING))
    }

    private suspend fun executeDownloadRun(
        playlistId: String,
        selected: List<SongRecord>,
        progress: DownloadProgressTracker,
        cancellationToken: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ): RunOutcome {
        var outcome = RunOutcome.COMPLETED
        val concurrency = concurrencySupplier().coerceAtLeast(MIN_CONCURRENCY)
        val semaphore = Semaphore(concurrency)

        try {
            downloadAllTracks(selected, progress, cancellationToken, concurrency, semaphore, onProgress, onSongProgress)
            checkCancellation(cancellationToken)
        } catch (e: UserCanceledException) {
            Timber.i("DownloadQueueRunner.run: user cancelled")
            outcome = RunOutcome.CANCELED
            cancelCleanup(repo, playlistId, progress)
        } catch (e: CancellationException) {
            Timber.i("DownloadQueueRunner.run: coroutine cancelled")
            cancelCleanup(repo, playlistId, progress)
            throw e
        }

        finalizeRun(playlistId, progress, outcome, onProgress)
        Timber.i("DownloadQueueRunner.run: returning $outcome")
        return outcome
    }

    private suspend fun downloadAllTracks(
        selected: List<SongRecord>,
        progress: DownloadProgressTracker,
        cancellationToken: () -> Boolean,
        concurrency: Int,
        semaphore: Semaphore,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ) {
        val downloadIndex = AtomicInteger(0)
        coroutineScope {
            // One Channel fed with the whole selection and exactly `concurrency`
            // consumers bounds the number of live coroutines by the pool size,
            // not by the playlist length. (The old implementation launched one
            // coroutine per song — up to ~1370 — with the Semaphore only
            // throttling the network body.)
            val queue = Channel<SongRecord>(capacity = concurrency)
            val workers = List(concurrency) {
                launch {
                    for (song in queue) {
                        downloadSingleTrack(
                            song = song,
                            progress = progress,
                            cancellationToken = cancellationToken,
                            concurrency = concurrency,
                            semaphore = semaphore,
                            downloadIndex = downloadIndex,
                            onProgress = onProgress,
                            onSongProgress = onSongProgress,
                        )
                    }
                }
            }
            try {
                for (song in selected) queue.send(song)
            } finally {
                queue.close()
            }
            workers.forEach { it.join() }
        }
    }

    private suspend fun downloadSingleTrack(
        song: SongRecord,
        progress: DownloadProgressTracker,
        cancellationToken: () -> Boolean,
        concurrency: Int,
        semaphore: Semaphore,
        downloadIndex: AtomicInteger,
        onProgress: (DownloadProgress) -> Unit,
        onSongProgress: (songId: String, fraction: Float) -> Unit,
    ) {
        semaphore.withPermit {
            currentCoroutineContext().ensureActive()
            if (cancellationToken()) throw UserCanceledException()

            val index = downloadIndex.getAndIncrement()
            if (index < concurrency) {
                val staggerMs = STAGGER_DELAY_PER_INDEX_MS * index
                Timber.d("DownloadQueueRunner: song ${song.id} waiting ${staggerMs}ms (index=$index) before download")
                delay(staggerMs)
            }

            repo.setStatus(song.id, DownloadStatus.DOWNLOADING)
            progress.markStatus(song.id, DownloadStatus.DOWNLOADING)
            Timber.d("DownloadQueueRunner: song ${song.id} state changed to DOWNLOADING")
            val current = repo.getSong(song.id) ?: song
            onProgress(progress.snapshot(DownloadRunState.RUNNING))

            val result = downloadTrackOrCatch(current, onSongProgress)
            applyTrackResult(song.id, result)
            progress.applyResult(song.id, result)
            onProgress(progress.snapshot(DownloadRunState.RUNNING))
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
                repo.updateYoutubeMeta(songId, result.ytUrl, result.ytName, result.ytChannel)
                val bitrateInt = result.bitrate?.trimEnd('k')?.toIntOrNull()
                repo.updateDownloadMeta(songId, bitrateInt, result.fileSize, System.currentTimeMillis())
                result.filePath?.let { path ->
                    artworkPersistence?.let { persistence ->
                        val localPath = persistence.persist(songId, path)
                        if (localPath != null) {
                            repo.updateCoverArtLocalPath(songId, localPath)
                            Timber.d("DownloadQueueRunner: saved cover art for song $songId")
                        }
                    }
                }
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
        progress: DownloadProgressTracker,
    ) {
        withContext(NonCancellable) {
            cleanupOnCancel(repo, playlistId)
            progress.applyCancelCleanup(CANCEL_ERROR_MESSAGE)
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
        }
    }

    private suspend fun finalizeRun(
        playlistId: String,
        progress: DownloadProgressTracker,
        outcome: RunOutcome,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        withContext(NonCancellable) {
            if (outcome == RunOutcome.COMPLETED) {
                persistPlaylistCover(playlistId)
            }
            repo.setPlaylistStatus(playlistId, PlaylistStatus.READY)
            Timber.d("DownloadQueueRunner: state changed to READY for playlist=$playlistId")
            onProgress(
                progress.snapshot(
                    if (outcome == RunOutcome.CANCELED) DownloadRunState.CANCELED else DownloadRunState.COMPLETED,
                ),
            )
        }
    }

    /**
     * Persists the playlist's own cover for offline display, never a track's cover.
     *
     * Root cause of "first-song cover after restart": the old implementation
     * unconditionally copied the first downloaded song's `covers/<songId>.jpg`
     * to `covers/playlist_<id>.jpg` and stored it as the playlist's
     * `coverArtLocalPath`. Since `coverImageModel` prefers the local file, the
     * wrong file then shadowed the correct remote `coverUrl` on every cold start
     * (memory/disk Coil caches hid it until the restart).
     *
     * New precedence, all persisted (Room + covers file):
     *  1. Existing usable local playlist file -> keep, do nothing.
     *  2. Playlist's own `coverUrl` -> download it to `playlist_<id>.jpg`.
     *  3. No own cover at all -> copy first downloaded song as last-resort
     *     fallback (only case where a track's art may stand in).
     * When (2) fails (offline/fetch error) the DB is left untouched so the
     * remote URL still displays instead of wrong art.
     */
    private suspend fun persistPlaylistCover(playlistId: String) {
        val persistence = artworkPersistence ?: return
        val playlist = runCatching { repo.getPlaylist(playlistId) }.getOrNull()

        if (playlist != null && persistence.playlistLocalUsable(playlist.coverArtLocalPath)) {
            Timber.d("DownloadQueueRunner: keeping existing playlist cover for $playlistId")
            return
        }

        val ownCoverUrl = playlist?.coverUrl?.trim().orEmpty()
        if (ownCoverUrl.isNotEmpty()) {
            val localPath = runCatching {
                persistence.persistPlaylistCoverFromUrl(playlistId, ownCoverUrl, playlistCoverFetcher)
            }.getOrNull()
            if (localPath != null) {
                repo.updatePlaylistCoverArtLocalPath(playlistId, localPath)
                Timber.d("DownloadQueueRunner: saved own playlist cover for $playlistId")
            } else {
                Timber.d("DownloadQueueRunner: own playlist cover download failed for $playlistId, keeping remote URL")
            }
            return
        }

        // Last resort: playlist truly has no own artwork — borrow the first
        // downloaded track so offline rows still show something.
        val songs = repo.songsFor(playlistId)
        val firstDownloaded = songs
            .filter { it.status == DownloadStatus.DOWNLOADED && it.filePath != null }
            .minByOrNull { it.position }
        if (firstDownloaded != null) {
            val localPath = persistence.persistPlaylistCover(playlistId, firstDownloaded.id)
            if (localPath != null) {
                repo.updatePlaylistCoverArtLocalPath(playlistId, localPath)
            }
        }
    }

    /**
     * Resets any in-flight leftovers from a previous (possibly killed) run to
     * PENDING (T-051) and returns the playlist as one in-memory snapshot.
     *
     * This is the only full read the run performs: selection and every progress
     * emission afterwards are derived from the returned list, so progress is O(1)
     * in database reads instead of the old O(N) per song.
     */
    private suspend fun recoverInFlight(playlistId: String): List<SongRecord> =
        repo.songsFor(playlistId).map { song ->
            if (song.status.isRecoverable) {
                repo.setStatus(song.id, DownloadStatus.PENDING)
                song.copy(status = DownloadStatus.PENDING, error = null)
            } else {
                song
            }
        }

    private suspend fun cleanupOnCancel(
        repo: DownloadRepository,
        playlistId: String,
    ) {
        val songs = repo.songsFor(playlistId)
        songs
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .forEach { repo.setStatus(it.id, DownloadStatus.CANCELED, error = CANCEL_ERROR_MESSAGE) }
        songs
            .filter { it.status == DownloadStatus.QUEUED }
            .forEach { repo.setStatus(it.id, DownloadStatus.PENDING) }
    }

    private companion object {
        const val MIN_CONCURRENCY = 1
        const val DOWNLOAD_FAILED_MESSAGE = "Download failed"
        const val CANCEL_ERROR_MESSAGE = "Download canceled"
        const val STAGGER_DELAY_PER_INDEX_MS = 2000L
    }
}
