package xyz.botolog.ghostify.download

import xyz.botolog.ghostify.trackdownload.TrackDownloadBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-playlist download orchestrator: the entry point the UI calls.
 *
 * Guarantees:
 *  - **Deduplication (T-054):** a second `downloadAll`/`retry` while a run is
 *    active is a no-op returning false — a single run, never double-downloads.
 *  - **Isolation (T-055):** per-playlist token + execution tracking, so two
 *    playlists can run concurrently without shared-state corruption.
 *  - **Progress (T-046):** `observeProgress` re-derives the overall % from the
 *    repository snapshot (including FAILED) merged with live per-track fractions.
 *  - **Cancel (T-049):** sets the cooperative token *and* asks the executor to
 *    cancel the run; state cleanup happens inside [DownloadQueueRunner].
 *  - **Crash recovery (T-051):** a new run starts by resetting any in-flight
 *    leftovers to PENDING (see [DownloadQueueRunner.recoverInFlight]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManager(
    private val repo: DownloadRepository,
    private val runner: DownloadQueueRunner,
    private val scope: CoroutineScope,
) {

    private val executorRef = AtomicReference<DownloadExecutor?>(null)
    private val defaultInlineExecutor: DownloadExecutor by lazy { InlineDownloadExecutor(this, scope) }

    private val tokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val executions = ConcurrentHashMap.newKeySet<String>()

    private val runState = MutableStateFlow<Map<String, DownloadRunState>>(emptyMap())
    private val fractions = MutableStateFlow<Map<String, Map<String, Float>>>(emptyMap())

    /**
     * Wire the executor that actually runs downloads. Called once at wiring time
     * (Hilt / application init). Falls back to an inline executor if none is set.
     */
    fun bindExecutor(executor: DownloadExecutor) {
        Timber.i("DownloadManager.bindExecutor: START")
        executorRef.set(executor)
    }

    /**
     * Downloads every PENDING + FAILED song of the playlist (T-042), in playlist
     * order (T-045), never touching DOWNLOADED songs (T-043).
     *
     * @return true if a run was started, false if one is already running or the
     *   executor rejected the request (T-054).
     */
    fun downloadAll(playlistId: String): Boolean {
        Timber.i("DownloadManager.downloadAll: START")
        if (executions.contains(playlistId)) {
            Timber.i("DownloadManager.downloadAll: returning false (already running)")
            return false
        }
        val token = AtomicBoolean(false)
        if (tokens.putIfAbsent(playlistId, token) != null) {
            Timber.i("DownloadManager.downloadAll: returning false (token exists)")
            return false
        }
        val started = startExecution(playlistId)
        if (!started) {
            tokens.remove(playlistId)
        }
        Timber.i("DownloadManager.downloadAll: returning $started")
        return started
    }

    /**
     * Re-queues only the FAILED tracks of the playlist (T-048). Since a finished
     * run leaves no PENDING tracks behind, `downloadAll` naturally picks up only
     * the FAILED ones; PENDING tracks that arrive later are also picked up.
     */
    fun retry(playlistId: String): Boolean {
        Timber.i("DownloadManager.retry: START")
        val result = downloadAll(playlistId)
        Timber.i("DownloadManager.retry: returning $result")
        return result
    }

    /**
     * Cancels the active run for [playlistId] (T-049). The in-flight track ends
     * up CANCELED (or FAILED), the not-yet-started tracks return to PENDING, and
     * the run terminates — no zombie worker.
     */
    fun cancel(playlistId: String) {
        Timber.i("DownloadManager.cancel: START")
        tokens[playlistId]?.set(true)
        executorRef.get()?.cancel(playlistId)
        // If the run never actually started (e.g. cancelled before launch), the
        // token would otherwise leak and pin `isRunning` to true forever.
        if (!executions.contains(playlistId)) {
            tokens.remove(playlistId)
        }
    }

    /**
     * Returns whether a download run is active for [playlistId].
     *
     * @param playlistId the playlist to check.
     */
    fun isRunning(playlistId: String): Boolean {
        Timber.i("DownloadManager.isRunning: START")
        val result = executions.contains(playlistId) || tokens.containsKey(playlistId)
        Timber.i("DownloadManager.isRunning: returning $result")
        return result
    }

    /**
     * Live progress flow. Derived from the authoritative repository snapshot, so
     * it is correct before, during and after a run, including FAILED songs and
     * process death.
     *
     * @param playlistId the playlist to observe.
     */
    fun observeProgress(playlistId: String): Flow<DownloadProgress> {
        Timber.i("DownloadManager.observeProgress: START")
        // Only re-derive when this playlist's fractions actually change, and
        // conflate the (potentially bursty) Room song stream so a full playlist
        // re-projection is never queued per individual row write.
        val fractionsForPlaylist = fractions
            .map { it[playlistId] ?: emptyMap() }
            .distinctUntilChanged()
        val flow = combine(
            repo.observeSongs(playlistId).conflate(),
            runState,
            fractionsForPlaylist,
        ) { songs, runs, songFractions ->
            // `songs` already arrives ordered by position (`SongDao` orders by
            // position, added_at) — re-sorting on every emission was pure churn.
            DownloadProgressCalculator.fromSongs(
                playlistId = playlistId,
                songs = songs,
                state = runs[playlistId] ?: DownloadRunState.IDLE,
                fractions = songFractions,
            )
        }
            .distinctUntilChanged()
            // The projection builds a 1300+-entry map; keep it off the caller's
            // dispatcher (in production the UI's Main dispatcher).
            .flowOn(Dispatchers.Default)
        Timber.i("DownloadManager.observeProgress: returning flow")
        return flow
    }

    /**
     * Executes a run in the calling coroutine (used by the WorkManager worker).
     * Deduplicated against concurrent runs for the same playlist.
     *
     * @param playlistId the playlist to download.
     * @return the outcome of the run.
     */
    suspend fun runSynchronously(playlistId: String): RunOutcome {
        Timber.i("DownloadManager.runSynchronously: START")
        if (!executions.add(playlistId)) {
            Timber.i("DownloadManager.runSynchronously: returning IDLE (already running)")
            return RunOutcome.IDLE
        }
        val token = tokens.getOrPut(playlistId) { AtomicBoolean(false) }
        runState.update { it + (playlistId to DownloadRunState.RUNNING) }
        Timber.d("DownloadManager: state changed to RUNNING for $playlistId")
        fractions.update { it - playlistId }
        return try {
            executeRunner(playlistId, token)
        } catch (t: Throwable) {
            Timber.e(t, "DownloadManager: runSynchronously FAILED")
            throw t
        } finally {
            cleanupAfterRun(playlistId)
        }
    }

    private fun startExecution(playlistId: String): Boolean {
        val executor = executorRef.get() ?: defaultInlineExecutor
        return try {
            executor.execute(playlistId)
        } catch (t: Throwable) {
            Timber.e(t, "DownloadManager: execute FAILED")
            false
        }
    }

    private suspend fun executeRunner(
        playlistId: String,
        token: AtomicBoolean,
    ): RunOutcome {
        // Coalesce the per-track fraction stream: the downloader can report tens
        // of ticks per second per track and every one of them would otherwise copy
        // the whole per-playlist fraction map and re-project the entire playlist.
        // Values accumulate and are published at most once per interval; the last
        // value is force-published when the run ends so no state is dropped.
        val coalescer = FractionCoalescer(FRACTION_THROTTLE_MILLIS)
        val result = try {
            runner.run(
                playlistId = playlistId,
                cancellationToken = { token.get() },
                onProgress = { p ->
                    runState.update { it + (playlistId to p.state) }
                    Timber.d("DownloadManager: state changed to ${p.state} for $playlistId")
                },
                onSongProgress = { songId, fraction ->
                    coalescer.offer(songId, fraction)?.let { publishFractions(playlistId, it) }
                },
            )
        } finally {
            coalescer.flush()?.let { publishFractions(playlistId, it) }
        }
        Timber.i("DownloadManager.runSynchronously: returning $result")
        return result
    }

    private fun publishFractions(playlistId: String, delta: Map<String, Float>) {
        fractions.update { map ->
            val current = map[playlistId].orEmpty()
            map + (playlistId to (current + delta))
        }
    }

    private fun cleanupAfterRun(playlistId: String) {
        executions.remove(playlistId)
        tokens.remove(playlistId)
        runState.update { it - playlistId }
        // Do not keep the per-playlist fraction map resident after the run:
        // terminal statuses already carry the correct default fraction
        // (DOWNLOADED = 1f), so a stale 1300+-entry map would only leak memory.
        fractions.update { it - playlistId }
    }

    /**
     * Re-fetches lyrics for a single song and persists them to the database.
     *
     * @param songId the song to retry lyrics for.
     */
    suspend fun updateLyricsForSong(songId: String, lyrics: String) {
        Timber.i("DownloadManager.updateLyricsForSong: START songId=$songId")
        repo.updateLyrics(songId, lyrics)
        Timber.i("DownloadManager.updateLyricsForSong: done songId=$songId")
    }

    suspend fun markLyricsEdited(songId: String) {
        repo.updateLyricsEdited(songId, true)
    }

    suspend fun retryLyricsForSong(songId: String, provider: String = "synced") {
        Timber.i("DownloadManager.retryLyricsForSong: START songId=$songId, provider=$provider")
        val song = repo.getSong(songId) ?: run {
            Timber.w("DownloadManager.retryLyricsForSong: song not found $songId")
            return
        }
        val lyrics = withContext(Dispatchers.IO) {
            TrackDownloadBridge().fetchLyricsBlocking(song.title, song.artists, provider)
        }
        if (lyrics != null) {
            repo.updateLyrics(songId, lyrics)
            repo.updateLyricsSource(songId, provider)
            Timber.i("DownloadManager.retryLyricsForSong: saved ${lyrics.length} chars for $songId")
        } else {
            Timber.i("DownloadManager.retryLyricsForSong: no lyrics found for $songId")
        }
    }

    suspend fun fetchLyricsForSong(songId: String, provider: String = "synced"): String? {
        Timber.i("DownloadManager.fetchLyricsForSong: START songId=$songId, provider=$provider")
        val song = repo.getSong(songId) ?: run {
            Timber.w("DownloadManager.fetchLyricsForSong: song not found $songId")
            return null
        }
        val lyrics = withContext(Dispatchers.IO) {
            TrackDownloadBridge().fetchLyricsBlocking(song.title, song.artists, provider)
        }
        if (lyrics != null) {
            repo.updateLyrics(songId, lyrics)
            repo.updateLyricsSource(songId, provider)
            Timber.i("DownloadManager.fetchLyricsForSong: saved ${lyrics.length} chars")
        } else {
            Timber.i("DownloadManager.fetchLyricsForSong: no lyrics found")
        }
        return lyrics
    }
}

/** Minimum interval between published per-playlist fraction updates (~5/sec). */
private const val FRACTION_THROTTLE_MILLIS = 200L

/**
 * Time-gated coalescer for high-frequency per-song fraction updates.
 *
 * Mirrors `xyz.botolog.ghostify.perf.CoalescingThrottle` but keyed by song id:
 * values offered within [intervalMillis] of the last publication are merged into
 * a pending map and released on the next allowed offer or on [flush]. The latest
 * value for each song always wins, and [flush] guarantees the final state of a
 * run is never dropped.
 *
 * @param intervalMillis minimum milliseconds between publications.
 * @param clock time source returning epoch millis (overridable in tests).
 */
internal class FractionCoalescer(
    private val intervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var lastPublishedAt = Long.MIN_VALUE
    private val pending = LinkedHashMap<String, Float>()

    /**
     * Merges one fraction. Returns the per-song delta to publish now, or `null`
     * when the update was coalesced into the next publication.
     */
    fun offer(songId: String, fraction: Float): Map<String, Float>? {
        pending[songId] = fraction
        val now = clock()
        if (lastPublishedAt == Long.MIN_VALUE || now - lastPublishedAt >= intervalMillis) {
            lastPublishedAt = now
            return drain()
        }
        return null
    }

    /**
     * Force-releases everything still pending (call when the run ends). Returns
     * `null` when there is nothing new to publish.
     */
    fun flush(): Map<String, Float>? = drain()

    private fun drain(): Map<String, Float>? {
        if (pending.isEmpty()) return null
        val snapshot = LinkedHashMap(pending)
        pending.clear()
        return snapshot
    }
}
