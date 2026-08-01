package com.ghostify.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual-DI entry point used by [DownloadWorker] to reach the shared
 * [DownloadManager]. In the assembled app this is replaced by Hilt wiring
 * (PROJECT.md §3, §7.1); keeping a tiny provider here keeps the component
 * self-contained and buildable in isolation.
 */
class DownloadProvider private constructor(
    context: Context,
    repoOverride: DownloadRepository? = null,
    downloaderOverride: TrackDownloader? = null,
) {

    private val app: Context = context.applicationContext

    private val repo: DownloadRepository by lazy { repoOverride ?: SpotdlDataWiring.buildRepository(app) }

    private val runner: DownloadQueueRunner by lazy {
        DownloadQueueRunner(repo, downloaderOverride ?: SpotdlTrackDownloader())
    }

    private val manager: DownloadManager by lazy {
        DownloadManager(
            repo = repo,
            runner = runner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { m ->
            m.bindExecutor(DownloadScheduler(androidx.work.WorkManager.getInstance(app)))
        }
    }

    fun manager(): DownloadManager = manager

    fun recovery(): DownloadRecovery = DownloadRecovery(repo)

    companion object {
        @Volatile
        private var instance: DownloadProvider? = null

        fun get(context: Context): DownloadProvider =
            instance ?: synchronized(this) {
                instance ?: DownloadProvider(context).also { instance = it }
            }

        /** Test seam: inject fakes so instrumentation tests exercise the real worker. */
        fun overrideForTesting(context: Context, repo: DownloadRepository, downloader: TrackDownloader) {
            synchronized(this) {
                instance = DownloadProvider(context, repo, downloader)
            }
        }

        fun reset() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

/** Points the download component at the Room-backed repository + spotdl wrapper. */
object SpotdlDataWiring {
    fun buildRepository(app: Context): DownloadRepository =
        com.ghostify.download.data.RoomDownloadRepository(
            com.ghostify.download.data.DownloadDatabase.get(app).downloadDao(),
        )
}
