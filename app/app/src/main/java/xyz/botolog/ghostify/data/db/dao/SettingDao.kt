package xyz.botolog.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.botolog.ghostify.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `settings` key/value table.
 *
 * Uses upsert (insert-or-replace) semantics: calling [upsert] with an
 * existing key overwrites the previous value.
 */
@Dao
interface SettingDao {

    /**
     * Observes all settings as a list of [SettingEntity] rows.
     *
     * @return A [Flow] emitting the full settings list on every DB change.
     */
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    /**
     * Observes a single setting value by key.
     *
     * @param key The preference key.
     * @return A [Flow] emitting the value, or `null` if the key does not exist.
     */
    @Query("SELECT value FROM settings WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    /**
     * One-shot fetch of a single setting value by key.
     *
     * @param key The preference key.
     * @return The value, or `null` if the key does not exist.
     */
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun getValue(key: String): String?

    /**
     * Inserts or replaces a setting value.
     *
     * @param setting The setting to upsert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingEntity)

    /**
     * Deletes a setting by key.
     *
     * @param key The preference key to remove.
     */
    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}
