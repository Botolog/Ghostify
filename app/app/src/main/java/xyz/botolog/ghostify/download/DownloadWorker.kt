package xyz.botolog.ghostify.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import timber.log.Timber

/**
 * WorkManager worker that executes a single playlist's download run.
 *
 * - Runs [DownloadManager.runSynchronously] so downloads continue while the app is
 *   backgrounded (T-050).
 * - Registers as a data-sync foreground service so the OS does not kill it mid-run.
 * - If the process is killed mid-download (T-051), the next run (manual retry or
 *   app restart via [DownloadRecovery]) resets the in-flight track to PENDING.
 * - On cancellation the runner restores consistent state and the worker returns
 *   success — no zombie workers (T-049).
 *
 * Device/instrumentation only; unit behaviour is covered through
 * [DownloadQueueRunner] on the JVM.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Timber.i("DownloadWorker.doWork: START")
        val playlistId = inputData.getString(KEY_PLAYLIST_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to MISSING_PLAYLIST_ID_ERROR))

        promoteToForeground()

        val manager = DownloadProvider.get(applicationContext).manager()
        val outcome = manager.runSynchronously(playlistId)
        Timber.i("DownloadWorker.doWork: returning $outcome")
        return when (outcome) {
            RunOutcome.IDLE, RunOutcome.COMPLETED, RunOutcome.CANCELED -> Result.success()
        }
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(createForegroundInfo())
        } catch (t: Throwable) {
            Timber.e(t, "DownloadWorker: foreground promotion FAILED")
            // Foreground promotion is best-effort; the download must still run.
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val context = applicationContext
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val KEY_ERROR = "download_error"

        private const val NOTIFICATION_ID = 4100
        private const val CHANNEL_ID = "ghostify_downloads"
        private const val NOTIFICATION_TITLE = "Ghostify"
        private const val NOTIFICATION_TEXT = "Downloading playlist\u2026"
        private const val MISSING_PLAYLIST_ID_ERROR = "missing playlist_id"

        /**
         * Builds a [OneTimeWorkRequest] for downloading the given playlist.
         *
         * @param playlistId the playlist to download.
         * @return a configured work request.
         */
        fun buildRequest(playlistId: String): androidx.work.OneTimeWorkRequest {
            Timber.i("DownloadWorker.buildRequest: START")
            val result = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_PLAYLIST_ID, playlistId).build())
                .build()
            Timber.i("DownloadWorker.buildRequest: returning request")
            return result
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        private const val CHANNEL_NAME = "Downloads"
    }
}
