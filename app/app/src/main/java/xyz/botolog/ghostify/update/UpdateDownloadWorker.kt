package xyz.botolog.ghostify.update

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
import java.io.File
import timber.log.Timber

class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(
            workDataOf(KEY_ERROR to "missing url"),
        )
        val version = inputData.getString(KEY_VERSION)
        return try {
            setForeground(createForegroundInfo())
            val updatesDir = File(applicationContext.cacheDir, "updates").also { it.mkdirs() }
            val partFile = File(updatesDir, UpdateDownloadHelper.partName())
            val finalFile = File(updatesDir, UpdateDownloadHelper.FINAL_NAME)
            val metaFile = File(updatesDir, UpdateDownloadHelper.META_NAME)
            val existingMeta = try {
                if (metaFile.exists()) UpdateDownloadHelper.decodeMeta(metaFile.readText()) else null
            } catch (_: Exception) {
                null
            }
            if (existingMeta != null && !UpdateDownloadHelper.metaMatches(existingMeta, url, version)) {
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
            }
            var existing = if (partFile.exists()) partFile.length() else 0L
            val metaTotal = try {
                if (metaFile.exists()) {
                    UpdateDownloadHelper.decodeMeta(metaFile.readText())
                        ?.takeIf { UpdateDownloadHelper.metaMatches(it, url, version) }?.totalBytes
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
            setProgress(workDataOf(KEY_PROGRESS to UpdateDownloadHelper.progress(existing, metaTotal), KEY_URL to url))
            val downloader = HttpUpdateDownloader()
            val total = downloader.download(url, partFile, existing, metaTotal) { downloaded, totalBytes ->
                try {
                    updatesDir.mkdirs()
                    val previous = try {
                        if (metaFile.exists()) UpdateDownloadHelper.decodeMeta(metaFile.readText()) else null
                    } catch (_: Exception) {
                        null
                    }
                    val resolved = totalBytes ?: previous?.takeIf { it.url == url }?.totalBytes
                    metaFile.writeText(UpdateDownloadHelper.encodeMeta(url, version, resolved))
                } catch (_: Exception) {
                }
                try {
                    setProgressAsync(workDataOf(KEY_PROGRESS to UpdateDownloadHelper.progress(downloaded, totalBytes), KEY_URL to url))
                } catch (_: Exception) {
                }
            }
            try {
                updatesDir.mkdirs()
                metaFile.writeText(UpdateDownloadHelper.encodeMeta(url, version, total))
            } catch (_: Exception) {
            }
            val completedLen = if (partFile.exists()) partFile.length() else 0L
            if (total != null && total > 0 && completedLen < total) {
                return Result.failure(
                    workDataOf(
                        KEY_ERROR to "incomplete download",
                        KEY_PROGRESS to UpdateDownloadHelper.progress(completedLen, total),
                    ),
                )
            }
            if (!isValidApk(partFile)) {
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
                return Result.failure(workDataOf(KEY_ERROR to "Downloaded file is not a valid APK"))
            }
            try {
                finalFile.delete()
            } catch (_: Exception) {
            }
            val renamed = try {
                if (partFile.renameTo(finalFile)) true
                else {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (!renamed || !finalFile.exists()) {
                return Result.failure(workDataOf(KEY_ERROR to "Could not finalize downloaded update"))
            }
            Result.success(workDataOf(KEY_FILE to finalFile.absolutePath))
        } catch (e: Exception) {
            Timber.e("UpdateDownloadWorker: failed: ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "download failed")))
        }
    }

    private suspend fun promoteForeground() {
        try {
            setForeground(createForegroundInfo())
        } catch (t: Throwable) {
            Timber.e(t, "UpdateDownloadWorker: foreground promotion FAILED")
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

    private fun isValidApk(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 4) return false
            val header = ByteArray(4)
            file.inputStream().use { input ->
                var read = 0
                while (read < 4) {
                    val n = input.read(header, read, 4 - read)
                    if (n == -1) break
                    read += n
                }
                if (read < 4) return false
            }
            if (!UpdateDownloadHelper.isZipMagic(header)) return false
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    zip.getEntry("AndroidManifest.xml") != null || zip.entries().hasMoreElements()
                }
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val UNIQUE_WORK = "ghostify.update.download"
        const val KEY_URL = "update_url"
        const val KEY_VERSION = "update_version"
        const val KEY_PROGRESS = "update_progress"
        const val KEY_FILE = "update_file"
        const val KEY_ERROR = "update_error"

        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "ghostify_updates"
        private const val CHANNEL_NAME = "Updates"
        private const val NOTIFICATION_TITLE = "Ghostify"
        private const val NOTIFICATION_TEXT = "Downloading update\u2026"

        fun buildRequest(url: String, versionName: String?): androidx.work.OneTimeWorkRequest {
            val data = Data.Builder()
                .putString(KEY_URL, url)
                .putString(KEY_VERSION, versionName ?: "")
                .build()
            return OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(data)
                .build()
        }

        private fun ensureChannel(context: Context) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }
}
