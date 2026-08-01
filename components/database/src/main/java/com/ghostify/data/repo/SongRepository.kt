package com.ghostify.data.repo

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow

/**
 * Track-level operations: querying, batch persistence and status transitions
 * (used by the download manager and the re-sync diff).
 */
class SongRepository(
    private val songDao: SongDao,
    private val transactions: TransactionRunner
) {

    fun observeSongs(playlistId: String): Flow<List<SongEntity>> =
        songDao.observeSongsForPlaylist(playlistId)

    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>> =
        songDao.observeSongsByStatus(playlistId, statuses)

    suspend fun getSongs(playlistId: String): List<SongEntity> =
        songDao.getSongsForPlaylist(playlistId)

    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity> =
        songDao.getSongsByStatus(playlistId, statuses)

    suspend fun getSong(id: String): SongEntity? = songDao.getById(id)

    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity? =
        songDao.getByPlaylistAndSpotify(playlistId, spotifyId)

    suspend fun insertAll(songs: List<SongEntity>) {
        transactions.withinTransaction { songDao.insertAll(songs) }
    }

    suspend fun update(song: SongEntity) {
        songDao.update(song)
    }

    suspend fun updateAll(songs: List<SongEntity>) {
        transactions.withinTransaction { songDao.updateAll(songs) }
    }

    suspend fun delete(song: SongEntity) {
        songDao.delete(song)
    }

    /** Batch transition used by "download all": PENDING/FAILED -> QUEUED -> DOWNLOADING. */
    suspend fun setStatus(ids: List<String>, status: SongStatus) {
        if (ids.isEmpty()) return
        transactions.withinTransaction { songDao.updateStatus(ids, status) }
    }

    /** Remove tracks that disappeared from the Spotify playlist during re-sync. */
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>) {
        transactions.withinTransaction { songDao.deleteBySpotifyIds(playlistId, spotifyIds) }
    }

    suspend fun countForPlaylist(playlistId: String): Int =
        songDao.countForPlaylist(playlistId)

    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int =
        songDao.countForPlaylistByStatus(playlistId, status)
}
