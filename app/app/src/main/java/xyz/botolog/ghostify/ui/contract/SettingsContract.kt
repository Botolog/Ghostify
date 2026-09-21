package xyz.botolog.ghostify.ui.contract

import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import xyz.botolog.ghostify.update.DownloadState
import xyz.botolog.ghostify.update.UpdateInfo
import kotlinx.coroutines.flow.SharedFlow
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

    /** One-shot event to request the UI to open the SAF directory picker. */
    val openStoragePicker: SharedFlow<Unit>

    /**
     * Update the download bitrate preference.
     *
     * @param bitrate the new bitrate to apply.
     */
    fun setBitrate(bitrate: Bitrate)

    /**
     * Triggers the SAF storage-picker flow via [openStoragePicker].
     */
    fun changeStoragePath()

    /**
     * Persists a chosen storage directory from the SAF picker.
     *
     * @param path the resolved filesystem path from the SAF picker.
     */
    fun setStoragePath(path: String)

    /**
     * Resets the storage path to the app-scoped default (getExternalFilesDir).
     */
    fun resetStoragePath()

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
     * Update the landscape controls side preference.
     *
     * @param side "left" or "right".
     */
    fun setLandscapeControlsSide(side: String)

    /**
     * Deletes orphan/cache files; library records are untouched.
     */
    fun clearCache()

    /**
     * Dismisses the migration dialog and clears the result.
     */
    fun dismissMigration()

    /**
     * Migrates all downloaded songs from the old storage path to the new one.
     */
    fun migrateSongs()

    /**
     * Checks for a new release on GitHub.
     */
    fun checkForUpdate()

    /**
     * Starts downloading the update APK after user confirmation.
     */
    fun confirmUpdate()

    /**
     * Dismisses the update dialog and clears the update info.
     */
    fun dismissUpdate()

    /**
     * Triggers installation of the downloaded update APK.
     */
    fun installUpdate()

    /**
     * Immutable UI state for the Settings screen.
     *
     * @property bitrate current download bitrate preference.
     * @property storagePath absolute path to the music directory.
     * @property concurrentDownloads maximum number of parallel downloads.
     * @property autoDownloadOnAdd `true` when auto-download is enabled.
     * @property cacheStats current cache statistics (orphan file count and size).
     * @property isClearingCache `true` while the clear-cache operation is running.
     * @property pendingMigrationPath if non-null, a storage change just happened and
     *   songs exist at the old path — the UI should show a migration dialog.
     * @property isMigrating `true` while a migration is in progress.
     * @property migrationResult summary after migration finishes (e.g. "12 moved, 2 failed").
     */
    data class SettingsUiState(
        val bitrate: Bitrate = Bitrate.MEDIUM,
        val storagePath: String = "",
        val concurrentDownloads: Int = 1,
        val autoDownloadOnAdd: Boolean = false,
        val landscapeControlsSide: String = "left",
        val cacheStats: CacheStats = CacheStats(0, 0),
        val isClearingCache: Boolean = false,
        val pendingMigrationPath: String? = null,
        val isMigrating: Boolean = false,
        val migrationResult: String? = null,
        val isCheckingUpdate: Boolean = false,
        val updateInfo: UpdateInfo? = null,
        val showUpdateDialog: Boolean = false,
        val downloadState: DownloadState = DownloadState.Idle,
        val updateError: String? = null,
    )
}
