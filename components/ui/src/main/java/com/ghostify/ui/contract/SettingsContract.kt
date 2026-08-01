package com.ghostify.ui.contract

import com.ghostify.ui.model.Bitrate
import com.ghostify.ui.model.CacheStats
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Settings screen.
 *
 * The contract is the single place that talks to the settings repository, so values
 * "persist and are used for new downloads / new saves" by construction: the download
 * and add-playlist flows read the same repository the contract writes.
 */
interface SettingsContract {

    val state: StateFlow<SettingsUiState>

    fun setBitrate(bitrate: Bitrate)

    /** Opens the storage-picker flow (SAF); the contract writes the chosen path. */
    fun changeStoragePath()

    fun setConcurrency(count: Int)

    fun setAutoDownload(enabled: Boolean)

    /** Deletes orphan/cache files; library records are untouched. */
    fun clearCache()

    data class SettingsUiState(
        val bitrate: Bitrate = Bitrate.MEDIUM,
        val storagePath: String = "",
        val concurrentDownloads: Int = 1,
        val autoDownloadOnAdd: Boolean = false,
        val cacheStats: CacheStats = CacheStats(0, 0),
        val isClearingCache: Boolean = false,
    )
}
