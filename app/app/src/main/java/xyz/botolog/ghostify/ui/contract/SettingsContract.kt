package xyz.botolog.ghostify.ui.contract

import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Settings screen.
 *
 * The contract is the single place that talks to the settings repository, so values
 * "persist and are used for new downloads / new saves" by construction: the download
 * and add-playlist flows read the same repository the contract writes.
 */
interface SettingsContract {

    /** Observable UI state for the Settings screen. */
    val state: StateFlow<SettingsUiState>

    /**
     * Update the download bitrate preference.
     *
     * @param bitrate the new bitrate to apply.
     */
    fun setBitrate(bitrate: Bitrate)

    /**
     * Opens the storage-picker flow (SAF); the contract writes the chosen path.
     */
    fun changeStoragePath()

    /**
     * Update the maximum number of concurrent downloads.
     *
     * @param count desired concurrency; clamped to a minimum of 1.
     */
    fun setConcurrency(count: Int)

    /**
     * Enable or disable auto-download when a playlist is added.
     *
     * @param enabled `true` to start downloads immediately on add.
     */
    fun setAutoDownload(enabled: Boolean)

    /**
     * Deletes orphan/cache files; library records are untouched.
     */
    fun clearCache()

    /**
     * Immutable UI state for the Settings screen.
     *
     * @property bitrate current download bitrate preference.
     * @property storagePath absolute path to the app-scoped music directory.
     * @property concurrentDownloads maximum number of parallel downloads.
     * @property autoDownloadOnAdd `true` when auto-download is enabled.
     * @property cacheStats current cache statistics (orphan file count and size).
     * @property isClearingCache `true` while the clear-cache operation is running.
     */
    data class SettingsUiState(
        val bitrate: Bitrate = Bitrate.MEDIUM,
        val storagePath: String = "",
        val concurrentDownloads: Int = 1,
        val autoDownloadOnAdd: Boolean = false,
        val cacheStats: CacheStats = CacheStats(0, 0),
        val isClearingCache: Boolean = false,
    )
}
