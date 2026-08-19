package com.ghostify.data.repo

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.PlaylistOrigin
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

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

    fun observePlaylists(): Flow<List<PlaylistEntity>> {
        Timber.i("PlaylistRepository.observePlaylists: START")
        val result = playlistDao.observeAll()
        Timber.i("PlaylistRepository.observePlaylists: returning $result")
        return result
    }

    fun observePlaylist(id: String): Flow<PlaylistEntity?> {
        Timber.i("PlaylistRepository.observePlaylist: START")
        val result = playlistDao.observeById(id)
        Timber.i("PlaylistRepository.observePlaylist: returning $result")
        return result
    }

    fun observeSongs(playlistId: String): Flow<List<SongEntity>> {
        Timber.i("PlaylistRepository.observeSongs: START")
        val result = songDao.observeSongsForPlaylist(playlistId)
        Timber.i("PlaylistRepository.observeSongs: returning $result")
        return result
    }

    suspend fun getPlaylist(id: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getPlaylist: START")
        val result = playlistDao.getById(id)
        Timber.i("PlaylistRepository.getPlaylist: returning $result")
        return result
    }

    /**
     * Looks a saved Spotify playlist up by its id (used by the Add-dialog
     * duplicate check). Only matches Spotify-origin rows.
     */
    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getBySpotifyId: START")
        val result = playlistDao.getBySpotifyId(spotifyId)
        Timber.i("PlaylistRepository.getBySpotifyId: returning $result")
        return result
    }

    /**
     * Looks a saved YouTube playlist up by its URL (used by the Add-dialog
     * duplicate check). Only matches YouTube-origin rows.
     */
    suspend fun getByYtPlaylistId(ytPlaylistId: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getByYtPlaylistId: START")
        val result = playlistDao.getByYtPlaylistId(ytPlaylistId)
        Timber.i("PlaylistRepository.getByYtPlaylistId: returning $result")
        return result
    }

    suspend fun getSongs(playlistId: String): List<SongEntity> {
        Timber.i("PlaylistRepository.getSongs: START")
        val result = songDao.getSongsForPlaylist(playlistId)
        Timber.i("PlaylistRepository.getSongs: returning $result")
        return result
    }

    suspend fun savePlaylist(playlist: PlaylistEntity) {
        Timber.i("PlaylistRepository.savePlaylist: START")
        playlistDao.insert(playlist)
    }

    /**
     * The "Add playlist" flow: persist the playlist header and its full track list
     * atomically. `track_count` is derived from the batch, never trusted from callers.
     *
     * Saving the same playlist again is idempotent — the previous copy is removed
     * inside the same transaction so the unique `(spotify_id, origin)` index never
     * trips.
     */
    suspend fun savePlaylistWithSongs(playlist: PlaylistEntity, songs: List<SongEntity>) {
        Timber.i("PlaylistRepository.savePlaylistWithSongs: START")
        transactions.withinTransaction {
            when (playlist.origin) {
                PlaylistOrigin.SPOTIFY ->
                    playlistDao.getBySpotifyId(playlist.spotifyId)?.let { existing ->
                        songDao.deleteSongsForPlaylist(existing.id)
                        playlistDao.deleteById(existing.id)
                    }
                PlaylistOrigin.YOUTUBE ->
                    playlistDao.getByYtPlaylistId(playlist.spotifyId)?.let { existing ->
                        songDao.deleteSongsForPlaylist(existing.id)
                        playlistDao.deleteById(existing.id)
                    }
            }
            playlistDao.insert(playlist.copy(trackCount = songs.size))
            songDao.insertAll(songs)
        }
    }

    suspend fun updatePlaylist(playlist: PlaylistEntity) {
        Timber.i("PlaylistRepository.updatePlaylist: START")
        playlistDao.update(playlist)
    }

    /** Deletes the playlist; tracks are removed via the FK ON DELETE CASCADE. */
    suspend fun deletePlaylist(playlistId: String) {
        Timber.i("PlaylistRepository.deletePlaylist: START")
        playlistDao.deleteById(playlistId)
    }

    /** Recomputes and stores `track_count` from the live songs rows (used by re-sync). */
    suspend fun refreshTrackCount(playlistId: String) {
        Timber.i("PlaylistRepository.refreshTrackCount: START")
        transactions.withinTransaction {
            val playlist = playlistDao.getById(playlistId) ?: return@withinTransaction
            playlistDao.update(playlist.copy(trackCount = songDao.countForPlaylist(playlistId)))
        }
    }

    /** Reorder a playlist to a new position. All other playlists shift accordingly. */
    suspend fun reorderPlaylist(playlistId: String, newSortOrder: Int) {
        Timber.i("PlaylistRepository.reorderPlaylist: START $playlistId -> $newSortOrder")
        playlistDao.setSortOrder(playlistId, newSortOrder)
    }

    /** Reorder a song within a playlist. */
    suspend fun reorderSong(songId: String, newPosition: Int) {
        Timber.i("PlaylistRepository.reorderSong: START $songId -> $newPosition")
        songDao.setPosition(songId, newPosition)
    }
}
