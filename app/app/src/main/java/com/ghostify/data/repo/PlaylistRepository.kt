package com.ghostify.data.repo

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * Coordinates playlists and their songs.
 *
 * Multi-statement writes go through [TransactionRunner] so a failure mid-way leaves
 * no half-written state (e.g. a playlist row whose track batch failed).
 */
class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val transactions: TransactionRunner
) {

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun observePlaylist(id: String): Flow<PlaylistEntity?> = playlistDao.observeById(id)

    fun observeSongs(playlistId: String): Flow<List<SongEntity>> =
        songDao.observeSongsForPlaylist(playlistId)

    suspend fun getPlaylist(id: String): PlaylistEntity? = playlistDao.getById(id)

    suspend fun getSongs(playlistId: String): List<SongEntity> =
        songDao.getSongsForPlaylist(playlistId)

    suspend fun savePlaylist(playlist: PlaylistEntity) {
        playlistDao.insert(playlist)
    }

    /**
     * The "Add playlist" flow: persist the playlist header and its full track list
     * atomically. `track_count` is derived from the batch, never trusted from callers.
     *
     * Saving the same Spotify playlist again is idempotent — the previous copy is
     * removed inside the same transaction so `spotify_id` UNIQUE never trips.
     */
    suspend fun savePlaylistWithSongs(playlist: PlaylistEntity, songs: List<SongEntity>) {
        transactions.withinTransaction {
            playlistDao.getBySpotifyId(playlist.spotifyId)?.let { existing ->
                songDao.deleteSongsForPlaylist(existing.id)
                playlistDao.deleteById(existing.id)
            }
            playlistDao.insert(playlist.copy(trackCount = songs.size))
            songDao.insertAll(songs)
        }
    }

    suspend fun updatePlaylist(playlist: PlaylistEntity) {
        playlistDao.update(playlist)
    }

    /** Deletes the playlist; tracks are removed via the FK ON DELETE CASCADE. */
    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deleteById(playlistId)
    }

    /** Recomputes and stores `track_count` from the live songs rows (used by re-sync). */
    suspend fun refreshTrackCount(playlistId: String) {
        transactions.withinTransaction {
            val playlist = playlistDao.getById(playlistId) ?: return@withinTransaction
            playlistDao.update(playlist.copy(trackCount = songDao.countForPlaylist(playlistId)))
        }
    }
}
