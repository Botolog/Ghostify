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
        launch {
            settings.setStorageDir(path)
            val dir = java.io.File(path)
            musicStore.updateRoot(dir)
            Timber.i("SettingsViewModel: MusicStore root updated to ${dir.absolutePath}")
        }
    }

    override fun resetStoragePath() {
        Timber.i("SettingsViewModel.resetStoragePath: START")
        val defaultName = SettingsRepository.DEFAULT_STORAGE_DIR
        launch {
            settings.setStorageDir(defaultName)
            val dir = xyz.botolog.ghostify.file.resolveStorageDir(context, defaultName)
            musicStore.updateRoot(dir)
            Timber.i("SettingsViewModel: MusicStore root reset to ${dir.absolutePath}")
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

    override fun clearCache() {
        Timber.i("SettingsViewModel.clearCache: START")
        launch {
            performClearCache()
            refreshCacheStats()
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
        ) { bitrate, concurrency, autoDownload, storageDir ->
            _state.update {
                it.copy(
                    bitrate = bitrateFromKbps(bitrate),
                    storagePath = storageDir,
                    concurrentDownloads = concurrency,
                    autoDownloadOnAdd = autoDownload,
                )
            }
        }
            .catch { e -> Timber.e(e, "SettingsViewModel: settings stream FAILED") }
            .collect { }
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
