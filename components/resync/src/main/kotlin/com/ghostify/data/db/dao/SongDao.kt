package com.ghostify.data.db.dao

import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow

/**
 * Track-level persistence contract used by the sync diff.
 *
 * Method-for-method mirror of the Room `SongDao` in the `database` component
 * (Room annotations omitted: they are compile-time/KSP metadata only and never
 * change these signatures). When wired into the app, the Room-annotated DAO
 * satisfies this interface directly.
 */
interface SongDao {

    fun observeSongsForPlaylist(playlistId: String): Flow<List<SongEntity>>

    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>>

    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity>

    fun observeById(id: String): Flow<SongEntity?>

    suspend fun getById(id: String): SongEntity?

    /** Primary lookup used by the re-sync diff: does this Spotify track exist here already? */
    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity?

    suspend fun insert(song: SongEntity)

    suspend fun insertAll(songs: List<SongEntity>)

    suspend fun update(song: SongEntity)

    suspend fun updateAll(songs: List<SongEntity>)

    suspend fun delete(song: SongEntity)

    suspend fun deleteSongsForPlaylist(playlistId: String)

    /** Remove tracks that vanished from the Spotify playlist (used by re-sync). */
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>)

    suspend fun updateStatus(ids: List<String>, status: SongStatus)

    suspend fun countForPlaylist(playlistId: String): Int

    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int
}
