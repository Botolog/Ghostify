package xyz.botolog.ghostify.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import timber.log.Timber

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

    private val repo: DownloadRepository by lazy {
        repoOverride ?: SpotdlDataWiring.buildRepository(app)
    }

    private val settingsRepo: xyz.botolog.ghostify.data.repo.SettingsRepository by lazy {
        val db = xyz.botolog.ghostify.data.db.AppDatabase.get(app)
        xyz.botolog.ghostify.data.repo.SettingsRepository(db.settingDao())
    }

    private val runner: DownloadQueueRunner by lazy {
        DownloadQueueRunner(repo, downloaderOverride ?: buildWiredDownloader()) {
            runBlocking { settingsRepo.getConcurrency() }
        }
    }

    /** Builds the Chaquopy-backed [TrackDownloader] with the real bridge + store. */
    private fun buildWiredDownloader(): TrackDownloader {
        val db = xyz.botolog.ghostify.data.db.AppDatabase.get(app)
        val store = xyz.botolog.ghostify.file.musicStore(app)
        return SpotdlTrackDownloader(
            ChaquopySpotdlCall(
                bridge = xyz.botolog.ghostify.trackdownload.TrackDownloadBridge(),
                musicStore = store,
                settings = xyz.botolog.ghostify.data.repo.SettingsRepository(db.settingDao()),
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

    /**
     * Returns the shared [DownloadManager] instance.
     */
    fun manager(): DownloadManager {
        Timber.i("DownloadProvider.manager: START")
        val result = manager
        Timber.i("DownloadProvider.manager: returning manager")
        return result
    }

    /**
     * Returns a new [DownloadRecovery] bound to the same repository.
     */
    fun recovery(): DownloadRecovery {
        Timber.i("DownloadProvider.recovery: START")
        val result = DownloadRecovery(repo)
        Timber.i("DownloadProvider.recovery: returning recovery")
        return result
    }

    companion object {
        @Volatile
        private var instance: DownloadProvider? = null

        /**
         * Returns the singleton [DownloadProvider], creating it if necessary.
         *
         * @param context application context.
         */
        fun get(context: Context): DownloadProvider {
            Timber.i("DownloadProvider.get: START")
            val result = instance ?: synchronized(this) {
                instance ?: DownloadProvider(context).also { instance = it }
            }
            Timber.i("DownloadProvider.get: returning provider")
            return result
        }

        /** Test seam: inject fakes so instrumentation tests exercise the real worker. */
        fun overrideForTesting(context: Context, repo: DownloadRepository, downloader: TrackDownloader) {
            Timber.i("DownloadProvider.overrideForTesting: START")
            synchronized(this) {
                instance = DownloadProvider(context, repo, downloader)
            }
        }

        /** Resets the singleton instance. Used in tests. */
        fun reset() {
            Timber.i("DownloadProvider.reset: START")
            synchronized(this) {
                instance = null
            }
        }
    }
}

/** Points the download component at the canonical Room-backed repository. */
object SpotdlDataWiring {
    /**
     * Builds a [DownloadRepository] backed by the Room database.
     *
     * @param app application context.
     * @return a fully wired [DownloadRepository].
     */
    fun buildRepository(app: Context): DownloadRepository {
        Timber.i("SpotdlDataWiring.buildRepository: START")
        val db = xyz.botolog.ghostify.data.db.AppDatabase.get(app)
        val result = xyz.botolog.ghostify.download.data.RoomDownloadRepository(db.songDao(), db.playlistDao())
        Timber.i("SpotdlDataWiring.buildRepository: returning repository")
        return result
    }
}
