package xyz.botolog.ghostify.data.repo

import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Coordinates playlists and their songs.
 *
 * Multi-statement writes go through [TransactionRunner] so a failure mid-way leaves
 * no half-written state (e.g. a playlist row whose track batch failed).
 *
 * @property playlistDao The underlying DAO for the `playlists` table.
 * @property songDao The underlying DAO for the `songs` table.
 * @property transactions Provides transactional write boundaries.
 */
class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val transactions: TransactionRunner,
) {

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Observes all playlists, ordered by sort position then creation time.
     *
     * @return A [Flow] emitting the full playlist list on every DB change.
     */
    fun observePlaylists(): Flow<List<PlaylistEntity>> {
        Timber.i("PlaylistRepository.observePlaylists: START")
        val result = playlistDao.observeAll()
        Timber.i("PlaylistRepository.observePlaylists: returning $result")
        return result
    }

    /**
     * Observes a single playlist by its primary key.
     *
     * @param id The playlist row id.
     * @return A [Flow] emitting the playlist, or `null` if not found.
     */
    fun observePlaylist(id: String): Flow<PlaylistEntity?> {
        Timber.i("PlaylistRepository.observePlaylist: START")
        val result = playlistDao.observeById(id)
        Timber.i("PlaylistRepository.observePlaylist: returning $result")
        return result
    }

    /**
     * Observes all songs in a playlist, ordered by position.
     *
     * @param playlistId The playlist whose songs to observe.
     * @return A [Flow] emitting the song list on every DB change.
     */
    fun observeSongs(playlistId: String): Flow<List<SongEntity>> {
        Timber.i("PlaylistRepository.observeSongs: START")
        val result = songDao.observeSongsForPlaylist(playlistId)
        Timber.i("PlaylistRepository.observeSongs: returning $result")
        return result
    }

    /**
     * One-shot fetch of a playlist by its primary key.
     *
     * @param id The playlist row id.
     * @return The playlist, or `null` if not found.
     */
    suspend fun getPlaylist(id: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getPlaylist: START")
        val result = playlistDao.getById(id)
        Timber.i("PlaylistRepository.getPlaylist: returning $result")
        return result
    }

    /**
     * Looks up a saved Spotify playlist by its Spotify id.
     *
     * Used by the Add-dialog duplicate check. Only matches Spotify-origin rows.
     *
     * @param spotifyId The Spotify playlist id.
     * @return The matching playlist, or `null`.
     */
    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getBySpotifyId: START")
        val result = playlistDao.getBySpotifyId(spotifyId)
        Timber.i("PlaylistRepository.getBySpotifyId: returning $result")
        return result
    }

    /**
     * Looks up a saved YouTube playlist by its URL.
     *
     * Used by the Add-dialog duplicate check. Only matches YouTube-origin rows.
     *
     * @param ytPlaylistId The YouTube playlist URL / identifier.
     * @return The matching playlist, or `null`.
     */
    suspend fun getByYtPlaylistId(ytPlaylistId: String): PlaylistEntity? {
        Timber.i("PlaylistRepository.getByYtPlaylistId: START")
        val result = playlistDao.getByYtPlaylistId(ytPlaylistId)
        Timber.i("PlaylistRepository.getByYtPlaylistId: returning $result")
        return result
    }

    /**
     * One-shot fetch of all songs in a playlist.
     *
     * @param playlistId The playlist to query.
     * @return The ordered list of songs.
     */
    suspend fun getSongs(playlistId: String): List<SongEntity> {
        Timber.i("PlaylistRepository.getSongs: START")
        val result = songDao.getSongsForPlaylist(playlistId)
        Timber.i("PlaylistRepository.getSongs: returning $result")
        return result
    }

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Inserts a single playlist.
     *
     * @param playlist The playlist to persist.
     */
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
     *
     * @param playlist The playlist metadata to save.
     * @param songs The full ordered track list.
     */
    suspend fun savePlaylistWithSongs(playlist: PlaylistEntity, songs: List<SongEntity>) {
        Timber.i("PlaylistRepository.savePlaylistWithSongs: START")
        transactions.withinTransaction {
            removeExistingPlaylist(playlist)
            playlistDao.insert(playlist.copy(trackCount = songs.size))
            songDao.insertAll(songs)
        }
    }

    /**
     * Updates a single playlist in place.
     *
     * @param playlist The playlist with updated fields.
     */
    suspend fun updatePlaylist(playlist: PlaylistEntity) {
        Timber.i("PlaylistRepository.updatePlaylist: START")
        playlistDao.update(playlist)
    }

    /**
     * Deletes the playlist; tracks are removed via the FK ON DELETE CASCADE.
     *
     * @param playlistId The playlist row id to delete.
     */
    suspend fun deletePlaylist(playlistId: String) {
        Timber.i("PlaylistRepository.deletePlaylist: START")
        playlistDao.deleteById(playlistId)
    }

    /**
     * Recomputes and stores `track_count` from the live songs rows (used by re-sync).
     *
     * @param playlistId The playlist whose count should be refreshed.
     */
    suspend fun refreshTrackCount(playlistId: String) {
        Timber.i("PlaylistRepository.refreshTrackCount: START")
        transactions.withinTransaction {
            val playlist = playlistDao.getById(playlistId) ?: return@withinTransaction
            playlistDao.update(playlist.copy(trackCount = songDao.countForPlaylist(playlistId)))
        }
    }

    /**
     * Reorders a playlist to a new position. All other playlists shift accordingly.
     *
     * @param playlistId The playlist to reorder.
     * @param newSortOrder The target sort position (lower = higher in list).
     */
    suspend fun reorderPlaylist(playlistId: String, newSortOrder: Int) {
        Timber.i("PlaylistRepository.reorderPlaylist: START $playlistId -> $newSortOrder")
        playlistDao.setSortOrder(playlistId, newSortOrder)
    }

    /**
     * Reorders a song within a playlist.
     *
     * @param songId The song to reorder.
     * @param newPosition The target 0-based position within the playlist.
     */
    suspend fun reorderSong(songId: String, newPosition: Int) {
        Timber.i("PlaylistRepository.reorderSong: START $songId -> $newPosition")
        songDao.setPosition(songId, newPosition)
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Removes an existing playlist with the same Spotify/YouTube id if one exists,
     * ensuring the unique `(spotify_id, origin)` constraint is never violated.
     */
    private suspend fun removeExistingPlaylist(playlist: PlaylistEntity) {
        val existing = findExistingPlaylist(playlist) ?: return
        songDao.deleteSongsForPlaylist(existing.id)
        playlistDao.deleteById(existing.id)
    }

    /**
     * Finds an existing playlist matching the given playlist's remote identifier
     * and origin.
     */
    private suspend fun findExistingPlaylist(playlist: PlaylistEntity): PlaylistEntity? =
        when (playlist.origin) {
            PlaylistOrigin.SPOTIFY -> playlistDao.getBySpotifyId(playlist.spotifyId)
            PlaylistOrigin.YOUTUBE -> playlistDao.getByYtPlaylistId(playlist.spotifyId)
        }
}
