package xyz.botolog.ghostify.ui.viewmodel

import android.content.Context
import android.net.Uri
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.file.resolveTreeUriToPath
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.contract.SettingsContract.SettingsUiState
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import xyz.botolog.ghostify.ui.model.FullPlayerLayout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import timber.log.Timber
import xyz.botolog.ghostify.update.DownloadState
import xyz.botolog.ghostify.update.UpdateChecker

/**
 * Backs the Settings screen. It is the single place that talks to the settings
 * repository, so values persist and are used for new downloads / new saves by
 * construction (the download and add-playlist flows read the same repository).
 *
 * Cache stats are derived from [MusicStore.orphanFiles] against every file path
 * the database knows about, so clear-cache never touches library files.
 *
 * @property settings app-wide settings repository.
 * @property playlistRepo playlist persistence layer.
 * @property songRepo song persistence layer.
 * @property musicStore local file storage manager.
 */
class SettingsViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val playlistRepo: PlaylistRepository,
    private val songRepo: SongRepository,
    private val musicStore: MusicStore,
) : ContractViewModel(), SettingsContract {

    private val _state = MutableStateFlow(SettingsUiState())
    override val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _openStoragePicker = MutableSharedFlow<Unit>()
    override val openStoragePicker: SharedFlow<Unit> = _openStoragePicker.asSharedFlow()

    init {
        Timber.i("SettingsViewModel: init")
        launch { observeSettings() }
        launch { refreshCacheStats() }
        launch { initStorageFromSettings() }
    }

    override fun setBitrate(bitrate: Bitrate) {
        Timber.i("SettingsViewModel.setBitrate: START")
        launch { settings.setBitrate(bitrate.toKbpsString()) }
    }

    override fun changeStoragePath() {
        Timber.i("SettingsViewModel.changeStoragePath: START")
        launch { _openStoragePicker.emit(Unit) }
    }

    override fun setStoragePath(path: String) {
        Timber.i("SettingsViewModel.setStoragePath: path=$path")
        val oldPath = musicStore.rootDir.absolutePath
        launch {
            settings.setStorageDir(path)
            val dir = java.io.File(path)
            musicStore.updateRoot(dir)
            Timber.i("SettingsViewModel: MusicStore root updated to ${dir.absolutePath}")
            checkForMigration(oldPath)
        }
    }

    override fun resetStoragePath() {
        Timber.i("SettingsViewModel.resetStoragePath: START")
        val oldPath = musicStore.rootDir.absolutePath
        val defaultName = SettingsRepository.DEFAULT_STORAGE_DIR
        launch {
            settings.setStorageDir(defaultName)
            val dir = xyz.botolog.ghostify.file.resolveStorageDir(context, defaultName)
            musicStore.updateRoot(dir)
            Timber.i("SettingsViewModel: MusicStore root reset to ${dir.absolutePath}")
            checkForMigration(oldPath)
        }
    }

    override fun dismissMigration() {
        _state.update { it.copy(pendingMigrationPath = null, migrationResult = null) }
    }

    override fun migrateSongs() {
        val oldPath = _state.value.pendingMigrationPath ?: return
        val newPath = musicStore.rootDir.absolutePath
        Timber.i("SettingsViewModel.migrateSongs: $oldPath -> $newPath")
        launch {
            _state.update { it.copy(isMigrating = true) }
            try {
                val result = performMigration(oldPath, newPath)
                _state.update {
                    it.copy(
                        isMigrating = false,
                        pendingMigrationPath = null,
                        migrationResult = result,
                    )
                }
                Timber.i("SettingsViewModel.migrateSongs: $result")
            } catch (e: Exception) {
                Timber.e(e, "SettingsViewModel.migrateSongs: FAILED")
                _state.update {
                    it.copy(
                        isMigrating = false,
                        pendingMigrationPath = null,
                        migrationResult = "Migration failed: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Checks if there are downloaded songs at the old path and sets up migration dialog.
     */
    private suspend fun checkForMigration(oldPath: String) {
        val oldDir = java.io.File(oldPath)
        if (!oldDir.exists()) return
        val songs = collectAllSongPaths()
        val hasSongsAtOldPath = songs.any { it.startsWith(oldPath) }
        if (hasSongsAtOldPath) {
            _state.update { it.copy(pendingMigrationPath = oldPath) }
        }
    }

    /**
     * Moves all song files from [oldPath] to [newPath], preserving the
     * `playlistId/filename.mp3` structure. Updates the database file_path
     * for each moved song.
     *
     * @return a summary string like "12 moved, 2 failed".
     */
    private suspend fun performMigration(oldPath: String, newPath: String): String {
        var moved = 0
        var failed = 0
        val playlists = playlistRepo.observePlaylists().first()
        for (playlist in playlists) {
            val songs = songRepo.getSongs(playlist.id)
            for (song in songs) {
                val oldFilePath = song.filePath
                if (oldFilePath.isNullOrBlank()) continue
                if (!oldFilePath.startsWith(oldPath)) continue

                val oldFile = java.io.File(oldFilePath)
                if (!oldFile.exists()) continue

                // Build new path: newPath/playlistId/filename
                val fileName = oldFile.name
                val targetDir = java.io.File(newPath, playlist.id)
                val newFile = java.io.File(targetDir, fileName)

                try {
                    targetDir.mkdirs()
                    val success = oldFile.renameTo(newFile)
                    if (success) {
                        songRepo.updateFilePath(song.id, newFile.absolutePath)
                        moved++
                    } else {
                        // renameTo can fail across mount points; fall back to copy+delete
                        oldFile.copyTo(newFile, overwrite = true)
                        oldFile.delete()
                        songRepo.updateFilePath(song.id, newFile.absolutePath)
                        moved++
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Migration: failed to move $oldFilePath")
                    failed++
                }
            }
        }
        return if (failed == 0) {
            "$moved song${if (moved != 1) "s" else ""} moved"
        } else {
            "$moved moved, $failed failed"
        }
    }

    /**
     * Resolves a SAF tree URI to a filesystem path and saves it.
     * Called by the UI after the SAF picker returns.
     */
    fun handleStoragePickerResult(uri: Uri?) {
        if (uri == null) {
            Timber.w("SettingsViewModel: storage picker returned null URI")
            return
        }
        val path = resolveTreeUriToPath(context, uri)
        if (path != null) {
            setStoragePath(path)
        } else {
            Timber.e("SettingsViewModel: failed to resolve SAF URI to path: $uri")
        }
    }

    override fun setConcurrency(count: Int) {
        Timber.i("SettingsViewModel.setConcurrency: START")
        launch { settings.setConcurrency(count.coerceAtLeast(MIN_CONCURRENCY)) }
    }

    override fun setAutoDownload(enabled: Boolean) {
        Timber.i("SettingsViewModel.setAutoDownload: START")
        launch { settings.setAutoDownload(enabled) }
    }

    override fun setShowQueueCovers(enabled: Boolean) {
        Timber.i("SettingsViewModel.setShowQueueCovers: START")
        launch { settings.setShowQueueCovers(enabled) }
    }

    override fun setLoopPlaylists(enabled: Boolean) {
        launch { settings.setLoopPlaylists(enabled) }
    }

    override fun setFullPlayerLayout(layout: FullPlayerLayout) {
        Timber.i("SettingsViewModel.setFullPlayerLayout: layout=$layout")
        launch { settings.setFullPlayerLayout(layout.storageValue) }
    }

    override fun setLandscapeControlsSide(side: String) {
        Timber.i("SettingsViewModel.setLandscapeControlsSide: side=$side")
        launch { settings.setLandscapeControlsSide(side) }
    }

    override fun clearCache() {
        Timber.i("SettingsViewModel.clearCache: START")
        launch {
            performClearCache()
            refreshCacheStats()
        }
    }

    override fun checkForUpdate() {
        Timber.i("SettingsViewModel.checkForUpdate: START")
        launch {
            _state.update { it.copy(isCheckingUpdate = true, updateError = null) }
            try {
                val info = UpdateChecker.checkForUpdate(context)
                if (info != null) {
                    _state.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateInfo = info,
                            showUpdateDialog = true,
                        )
                    }
                    Timber.i("SettingsViewModel.checkForUpdate: update available v${info.versionName}")
                } else {
                    _state.update {
                        it.copy(isCheckingUpdate = false, updateError = "You're up to date!")
                    }
                    Timber.i("SettingsViewModel.checkForUpdate: already up to date")
                }
            } catch (e: Exception) {
                Timber.e(e, "SettingsViewModel.checkForUpdate: FAILED")
                _state.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateError = "Update check failed: ${e.message}",
                    )
                }
            }
        }
    }

    override fun confirmUpdate() {
        Timber.i("SettingsViewModel.confirmUpdate: START")
        val info = _state.value.updateInfo ?: return
        launch {
            _state.update { it.copy(showUpdateDialog = false, downloadState = DownloadState.Downloading(0f)) }
            try {
                val file = UpdateChecker.downloadApk(context, info.apkDownloadUrl) { progress ->
                    _state.update { it.copy(downloadState = DownloadState.Downloading(progress)) }
                }
                _state.update { it.copy(downloadState = DownloadState.Downloaded(file)) }
                Timber.i("SettingsViewModel.confirmUpdate: download complete")
            } catch (e: Exception) {
                Timber.e(e, "SettingsViewModel.confirmUpdate: download FAILED")
                _state.update {
                    it.copy(downloadState = DownloadState.Error("Download failed: ${e.message}"))
                }
            }
        }
    }

    override fun dismissUpdate() {
        _state.update {
            it.copy(
                showUpdateDialog = false,
                updateInfo = null,
                downloadState = DownloadState.Idle,
                updateError = null,
            )
        }
    }

    override fun installUpdate() {
        Timber.i("SettingsViewModel.installUpdate: START")
        val state = _state.value.downloadState
        if (state !is DownloadState.Downloaded) return
        try {
            UpdateChecker.installApk(context, state.file)
        } catch (e: Exception) {
            Timber.e(e, "SettingsViewModel.installUpdate: FAILED")
            _state.update {
                it.copy(updateError = "Install failed: ${e.message}")
            }
        }
    }

    /**
     * Reads the saved storage path from settings and updates MusicStore root.
     * If the saved value is a relative name (default), resolves it under app external storage.
     * If it's an absolute path (from SAF picker), uses it directly.
     */
    private suspend fun initStorageFromSettings() {
        val savedPath = settings.getStorageDir()
        val dir = if (savedPath.startsWith("/")) {
            // Absolute path from SAF picker
            java.io.File(savedPath)
        } else {
            // Relative name — resolve under app external storage
            xyz.botolog.ghostify.file.resolveStorageDir(context, savedPath)
        }
        musicStore.updateRoot(dir)
        Timber.i("SettingsViewModel: MusicStore root initialized to ${dir.absolutePath}")
    }

    /**
     * Observes the settings repository and keeps the UI state in sync.
     */
    private suspend fun observeSettings() {
        combine(
            settings.observeBitrate(),
            settings.observeConcurrency(),
            settings.observeAutoDownload(),
            settings.observeStorageDir(),
            settings.observeLandscapeControlsSide(),
        ) { bitrate, concurrency, autoDownload, storageDir, landscapeControlsSide ->
            SettingsUiState(
                bitrate = bitrateFromKbps(bitrate),
                storagePath = storageDir,
                concurrentDownloads = concurrency,
                autoDownloadOnAdd = autoDownload,
                landscapeControlsSide = landscapeControlsSide,
            )
        }
            .combine(settings.observeShowQueueCovers()) { uiState, showQueueCovers ->
                uiState.copy(showQueueCovers = showQueueCovers)
            }
            .combine(settings.observeLoopPlaylists()) { uiState, loopPlaylists ->
                uiState.copy(loopPlaylists = loopPlaylists)
            }
            .combine(settings.observeFullPlayerLayout()) { uiState, fullPlayerLayout ->
                uiState.copy(fullPlayerLayout = FullPlayerLayout.fromStorageValue(fullPlayerLayout))
            }
            .catch { e -> Timber.e(e, "SettingsViewModel: settings stream FAILED") }
            .collect { uiState -> _state.value = uiState }
    }

    /**
     * Clears orphan cache files and shows the loading indicator during the operation.
     */
    private suspend fun performClearCache() {
        _state.update { it.copy(isClearingCache = true) }
        try {
            val knownPaths = collectAllSongPaths()
            musicStore.clearOrphans(knownPaths)
        } catch (e: Exception) {
            Timber.e(e, "SettingsViewModel.clearCache: FAILED")
        } finally {
            _state.update { it.copy(isClearingCache = false) }
        }
    }

    /**
     * Recalculates cache statistics and updates the UI state.
     */
    private suspend fun refreshCacheStats() {
        val knownPaths = collectAllSongPaths()
        val orphans = musicStore.orphanFiles(knownPaths)
        _state.update {
            it.copy(
                cacheStats = CacheStats(
                    fileCount = orphans.size,
                    sizeBytes = orphans.sumOf { file ->
                        runCatching { file.length() }.getOrDefault(0L)
                    },
                ),
            )
        }
    }

    /**
     * Collects all song file paths across every playlist in the database.
     *
     * @return list of non-blank file paths known to the library.
     */
    private suspend fun collectAllSongPaths(): List<String> {
        val playlists = playlistRepo.observePlaylists().first()
        return buildList {
            for (playlist in playlists) {
                for (song in songRepo.getSongs(playlist.id)) {
                    if (!song.filePath.isNullOrBlank()) add(song.filePath!!)
                }
            }
        }
    }

    companion object {
        /** Minimum allowed value for concurrent downloads. */
        private const val MIN_CONCURRENCY = 1
    }
}
