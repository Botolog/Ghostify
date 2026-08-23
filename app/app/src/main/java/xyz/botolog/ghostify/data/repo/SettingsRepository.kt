package xyz.botolog.ghostify.data.repo

import xyz.botolog.ghostify.data.db.dao.SettingDao
import xyz.botolog.ghostify.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Typed access to the `settings` key/value store.
 *
 * Unknown keys fall back to defaults, and stored values that fail to parse
 * (e.g. a corrupted value) also fall back — the repository never throws on read.
 *
 * @property settingDao The underlying DAO for the `settings` table.
 */
class SettingsRepository(private val settingDao: SettingDao) {

    companion object {

        // ── Keys ──────────────────────────────────────────────────────

        /** Preference key for the music storage directory. */
        const val KEY_STORAGE_DIR = "storage_dir"

        /** Preference key for the default download bitrate. */
        const val KEY_BITRATE = "default_bitrate"

        /** Preference key for the maximum concurrent downloads. */
        const val KEY_CONCURRENCY = "concurrent_downloads"

        /** Preference key for auto-downloading tracks on playlist add. */
        const val KEY_AUTO_DOWNLOAD = "auto_download_on_add"

        // ── Defaults ──────────────────────────────────────────────────

        /** Default storage directory name. */
        const val DEFAULT_STORAGE_DIR = "ghostify"

        /** Default bitrate as a string (e.g. "128", "320"). */
        const val DEFAULT_BITRATE = "320"

        /** Default number of concurrent downloads. */
        const val DEFAULT_CONCURRENCY = 2

        /** Default auto-download flag. */
        const val DEFAULT_AUTO_DOWNLOAD = false

        /** Strings recognised as truthy when parsing boolean settings. */
        private val TRUTHY_VALUES = setOf("true", "1", "yes")

        /** Strings recognised as falsy when parsing boolean settings. */
        private val FALSY_VALUES = setOf("false", "0", "no")
    }

    // ── Generic accessors ─────────────────────────────────────────────

    /**
     * Observes all settings as a key-value map.
     *
     * @return A [Flow] emitting the full settings map on every DB change.
     */
    fun observeAll(): Flow<Map<String, String>> {
        Timber.i("SettingsRepository.observeAll: START")
        val result = settingDao.observeAll().map { rows -> rows.associate { it.key to it.value } }
        Timber.i("SettingsRepository.observeAll: returning $result")
        return result
    }

    /**
     * Observes a single setting value, falling back to [default] when absent.
     *
     * @param key The preference key.
     * @param default The fallback value.
     * @return A [Flow] emitting the resolved string.
     */
    fun observe(key: String, default: String): Flow<String> {
        Timber.i("SettingsRepository.observe: START")
        val result = settingDao.observeValue(key).map { it ?: default }
        Timber.i("SettingsRepository.observe: returning $result")
        return result
    }

    /**
     * One-shot fetch of a single setting value, falling back to [default].
     *
     * @param key The preference key.
     * @param default The fallback value.
     * @return The resolved string.
     */
    suspend fun get(key: String, default: String): String {
        Timber.i("SettingsRepository.get: START")
        val result = settingDao.getValue(key) ?: default
        Timber.i("SettingsRepository.get: returning $result")
        return result
    }

    /**
     * Persists a setting value (upsert).
     *
     * @param key The preference key.
     * @param value The value to store.
     */
    suspend fun set(key: String, value: String) {
        Timber.i("SettingsRepository.set: START")
        settingDao.upsert(SettingEntity(key, value))
    }

    // ── storage_dir ───────────────────────────────────────────────────

    /**
     * Observes the storage directory path.
     *
     * @return A [Flow] emitting the current storage directory name.
     */
    fun observeStorageDir(): Flow<String> {
        Timber.i("SettingsRepository.observeStorageDir: START")
        val result = observe(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)
        Timber.i("SettingsRepository.observeStorageDir: returning $result")
        return result
    }

    /**
     * One-shot fetch of the storage directory path.
     *
     * @return The storage directory name.
     */
    suspend fun getStorageDir(): String {
        Timber.i("SettingsRepository.getStorageDir: START")
        val result = get(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)
        Timber.i("SettingsRepository.getStorageDir: returning $result")
        return result
    }

