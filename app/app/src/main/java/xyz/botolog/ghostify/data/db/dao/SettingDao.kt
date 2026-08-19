package xyz.botolog.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import xyz.botolog.ghostify.data.db.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}
