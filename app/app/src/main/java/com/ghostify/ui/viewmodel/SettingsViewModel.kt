package com.ghostify.ui.viewmodel

import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SettingsRepository
import com.ghostify.data.repo.SongRepository
import com.ghostify.file.MusicStore
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.contract.SettingsContract.SettingsUiState
import com.ghostify.ui.model.Bitrate
import com.ghostify.ui.model.CacheStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
                .catch { }
                .collect { }
        }
        launch { refreshCacheStats() }
    }

    override fun setBitrate(bitrate: Bitrate) {
        launch { settings.setBitrate(bitrate.toKbpsString()) }
    }

    override fun changeStoragePath() {
        // v1: media always lives in app-scoped storage (no storage permission);
        // the "Change" control is intentionally inert until a SAF picker ships.
    }

    override fun setConcurrency(count: Int) {
        launch { settings.setConcurrency(count.coerceIn(1, 8)) }
    }

    override fun setAutoDownload(enabled: Boolean) {
        launch { settings.setAutoDownload(enabled) }
    }

    override fun clearCache() {
        launch {
            _state.update { it.copy(isClearingCache = true) }
            try {
                val known = collectAllSongPaths()
                musicStore.clearOrphans(known)
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
