package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.contract.SettingsContract.SettingsUiState
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val playlistRepo: PlaylistRepository,
    private val songRepo: SongRepository,
    private val musicStore: MusicStore,
) : ContractViewModel(), SettingsContract {

    private val _state = MutableStateFlow(SettingsUiState())
    override val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        Timber.i("SettingsViewModel: init")
        launch {
            combine(
                settings.observeBitrate(),
                settings.observeConcurrency(),
                settings.observeAutoDownload(),
            ) { bitrate, concurrency, autoDownload ->
                _state.update {
                    it.copy(
                        bitrate = bitrateFromKbps(bitrate),
                        storagePath = musicStore.rootDir.absolutePath,
                        concurrentDownloads = concurrency,
                        autoDownloadOnAdd = autoDownload,
                    )
                }
            }
                .catch { e -> Timber.e(e, "SettingsViewModel: settings stream FAILED") }
                .collect { }
        }
        launch { refreshCacheStats() }
    }

    override fun setBitrate(bitrate: Bitrate) {
        Timber.i("SettingsViewModel.setBitrate: START")
        launch { settings.setBitrate(bitrate.toKbpsString()) }
    }

    override fun changeStoragePath() {
        Timber.i("SettingsViewModel.changeStoragePath: START")
        // v1: media always lives in app-scoped storage (no storage permission);
        // the "Change" control is intentionally inert until a SAF picker ships.
    }

    override fun setConcurrency(count: Int) {
        Timber.i("SettingsViewModel.setConcurrency: START")
        launch { settings.setConcurrency(count.coerceAtLeast(1)) }
    }

    override fun setAutoDownload(enabled: Boolean) {
        Timber.i("SettingsViewModel.setAutoDownload: START")
        launch { settings.setAutoDownload(enabled) }
    }

    override fun clearCache() {
        Timber.i("SettingsViewModel.clearCache: START")
        launch {
            _state.update { it.copy(isClearingCache = true) }
            try {
                val known = collectAllSongPaths()
                musicStore.clearOrphans(known)
            } catch (e: Exception) {
                Timber.e(e, "SettingsViewModel.clearCache: FAILED")
            } finally {
                _state.update { it.copy(isClearingCache = false) }
            }
            refreshCacheStats()
        }
    }

    private suspend fun refreshCacheStats() {
        val known = collectAllSongPaths()
        val orphans = musicStore.orphanFiles(known)
        _state.update {
            it.copy(
                cacheStats = CacheStats(
                    fileCount = orphans.size,
                    sizeBytes = orphans.sumOf { f -> runCatching { f.length() }.getOrDefault(0L) },
                ),
            )
        }
    }

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
}
