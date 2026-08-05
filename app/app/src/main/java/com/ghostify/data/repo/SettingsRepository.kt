package com.ghostify.data.repo

import com.ghostify.data.db.dao.SettingDao
import com.ghostify.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Typed access to the `settings` key/value store.
 *
 * Unknown keys fall back to defaults, and stored values that fail to parse
 * (e.g. a corrupted value) also fall back — the repository never throws on read.
 */
class SettingsRepository(private val settingDao: SettingDao) {

    companion object {
        const val KEY_STORAGE_DIR = "storage_dir"
        const val KEY_BITRATE = "default_bitrate"
        const val KEY_CONCURRENCY = "concurrent_downloads"
        const val KEY_AUTO_DOWNLOAD = "auto_download_on_add"

        const val DEFAULT_STORAGE_DIR = "ghostify"
        const val DEFAULT_BITRATE = "320"
        const val DEFAULT_CONCURRENCY = 2
        const val DEFAULT_AUTO_DOWNLOAD = false
    }

    fun observeAll(): Flow<Map<String, String>> {
        Timber.i("SettingsRepository.observeAll: START")
        val result = settingDao.observeAll().map { rows -> rows.associate { it.key to it.value } }
        Timber.i("SettingsRepository.observeAll: returning $result")
        return result
    }

    fun observe(key: String, default: String): Flow<String> {
        Timber.i("SettingsRepository.observe: START")
        val result = settingDao.observeValue(key).map { it ?: default }
        Timber.i("SettingsRepository.observe: returning $result")
        return result
    }

    suspend fun get(key: String, default: String): String {
        Timber.i("SettingsRepository.get: START")
        val result = settingDao.getValue(key) ?: default
        Timber.i("SettingsRepository.get: returning $result")
        return result
    }

    suspend fun set(key: String, value: String) {
        Timber.i("SettingsRepository.set: START")
        settingDao.upsert(SettingEntity(key, value))
    }

    // --- storage_dir -----------------------------------------------------

    fun observeStorageDir(): Flow<String> {
        Timber.i("SettingsRepository.observeStorageDir: START")
        val result = observe(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)
        Timber.i("SettingsRepository.observeStorageDir: returning $result")
        return result
    }

    suspend fun getStorageDir(): String {
        Timber.i("SettingsRepository.getStorageDir: START")
        val result = get(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)
        Timber.i("SettingsRepository.getStorageDir: returning $result")
        return result
    }

    suspend fun setStorageDir(value: String) {
        Timber.i("SettingsRepository.setStorageDir: START")
        set(KEY_STORAGE_DIR, value)
    }

    // --- default_bitrate --------------------------------------------------

    fun observeBitrate(): Flow<String> {
        Timber.i("SettingsRepository.observeBitrate: START")
        val result = observe(KEY_BITRATE, DEFAULT_BITRATE)
        Timber.i("SettingsRepository.observeBitrate: returning $result")
        return result
    }

    suspend fun getBitrate(): String {
        Timber.i("SettingsRepository.getBitrate: START")
        val result = get(KEY_BITRATE, DEFAULT_BITRATE)
        Timber.i("SettingsRepository.getBitrate: returning $result")
        return result
    }

    suspend fun setBitrate(value: String) {
        Timber.i("SettingsRepository.setBitrate: START")
        set(KEY_BITRATE, value)
    }

    // --- concurrent_downloads ---------------------------------------------

    fun observeConcurrency(): Flow<Int> {
        Timber.i("SettingsRepository.observeConcurrency: START")
        val result = observe(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString()).map { it.toIntOrNull() ?: DEFAULT_CONCURRENCY }
        Timber.i("SettingsRepository.observeConcurrency: returning $result")
        return result
    }

    suspend fun getConcurrency(): Int {
        Timber.i("SettingsRepository.getConcurrency: START")
        val result = get(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString()).toIntOrNull() ?: DEFAULT_CONCURRENCY
        Timber.i("SettingsRepository.getConcurrency: returning $result")
        return result
    }

    suspend fun setConcurrency(value: Int) {
        Timber.i("SettingsRepository.setConcurrency: START")
        set(KEY_CONCURRENCY, value.toString())
    }

    // --- auto_download_on_add ---------------------------------------------

    fun observeAutoDownload(): Flow<Boolean> {
        Timber.i("SettingsRepository.observeAutoDownload: START")
        val result = observe(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString()).map {
            when (it.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> DEFAULT_AUTO_DOWNLOAD
            }
        }
        Timber.i("SettingsRepository.observeAutoDownload: returning $result")
        return result
    }

    suspend fun getAutoDownload(): Boolean {
        Timber.i("SettingsRepository.getAutoDownload: START")
        val result = when (get(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString()).lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> DEFAULT_AUTO_DOWNLOAD
        }
        Timber.i("SettingsRepository.getAutoDownload: returning $result")
        return result
    }

    suspend fun setAutoDownload(value: Boolean) {
        Timber.i("SettingsRepository.setAutoDownload: START")
        set(KEY_AUTO_DOWNLOAD, value.toString())
    }
}
