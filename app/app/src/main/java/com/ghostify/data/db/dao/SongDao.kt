package com.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC, added_at ASC")
    fun observeSongsForPlaylist(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC, added_at ASC")
    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    @Query(
        "SELECT * FROM songs WHERE playlist_id = :playlistId AND status IN (:statuses) " +
            "ORDER BY position ASC, added_at ASC"
    )
    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE playlist_id = :playlistId AND status IN (:statuses) " +
            "ORDER BY position ASC, added_at ASC"
    )
    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    /** Primary lookup used by the re-sync diff: does this Spotify track exist here already? */
    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId AND spotify_id = :spotifyId")
    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Update
    suspend fun updateAll(songs: List<SongEntity>)

    @Delete
    suspend fun delete(song: SongEntity)

    @Query("DELETE FROM songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: String)

    /** Remove tracks that vanished from the Spotify playlist (used by re-sync). */
    @Query("DELETE FROM songs WHERE playlist_id = :playlistId AND spotify_id IN (:spotifyIds)")
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>)

    @Query("UPDATE songs SET status = :status WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<String>, status: SongStatus)

    /**
     * Single-row status write used by the download manager. `file_path` is only
     * written when non-null so a recovery reset never wipes a downloaded path;
     * `error` mirrors the last failure reason (nullable, typically only while FAILED).
     */
    @Query(
        "UPDATE songs SET status = :status, error = :error, " +
            "file_path = COALESCE(:filePath, file_path) WHERE id = :songId"
    )
    suspend fun setStatus(songId: String, status: SongStatus, filePath: String?, error: String?)

    @Query("SELECT COUNT(*) FROM songs WHERE playlist_id = :playlistId")
    suspend fun countForPlaylist(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM songs WHERE playlist_id = :playlistId AND status = :status")
    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int
}
