package xyz.botolog.ghostify.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import timber.log.Timber

/**
 * WorkManager-backed [DownloadExecutor]. Downloads are enqueued as **unique work**
 * keyed by playlist, so duplicate presses never create parallel workers (T-054)
 * and downloads keep running while the app is backgrounded (T-050). Cancellation
 * is forwarded to WorkManager so no zombie worker survives (T-049).
 */
class DownloadScheduler(private val workManager: WorkManager) : DownloadExecutor {

    override fun execute(playlistId: String): Boolean {
        Timber.i("DownloadScheduler.execute: START")
        val request = DownloadWorker.buildRequest(playlistId)
        workManager.enqueueUniqueWork(
            uniqueName(playlistId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        Timber.i("DownloadScheduler.execute: returning true")
        return true
    }

    override fun cancel(playlistId: String) {
        Timber.i("DownloadScheduler.cancel: START")
        workManager.cancelUniqueWork(uniqueName(playlistId))
    }

    companion object {
        fun uniqueName(playlistId: String): String = "ghostify.download.$playlistId"
    }
}
