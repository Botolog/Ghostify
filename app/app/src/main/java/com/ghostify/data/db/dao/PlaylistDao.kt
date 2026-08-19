package com.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.model.PlaylistStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY sort_order ASC, created_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE spotify_id = :spotifyId AND origin = 'SPOTIFY'")
    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE spotify_id = :ytPlaylistId AND origin = 'YOUTUBE'")
    suspend fun getByYtPlaylistId(ytPlaylistId: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(playlists: List<PlaylistEntity>)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    /** Playlist-level download status, used by the download manager. */
    @Query("SELECT status FROM playlists WHERE id = :id")
    suspend fun getPlaylistStatus(id: String): PlaylistStatus?

    @Query("UPDATE playlists SET status = :status WHERE id = :id")
    suspend fun setPlaylistStatus(id: String, status: PlaylistStatus)

    @Query("SELECT id FROM playlists")
    suspend fun allPlaylistIds(): List<String>

    @Query("UPDATE playlists SET sort_order = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)
}
