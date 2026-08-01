package com.ghostify.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf

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
        val playlistId = inputData.getString(KEY_PLAYLIST_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing playlist_id"))

        try {
            setForeground(createForegroundInfo())
        } catch (_: Throwable) {
            // Foreground promotion is best-effort; the download must still run.
        }

        val manager = DownloadProvider.get(applicationContext).manager()
        val outcome = manager.runSynchronously(playlistId)
        return when (outcome) {
            RunOutcome.IDLE, RunOutcome.COMPLETED, RunOutcome.CANCELED -> Result.success()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val context = applicationContext
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Ghostify")
            .setContentText("Downloading playlist\u2026")
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

        fun buildRequest(playlistId: String) =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_PLAYLIST_ID, playlistId).build())
                .build()

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
