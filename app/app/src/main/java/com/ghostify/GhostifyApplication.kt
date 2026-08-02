package com.ghostify

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ghostify.crash.CrashMarker
import com.ghostify.crash.FileLogCrashReporter
import com.ghostify.crash.GhostifyCrashHandler
import com.ghostify.crash.PrefsCrashMarker
import com.ghostify.data.db.AppDatabase
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SettingsRepository
import com.ghostify.data.repo.SongRepository
import com.ghostify.download.DownloadManager
import com.ghostify.file.MusicStore
import com.ghostify.player.PlayerController
import com.ghostify.python.FfmpegLocator
import com.ghostify.python.PlaylistMetadataBridge
import com.ghostify.recovery.KilledProcessRecovery
import com.ghostify.recovery.RecoveryGate
import com.ghostify.recovery.RoomRecoveryDao
import com.ghostify.recovery.StartupBootstrap
import com.ghostify.sync.SyncUseCase
import com.ghostify.ui.viewmodel.GhostifyViewModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
class GhostifyApplication : Application() {

    /** Process-wide composition root, built lazily on first access. */
    val container: GhostifyContainer by lazy { GhostifyContainer(this) }

    override fun onCreate() {
        super.onCreate()
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
    }

    private fun startPythonRuntime() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val ffmpegDir = FfmpegLocator.ensureExecutable(this)
            Python.getInstance()
                .getModule("ghostify_dl")
                .callAttr("prepend_path", ffmpegDir.absolutePath)
            Log.i(TAG, "Python + ffmpeg ready")
        } catch (t: Throwable) {
            Log.e(TAG, "Python runtime failed to start; running in degraded mode.", t)
        }
    }

    private companion object {
        const val TAG = "GhostifyApp"
    }
}

/** App-level composition root: builds every process-wide dependency (manual DI). */
class GhostifyContainer(private val context: Application) {

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val crashMarker: CrashMarker by lazy { PrefsCrashMarker(context) }
    val recoveryDao: RoomRecoveryDao by lazy { database.recoveryDao() }

    // --- file layer --------------------------------------------------------

    val musicStore: MusicStore by lazy { com.ghostify.file.musicStore(context) }

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

    // --- python bridges -----------------------------------------------------

    val metadataBridge: PlaylistMetadataBridge by lazy { PlaylistMetadataBridge() }

    // --- downloads ----------------------------------------------------------

    /** The single shared [DownloadManager]; also used by [DownloadWorker]. */
    val downloadManager: DownloadManager by lazy {
        com.ghostify.download.DownloadProvider.get(context).manager()
    }

    // --- sync ---------------------------------------------------------------

    val syncUseCase: SyncUseCase by lazy {
        SyncUseCase(
            playlists = database.playlistDao(),
            songs = database.songDao(),
            transactions = database.transactionRunner(),
            fetcher = com.ghostify.di.SpotifyPlaylistFetcherAdapter(metadataBridge),
            files = com.ghostify.di.MusicStoreLocalFileStore(musicStore),
            enqueuer = com.ghostify.sync.DownloadEnqueuer { playlistId ->
                downloadManager.downloadAll(playlistId)
            },
            locks = com.ghostify.sync.SyncLocks(),
        )
    }

    // --- playback ------------------------------------------------------------

    val playerController: com.ghostify.player.PlayerController by lazy {
        com.ghostify.player.PlayerController.create(
            context = context,
            sessionActivityClass = MainActivity::class.java,
        )
    }

    // --- view models ----------------------------------------------------------

    val viewModels: com.ghostify.ui.viewmodel.GhostifyViewModels by lazy {
        com.ghostify.ui.viewmodel.GhostifyViewModels(
            repo = playlistRepository,
            songRepo = songRepository,
            settings = settingsRepository,
            bridge = metadataBridge,
            downloads = downloadManager,
            syncer = syncUseCase,
            player = playerController,
            musicStore = musicStore,
        )
    }

    /** Tears down process-scoped resources when the activity is destroyed. */
    fun onDestroy() {
        viewModels.clear()
    }
}