    /**
     * Persists the storage directory path.
     *
     * @param value The new storage directory name.
     */
    suspend fun setStorageDir(value: String) {
        Timber.i("SettingsRepository.setStorageDir: START")
        set(KEY_STORAGE_DIR, value)
    }

    // ── default_bitrate ───────────────────────────────────────────────

    /**
     * Observes the default download bitrate.
     *
     * @return A [Flow] emitting the bitrate string (e.g. "320").
     */
    fun observeBitrate(): Flow<String> {
        Timber.i("SettingsRepository.observeBitrate: START")
        val result = observe(KEY_BITRATE, DEFAULT_BITRATE)
        Timber.i("SettingsRepository.observeBitrate: returning $result")
        return result
    }

    /**
     * One-shot fetch of the default download bitrate.
     *
     * @return The bitrate string.
     */
    suspend fun getBitrate(): String {
        Timber.i("SettingsRepository.getBitrate: START")
        val result = get(KEY_BITRATE, DEFAULT_BITRATE)
        Timber.i("SettingsRepository.getBitrate: returning $result")
        return result
    }

    /**
     * Persists the default download bitrate.
     *
     * @param value The new bitrate string (e.g. "128", "320").
     */
    suspend fun setBitrate(value: String) {
        Timber.i("SettingsRepository.setBitrate: START")
        set(KEY_BITRATE, value)
    }

    // ── concurrent_downloads ──────────────────────────────────────────

    /**
     * Observes the maximum number of concurrent downloads.
     *
     * @return A [Flow] emitting the concurrency limit.
     */
    fun observeConcurrency(): Flow<Int> {
        Timber.i("SettingsRepository.observeConcurrency: START")
        val result = observe(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString())
            .map { it.toIntOrNull() ?: DEFAULT_CONCURRENCY }
        Timber.i("SettingsRepository.observeConcurrency: returning $result")
        return result
    }

    /**
     * One-shot fetch of the concurrency limit.
     *
     * @return The concurrency limit.
     */
    suspend fun getConcurrency(): Int {
        Timber.i("SettingsRepository.getConcurrency: START")
        val result = get(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString())
            .toIntOrNull() ?: DEFAULT_CONCURRENCY
        Timber.i("SettingsRepository.getConcurrency: returning $result")
        return result
    }

    /**
     * Persists the concurrency limit.
     *
     * @param value The new concurrency limit.
     */
    suspend fun setConcurrency(value: Int) {
        Timber.i("SettingsRepository.setConcurrency: START")
        set(KEY_CONCURRENCY, value.toString())
    }

    // ── auto_download_on_add ──────────────────────────────────────────

    /**
     * Observes whether tracks should be auto-downloaded when a playlist is added.
     *
     * @return A [Flow] emitting the boolean flag.
     */
    fun observeAutoDownload(): Flow<Boolean> {
        Timber.i("SettingsRepository.observeAutoDownload: START")
        val result = observe(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString())
            .map { parseBooleanSetting(it, DEFAULT_AUTO_DOWNLOAD) }
        Timber.i("SettingsRepository.observeAutoDownload: returning $result")
        return result
    }

    /**
     * One-shot fetch of the auto-download flag.
     *
     * @return `true` if auto-download is enabled.
     */
    suspend fun getAutoDownload(): Boolean {
        Timber.i("SettingsRepository.getAutoDownload: START")
        val raw = get(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString())
        val result = parseBooleanSetting(raw, DEFAULT_AUTO_DOWNLOAD)
        Timber.i("SettingsRepository.getAutoDownload: returning $result")
        return result
    }

    /**
     * Persists the auto-download flag.
     *
     * @param value `true` to enable auto-download on playlist add.
     */
    suspend fun setAutoDownload(value: Boolean) {
        Timber.i("SettingsRepository.setAutoDownload: START")
        set(KEY_AUTO_DOWNLOAD, value.toString())
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Parses a string setting as a boolean, accepting common truthy/falsy
     * representations and falling back to [fallback] for unrecognised values.
     *
     * @param raw The raw string value from the database.
     * @param fallback The default to use when [raw] is not a recognised boolean.
     * @return The parsed boolean.
     */
    private fun parseBooleanSetting(raw: String, fallback: Boolean): Boolean =
        when (raw.lowercase()) {
            in TRUTHY_VALUES -> true
            in FALSY_VALUES -> false
            else -> fallback
        }
}
