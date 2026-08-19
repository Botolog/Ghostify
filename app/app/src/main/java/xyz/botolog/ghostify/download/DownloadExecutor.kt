package xyz.botolog.ghostify.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over *where* a download run executes. Two implementations:
 *
 *  - [InlineDownloadExecutor] — runs in-process on a coroutine scope. Used by
 *    JVM tests and by UI-triggered runs when no scheduler is wired.
 *  - [DownloadScheduler] — delegates to WorkManager so downloads survive
 *    backgrounding and process death (T-050/T-051).
 */
interface DownloadExecutor {
    /** Starts a run for [playlistId]. Returns false if it could not start. */
    fun execute(playlistId: String): Boolean

    /** Requests cancellation of a running download for [playlistId]. */
    fun cancel(playlistId: String)
}

/** Executes download runs in-process on the given [scope]. */
class InlineDownloadExecutor(
    private val manager: DownloadManager,
    private val scope: CoroutineScope,
) : DownloadExecutor {

    private val jobs = ConcurrentHashMap<String, Job>()

    override fun execute(playlistId: String): Boolean {
        Timber.i("InlineDownloadExecutor.execute: START")
        if (jobs.containsKey(playlistId)) {
            Timber.i("InlineDownloadExecutor.execute: returning false (already running)")
            return false
        }
        // UNDISPATCHED: the run registers itself as active synchronously, so
        // duplicate presses and early cancels can never race a not-yet-started run.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.runSynchronously(playlistId)
        }
        jobs[playlistId] = job
        // No stale entries: a finished run (whether completed or cancelled) is
        // removed from the active map.
        job.invokeOnCompletion { jobs.remove(playlistId, job) }
        Timber.i("InlineDownloadExecutor.execute: returning true")
        return true
    }

    override fun cancel(playlistId: String) {
        Timber.i("InlineDownloadExecutor.cancel: START")
        // Cancelling the run coroutine interrupts an in-flight `TrackDownloader`
        // call; the runner's CancellationException handler restores consistent state.
        jobs.remove(playlistId)?.cancel()
    }

    fun isRunning(playlistId: String): Boolean {
        Timber.i("InlineDownloadExecutor.isRunning: START")
        val result = jobs.containsKey(playlistId)
        Timber.i("InlineDownloadExecutor.isRunning: returning $result")
        return result
    }
}
