package xyz.botolog.ghostify.data.repo

import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Track-level operations: querying, batch persistence and status transitions.
 *
 * Used by the download manager and the re-sync diff. All multi-statement writes
 * go through [TransactionRunner] for atomicity.
 *
 * @property songDao The underlying DAO for the `songs` table.
 * @property transactions Provides transactional write boundaries.
 */
class SongRepository(
    private val songDao: SongDao,
    private val transactions: TransactionRunner,
) {

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Observes all songs in a playlist, ordered by position.
     *
     * @param playlistId The playlist to observe.
     * @return A [Flow] emitting the song list on every DB change.
     */
    fun observeSongs(playlistId: String): Flow<List<SongEntity>> {
        Timber.i("SongRepository.observeSongs: START")
        val result = songDao.observeSongsForPlaylist(playlistId)
        Timber.i("SongRepository.observeSongs: returning $result")
        return result
    }

    /**
     * Observes songs in a playlist filtered by status.
     *
     * @param playlistId The playlist to filter within.
     * @param statuses The statuses to include.
     * @return A [Flow] emitting the filtered song list.
     */
    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>> {
        Timber.i("SongRepository.observeSongsByStatus: START")
        val result = songDao.observeSongsByStatus(playlistId, statuses)
        Timber.i("SongRepository.observeSongsByStatus: returning $result")
        return result
    }

    /**
     * One-shot fetch of all songs in a playlist.
     *
     * @param playlistId The playlist to query.
     * @return The ordered list of songs.
     */
    suspend fun getSongs(playlistId: String): List<SongEntity> {
        Timber.i("SongRepository.getSongs: START")
        val result = songDao.getSongsForPlaylist(playlistId)
        Timber.i("SongRepository.getSongs: returning $result")
        return result
    }

    /**
     * One-shot fetch of songs filtered by status.
     *
     * @param playlistId The playlist to filter within.
     * @param statuses The statuses to include.
     * @return The filtered list of songs.
     */
    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity> {
        Timber.i("SongRepository.getSongsByStatus: START")
        val result = songDao.getSongsByStatus(playlistId, statuses)
        Timber.i("SongRepository.getSongsByStatus: returning $result")
        return result
    }

    /**
     * Fetches a single song by its primary key.
     *
     * @param id The song row id.
     * @return The song, or `null` if not found.
     */
    suspend fun getSong(id: String): SongEntity? {
        Timber.i("SongRepository.getSong: START")
        val result = songDao.getById(id)
        Timber.i("SongRepository.getSong: returning $result")
        return result
    }

    /**
     * Looks up a song by playlist and Spotify track id.
     *
     * Primary lookup used by the re-sync diff.
     *
     * @param playlistId The playlist to search within.
     * @param spotifyId The Spotify track id.
     * @return The matching song, or `null`.
     */
    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity? {
        Timber.i("SongRepository.getByPlaylistAndSpotify: START")
        val result = songDao.getByPlaylistAndSpotify(playlistId, spotifyId)
        Timber.i("SongRepository.getByPlaylistAndSpotify: returning $result")
        return result
    }

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Batch-inserts songs inside a transaction.
     *
     * @param songs The songs to insert.
     */
    suspend fun insertAll(songs: List<SongEntity>) {
        Timber.i("SongRepository.insertAll: START")
        transactions.withinTransaction { songDao.insertAll(songs) }
    }

    /**
     * Updates a single song (matched by primary key).
     *
     * @param song The song with updated fields.
     */
    suspend fun update(song: SongEntity) {
        Timber.i("SongRepository.update: START")
        songDao.update(song)
    }

    /**
     * Batch-updates songs inside a transaction.
     *
     * @param songs The songs with updated fields.
     */
    suspend fun updateAll(songs: List<SongEntity>) {
        Timber.i("SongRepository.updateAll: START")
        transactions.withinTransaction { songDao.updateAll(songs) }
    }

    /**
     * Deletes a single song.
     *
     * @param song The song to delete.
     */
    suspend fun delete(song: SongEntity) {
        Timber.i("SongRepository.delete: START")
        songDao.delete(song)
    }

    /**
     * Batch transition used by "download all": PENDING/FAILED → QUEUED → DOWNLOADING.
     *
     * @param ids The song row ids to update.
     * @param status The target status.
     */
    suspend fun setStatus(ids: List<String>, status: SongStatus) {
        Timber.i("SongRepository.setStatus: START")
        if (ids.isEmpty()) return
        Timber.d("SongRepository: state changed to $status")
        transactions.withinTransaction { songDao.updateStatus(ids, status) }
    }

    /**
     * Removes tracks that disappeared from the Spotify playlist during re-sync.
     *
     * @param playlistId The playlist to delete from.
     * @param spotifyIds The Spotify track ids to remove.
     */
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>) {
        Timber.i("SongRepository.deleteBySpotifyIds: START")
        transactions.withinTransaction { songDao.deleteBySpotifyIds(playlistId, spotifyIds) }
    }

    // ── Aggregate ─────────────────────────────────────────────────────

    /**
     * Counts all songs in a playlist.
     *
     * @param playlistId The playlist to count.
     * @return The total number of songs.
     */
    suspend fun countForPlaylist(playlistId: String): Int {
        Timber.i("SongRepository.countForPlaylist: START")
        val result = songDao.countForPlaylist(playlistId)
        Timber.i("SongRepository.countForPlaylist: returning $result")
        return result
    }

    /**
     * Counts songs in a playlist filtered by status.
     *
     * @param playlistId The playlist to count within.
     * @param status The status to filter on.
     * @return The number of matching songs.
     */
    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int {
        Timber.i("SongRepository.countForPlaylistByStatus: START")
        val result = songDao.countForPlaylistByStatus(playlistId, status)
        Timber.i("SongRepository.countForPlaylistByStatus: returning $result")
        return result
    }
}
