package com.ghostify.data.db.dao

import com.ghostify.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

/**
 * Playlist-header persistence contract. Method-for-method mirror of the Room
 * `PlaylistDao` in the `database` component (see [SongDao] for rationale).
 */
interface PlaylistDao {

    fun observeAll(): Flow<List<PlaylistEntity>>

    fun observeById(id: String): Flow<PlaylistEntity?>

    suspend fun getById(id: String): PlaylistEntity?

    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity?

    suspend fun insert(playlist: PlaylistEntity)

    suspend fun insertAll(playlists: List<PlaylistEntity>)

    suspend fun update(playlist: PlaylistEntity)

    suspend fun delete(playlist: PlaylistEntity)

    suspend fun deleteById(id: String)

    suspend fun count(): Int
}
