package com.ghostify.data.repo

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Track-level operations: querying, batch persistence and status transitions
 * (used by the download manager and the re-sync diff).
 */
class SongRepository(
    private val songDao: SongDao,
    private val transactions: TransactionRunner
) {

    fun observeSongs(playlistId: String): Flow<List<SongEntity>> {
        Timber.i("SongRepository.observeSongs: START")
        val result = songDao.observeSongsForPlaylist(playlistId)
        Timber.i("SongRepository.observeSongs: returning $result")
        return result
    }

    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>> {
        Timber.i("SongRepository.observeSongsByStatus: START")
        val result = songDao.observeSongsByStatus(playlistId, statuses)
        Timber.i("SongRepository.observeSongsByStatus: returning $result")
        return result
    }

    suspend fun getSongs(playlistId: String): List<SongEntity> {
        Timber.i("SongRepository.getSongs: START")
        val result = songDao.getSongsForPlaylist(playlistId)
        Timber.i("SongRepository.getSongs: returning $result")
        return result
    }

    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity> {
        Timber.i("SongRepository.getSongsByStatus: START")
        val result = songDao.getSongsByStatus(playlistId, statuses)
        Timber.i("SongRepository.getSongsByStatus: returning $result")
        return result
    }

    suspend fun getSong(id: String): SongEntity? {
        Timber.i("SongRepository.getSong: START")
        val result = songDao.getById(id)
        Timber.i("SongRepository.getSong: returning $result")
        return result
    }

    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity? {
        Timber.i("SongRepository.getByPlaylistAndSpotify: START")
        val result = songDao.getByPlaylistAndSpotify(playlistId, spotifyId)
        Timber.i("SongRepository.getByPlaylistAndSpotify: returning $result")
        return result
    }

    suspend fun insertAll(songs: List<SongEntity>) {
        Timber.i("SongRepository.insertAll: START")
        transactions.withinTransaction { songDao.insertAll(songs) }
    }

    suspend fun update(song: SongEntity) {
        Timber.i("SongRepository.update: START")
        songDao.update(song)
    }

    suspend fun updateAll(songs: List<SongEntity>) {
        Timber.i("SongRepository.updateAll: START")
        transactions.withinTransaction { songDao.updateAll(songs) }
    }

    suspend fun delete(song: SongEntity) {
        Timber.i("SongRepository.delete: START")
        songDao.delete(song)
    }

    /** Batch transition used by "download all": PENDING/FAILED -> QUEUED -> DOWNLOADING. */
    suspend fun setStatus(ids: List<String>, status: SongStatus) {
        Timber.i("SongRepository.setStatus: START")
        if (ids.isEmpty()) return
        Timber.d("SongRepository: state changed to $status")
        transactions.withinTransaction { songDao.updateStatus(ids, status) }
    }

    /** Remove tracks that disappeared from the Spotify playlist during re-sync. */
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>) {
        Timber.i("SongRepository.deleteBySpotifyIds: START")
        transactions.withinTransaction { songDao.deleteBySpotifyIds(playlistId, spotifyIds) }
    }

    suspend fun countForPlaylist(playlistId: String): Int {
        Timber.i("SongRepository.countForPlaylist: START")
        val result = songDao.countForPlaylist(playlistId)
        Timber.i("SongRepository.countForPlaylist: returning $result")
        return result
    }

    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int {
        Timber.i("SongRepository.countForPlaylistByStatus: START")
        val result = songDao.countForPlaylistByStatus(playlistId, status)
        Timber.i("SongRepository.countForPlaylistByStatus: returning $result")
        return result
    }
}
