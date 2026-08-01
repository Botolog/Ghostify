package com.ghostify.data.repo

import com.ghostify.data.db.dao.SettingDao
import com.ghostify.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    fun observeAll(): Flow<Map<String, String>> =
        settingDao.observeAll().map { rows -> rows.associate { it.key to it.value } }

    fun observe(key: String, default: String): Flow<String> =
        settingDao.observeValue(key).map { it ?: default }

    suspend fun get(key: String, default: String): String =
        settingDao.getValue(key) ?: default

    suspend fun set(key: String, value: String) {
        settingDao.upsert(SettingEntity(key, value))
    }

    // --- storage_dir -----------------------------------------------------

    fun observeStorageDir(): Flow<String> = observe(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)

    suspend fun getStorageDir(): String = get(KEY_STORAGE_DIR, DEFAULT_STORAGE_DIR)

    suspend fun setStorageDir(value: String) = set(KEY_STORAGE_DIR, value)

    // --- default_bitrate --------------------------------------------------

    fun observeBitrate(): Flow<String> = observe(KEY_BITRATE, DEFAULT_BITRATE)

    suspend fun getBitrate(): String = get(KEY_BITRATE, DEFAULT_BITRATE)

    suspend fun setBitrate(value: String) = set(KEY_BITRATE, value)

    // --- concurrent_downloads ---------------------------------------------

    fun observeConcurrency(): Flow<Int> =
        observe(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString()).map { it.toIntOrNull() ?: DEFAULT_CONCURRENCY }

    suspend fun getConcurrency(): Int =
        get(KEY_CONCURRENCY, DEFAULT_CONCURRENCY.toString()).toIntOrNull() ?: DEFAULT_CONCURRENCY

    suspend fun setConcurrency(value: Int) = set(KEY_CONCURRENCY, value.toString())

    // --- auto_download_on_add ---------------------------------------------

    fun observeAutoDownload(): Flow<Boolean> =
        observe(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString()).map {
            when (it.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> DEFAULT_AUTO_DOWNLOAD
            }
        }

    suspend fun getAutoDownload(): Boolean =
        when (get(KEY_AUTO_DOWNLOAD, DEFAULT_AUTO_DOWNLOAD.toString()).lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> DEFAULT_AUTO_DOWNLOAD
        }

    suspend fun setAutoDownload(value: Boolean) = set(KEY_AUTO_DOWNLOAD, value.toString())
}
