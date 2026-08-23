package xyz.botolog.ghostify.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Serializes re-syncs per playlist.
 *
 * The per-playlist mutex guarantees two concurrent [SyncUseCase.syncPlaylist]
 * calls for the same playlist never interleave, so the "insert new / delete
 * removed" diff is idempotent (second sync sees the first sync's writes and
 * diff-rematches against them).
 *
 * Deadlock-safety (T-068): the manual-download path (DownloadManager) never
 * acquires this lock, and [SyncUseCase] never holds it while blocking on the
 * download queue in a way that waits for a manual download to release the DB.
 * Lock ordering is: playlist lock → (short) DB transaction → queue lock;
 * the download manager holds queue lock → (short) DB transaction. There is no
 * cycle, so no deadlock.
 */
class SyncLocks {

    /** Guards mutations to [locks]. */
    private val guard = Mutex()

    /** Per-playlist mutex instances, created on demand. */
    private val locks = HashMap<String, Mutex>()

    /**
     * Executes [block] while holding the mutex for [playlistId].
     *
     * If two coroutines call this concurrently with the same [playlistId],
     * the second one suspends until the first releases the lock.
     *
     * @param playlistId The playlist to serialize.
     * @param block The work to perform under the lock.
     * @return The result of [block].
     */
    suspend fun <T> withPlaylistLock(playlistId: String, block: suspend () -> T): T {
        Timber.i("SyncLocks.withPlaylistLock: START playlistId=$playlistId")
        val lock = guard.withLock { locks.getOrPut(playlistId) { Mutex() } }
        val result = lock.withLock { block() }
        Timber.i("SyncLocks.withPlaylistLock: returning result")
        return result
    }
}
