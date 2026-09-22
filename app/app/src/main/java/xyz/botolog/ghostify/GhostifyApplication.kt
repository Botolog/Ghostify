package xyz.botolog.ghostify

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import xyz.botolog.ghostify.crash.CrashMarker
import xyz.botolog.ghostify.crash.FileLogCrashReporter
import xyz.botolog.ghostify.crash.GhostifyCrashHandler
import xyz.botolog.ghostify.crash.PrefsCrashMarker
import xyz.botolog.ghostify.data.db.AppDatabase
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.logging.FileLoggingTree
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.PlayerStatePersistence
import xyz.botolog.ghostify.python.FfmpegLocator
import xyz.botolog.ghostify.python.PlaylistMetadataBridge
import xyz.botolog.ghostify.recovery.KilledProcessRecovery
import xyz.botolog.ghostify.recovery.RecoveryGate
import xyz.botolog.ghostify.recovery.RoomRecoveryDao
import xyz.botolog.ghostify.recovery.StartupBootstrap
import xyz.botolog.ghostify.sync.SyncUseCase
import xyz.botolog.ghostify.ui.viewmodel.GhostifyViewModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Application entry point, owned by this (release) component.
 *
 * It is the single process-wide init point for:
 *  1. The Chaquopy interpreter, started eagerly because PROJECT.md guarantees
 *     Python is always used (metadata fetch, downloads). Starting it here means
 *     the whole app — Library browsing included — never blocks on a lazy first
 *     interpreter boot later.
 *  2. The bundled static ffmpeg binary: extracted to `filesDir/ffmpeg` and put
 *     on `PATH` so spotdl can exec it.
 *  3. The crash handler + killed-process recovery, installed before any network
 *     work so nothing can be left stuck mid-download (T-159).
 *
 * Python startup is wrapped in a fail-closed-but-degraded handler: if the
 * interpreter cannot boot (e.g. an old device, a tampered APK, a Chaquopy
 * packaging defect), the app still opens in a "library only" degraded mode
 * instead of crashing on every launch. Python-backed features then surface
 * their own error UI.
 *
 * Dependency injection is manual (PROJECT.md §7.1): this class owns the
 * process-wide singletons and exposes them through [container].
 */
class GhostifyApplication : Application(), ImageLoaderFactory {

    /** Process-wide composition root, built lazily on first access. */
    val container: GhostifyContainer by lazy { GhostifyContainer(this) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    lateinit var fileLoggingTree: FileLoggingTree
        private set

    override fun onCreate() {
        super.onCreate()

        // Timber file logging — must be planted before anything else logs.
        fileLoggingTree = FileLoggingTree(this)
        Timber.plant(fileLoggingTree)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val crashMarker = PrefsCrashMarker(this)
        startPythonRuntime()
        StartupBootstrap(
            crashHandler = GhostifyCrashHandler(FileLogCrashReporter(this, crashMarker)),
            recovery = KilledProcessRecovery(AppDatabase.get(this).recoveryDao()),
            recoveryGate = RecoveryGate(),
            scope = scope,
        ).onColdStart()
        crashMarker.markCleanStartup()
        Timber.i("Application started")
    }

    private fun startPythonRuntime() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val ffmpeg = FfmpegLocator.ensureExecutable(this)
            val ffmpegDir = ffmpeg.parentFile?.absolutePath ?: ffmpeg.absolutePath
            Python.getInstance()
                .getModule(MODULE_GHOSTIFY_DL)
                .callAttr(FN_PREPEND_PATH, ffmpegDir)
            Log.i(TAG, "Python + ffmpeg ready")
        } catch (t: Throwable) {
            Log.e(TAG, "Python runtime failed to start; running in degraded mode.", t)
        }
    }

    private companion object {
        const val TAG = "GhostifyApp"
        private const val MODULE_GHOSTIFY_DL = "ghostify_dl"
        private const val FN_PREPEND_PATH = "prepend_path"
    }
}

/** App-level composition root: builds every process-wide dependency (manual DI). */
class GhostifyContainer(private val context: Application) {

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val crashMarker: CrashMarker by lazy { PrefsCrashMarker(context) }
    val recoveryDao: RoomRecoveryDao by lazy { database.recoveryDao() }

    // --- file layer --------------------------------------------------------

    val musicStore: MusicStore by lazy { xyz.botolog.ghostify.file.musicStore(context) }

    // --- repositories -------------------------------------------------------

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(database.settingDao())
    }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            playlistDao = database.playlistDao(),
            songDao = database.songDao(),
            transactions = database.transactionRunner(),
        )
    }

    val songRepository: SongRepository by lazy {
        SongRepository(database.songDao(), database.transactionRunner())
    }

    val playerStatePersistence: PlayerStatePersistence by lazy {
        PlayerStatePersistence(
            settings = settingsRepository,
            songDao = database.songDao(),
        )
    }

    // --- python bridges -----------------------------------------------------

    val metadataBridge: PlaylistMetadataBridge by lazy { PlaylistMetadataBridge() }

    // --- downloads ----------------------------------------------------------

    /** The single shared [DownloadManager]; also used by [DownloadWorker]. */
    val downloadManager: DownloadManager by lazy {
        xyz.botolog.ghostify.download.DownloadProvider.get(context).also {
            it.setMusicStore(musicStore)
        }.manager()
    }

    // --- sync ---------------------------------------------------------------

    val syncUseCase: SyncUseCase by lazy {
        SyncUseCase(
            playlists = database.playlistDao(),
            songs = database.songDao(),
            transactions = database.transactionRunner(),
            fetcher = xyz.botolog.ghostify.di.SpotifyPlaylistFetcherAdapter(metadataBridge),
            files = xyz.botolog.ghostify.di.MusicStoreLocalFileStore(musicStore),
            enqueuer = xyz.botolog.ghostify.sync.DownloadEnqueuer { playlistId ->
                downloadManager.downloadAll(playlistId)
            },
            locks = xyz.botolog.ghostify.sync.SyncLocks(),
        )
    }

    // --- playback ------------------------------------------------------------

    val playerController: xyz.botolog.ghostify.player.PlayerController by lazy {
        xyz.botolog.ghostify.player.PlayerController.create(
            context = context,
            sessionActivityClass = MainActivity::class.java,
            persistence = playerStatePersistence,
        )
    }

    // --- view models ----------------------------------------------------------

    // Rebuilt per activity lifetime: clear() cancels every VM scope, so the bundle
    // must be discarded afterwards or the next activity inherits dead coroutines.
    private var viewModelsBundle: xyz.botolog.ghostify.ui.viewmodel.GhostifyViewModels? = null

    val viewModels: xyz.botolog.ghostify.ui.viewmodel.GhostifyViewModels
        get() = viewModelsBundle ?: buildViewModels().also { viewModelsBundle = it }

    private fun buildViewModels(): xyz.botolog.ghostify.ui.viewmodel.GhostifyViewModels =
        xyz.botolog.ghostify.ui.viewmodel.GhostifyViewModels(
            context = context,
            repo = playlistRepository,
            songRepo = songRepository,
            settings = settingsRepository,
            bridge = metadataBridge,
            downloads = downloadManager,
            syncer = syncUseCase,
            player = playerController,
            musicStore = musicStore,
        )

    /** Tears down activity-scoped ViewModels when the activity is destroyed. */
    fun onDestroy() {
        viewModelsBundle?.clear()
        viewModelsBundle = null
    }
}
