package com.ghostify.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Minimal manual-DI entry point used by [DownloadWorker] to reach the shared
 * [DownloadManager]. In the assembled app the composition root builds these
 * dependencies and passes them down (PROJECT.md §3, §7.1); keeping a tiny
 * provider here keeps the component self-contained and buildable in isolation.
 */
class DownloadProvider private constructor(
    context: Context,
    repoOverride: DownloadRepository? = null,
    downloaderOverride: TrackDownloader? = null,
) {

    private val app: Context = context.applicationContext

    private val repo: DownloadRepository by lazy { repoOverride ?: SpotdlDataWiring.buildRepository(app) }

    private val settingsRepo: com.ghostify.data.repo.SettingsRepository by lazy {
        val db = com.ghostify.data.db.AppDatabase.get(app)
        com.ghostify.data.repo.SettingsRepository(db.settingDao())
    }

    private val runner: DownloadQueueRunner by lazy {
        DownloadQueueRunner(repo, downloaderOverride ?: buildWiredDownloader()) {
            runBlocking { settingsRepo.getConcurrency() }
        }
    }

    /** Builds the Chaquopy-backed [TrackDownloader] with the real bridge + store. */
    private fun buildWiredDownloader(): TrackDownloader {
        val db = com.ghostify.data.db.AppDatabase.get(app)
        val store = com.ghostify.file.musicStore(app)
        return SpotdlTrackDownloader(
            ChaquopySpotdlCall(
                bridge = com.ghostify.trackdownload.TrackDownloadBridge(),
                musicStore = store,
                settings = com.ghostify.data.repo.SettingsRepository(db.settingDao()),
            )
        )
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

/** Points the download component at the canonical Room-backed repository. */
object SpotdlDataWiring {
    fun buildRepository(app: Context): DownloadRepository {
        val db = com.ghostify.data.db.AppDatabase.get(app)
        return com.ghostify.download.data.RoomDownloadRepository(db.songDao(), db.playlistDao())
    }
}
