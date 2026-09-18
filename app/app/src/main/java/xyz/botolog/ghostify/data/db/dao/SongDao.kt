package xyz.botolog.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `songs` table.
 *
 * Provides both [Flow]-based observation for reactive UI and one-shot `suspend`
 * queries for background work (sync diff, download manager).
 */
@Dao
interface SongDao {

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Observes all songs in a playlist, ordered by position then insertion time.
     *
     * @param playlistId The playlist to observe.
     * @return A [Flow] emitting the full ordered song list on every DB change.
     */
    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC, added_at ASC")
    fun observeSongsForPlaylist(playlistId: String): Flow<List<SongEntity>>

    /**
     * One-shot fetch of all songs in a playlist, ordered by position then insertion time.
     *
     * @param playlistId The playlist to query.
     * @return The ordered list of songs.
     */
    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY position ASC, added_at ASC")
    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    /**
     * One-shot fetch of songs for a playlist, filtered to DOWNLOADED with a file path, paginated.
     *
     * @param playlistId The playlist to query.
     * @param limit Maximum number of songs to return.
     * @param offset Number of songs to skip.
     * @return The ordered list of downloaded songs.
     */
    @Query(
        "SELECT * FROM songs WHERE playlist_id = :playlistId AND status = 'DOWNLOADED' AND file_path IS NOT NULL " +
            "ORDER BY position ASC, added_at ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getDownloadedSongsPaged(playlistId: String, limit: Int, offset: Int): List<SongEntity>

    /**
     * Observes songs filtered by status within a playlist.
     *
     * @param playlistId The playlist to filter within.
     * @param statuses The statuses to include in the result.
     * @return A [Flow] emitting the filtered song list.
     */
    @Query(
        "SELECT * FROM songs WHERE playlist_id = :playlistId AND status IN (:statuses) " +
            "ORDER BY position ASC, added_at ASC",
    )
    fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>>

    /**
     * One-shot fetch of songs filtered by status within a playlist.
     *
     * @param playlistId The playlist to filter within.
     * @param statuses The statuses to include in the result.
     * @return The filtered list of songs.
     */
    @Query(
        "SELECT * FROM songs WHERE playlist_id = :playlistId AND status IN (:statuses) " +
            "ORDER BY position ASC, added_at ASC",
    )
    suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity>

    /**
     * Observes the lyrics column for a single song by its primary key.
     *
     * @param id The song row id.
     * @return A [Flow] emitting the lyrics text, or `null` if unavailable.
     */
    @Query("SELECT lyrics FROM songs WHERE id = :id")
    fun observeLyricsById(id: String): Flow<String?>

    /**
     * Observes a single song by its primary key.
     *
     * @param id The song row id.
     * @return A [Flow] emitting the song, or `null` if not found.
     */
    @Query("SELECT * FROM songs WHERE id = :id")
    fun observeById(id: String): Flow<SongEntity?>

    /**
     * One-shot fetch of a single song by its primary key.
     *
     * @param id The song row id.
     * @return The song, or `null` if not found.
     */
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    /**
     * Looks up a song by playlist and Spotify track id.
     *
     * Primary lookup used by the re-sync diff: does this Spotify track
     * already exist in the local database?
     *
     * @param playlistId The playlist to search within.
     * @param spotifyId The Spotify track id.
     * @return The matching song, or `null` if not present.
     */
    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId AND spotify_id = :spotifyId")
    suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity?

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Inserts a single song. Fails immediately on primary-key conflict.
     *
     * @param song The song to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(song: SongEntity)

    /**
     * Inserts multiple songs in a batch. Fails immediately on any conflict.
     *
     * @param songs The songs to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(songs: List<SongEntity>)

    /**
     * Updates a single song in place (matched by primary key).
     *
     * @param song The song with updated fields.
     */
    @Update
    suspend fun update(song: SongEntity)

    /**
     * Updates multiple songs in a batch (each matched by primary key).
     *
     * @param songs The songs with updated fields.
     */
    @Update
    suspend fun updateAll(songs: List<SongEntity>)

    /**
     * Deletes a single song (matched by primary key).
     *
     * @param song The song to delete.
     */
    @Delete
    suspend fun delete(song: SongEntity)

    /**
     * Deletes all songs belonging to a playlist.
     *
     * @param playlistId The playlist whose songs should be removed.
     */
    @Query("DELETE FROM songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: String)

    /**
     * Removes tracks that vanished from the Spotify playlist (used by re-sync).
     *
     * @param playlistId The playlist to delete from.
     * @param spotifyIds The Spotify track ids to remove.
     */
    @Query("DELETE FROM songs WHERE playlist_id = :playlistId AND spotify_id IN (:spotifyIds)")
    suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>)

    /**
     * Batch-updates the status for a set of songs.
     *
     * @param ids The song row ids to update.
     * @param status The new status to apply.
     */
    @Query("UPDATE songs SET status = :status WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<String>, status: SongStatus)

    /**
     * Single-row status write used by the download manager.
     *
     * [filePath] is only written when non-null so a recovery reset never wipes
     * a downloaded path; [error] mirrors the last failure reason (nullable,
     * typically only while [SongStatus.FAILED]).
     *
     * @param songId The song row id.
     * @param status The new lifecycle status.
     * @param filePath Optional file path to set; `null` leaves the existing value.
     * @param optional error The last error message, or `null` to clear.
     */
    @Query(
        "UPDATE songs SET status = :status, error = :error, " +
            "file_path = COALESCE(:filePath, file_path) WHERE id = :songId",
    )
    suspend fun setStatus(songId: String, status: SongStatus, filePath: String?, error: String?)

    // ── Aggregate ─────────────────────────────────────────────────────

    /**
     * Counts all songs in a playlist.
     *
     * @param playlistId The playlist to count.
     * @return The total number of songs.
     */
    @Query("SELECT COUNT(*) FROM songs WHERE playlist_id = :playlistId")
    suspend fun countForPlaylist(playlistId: String): Int

    /**
     * Counts songs in a playlist filtered by status.
     *
     * @param playlistId The playlist to count within.
     * @param status The status to filter on.
     * @return The number of matching songs.
     */
    @Query("SELECT COUNT(*) FROM songs WHERE playlist_id = :playlistId AND status = :status")
    suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int

    /**
     * Updates the display position of a single song.
     *
     * @param id The song row id.
     * @param position The new 0-based position.
     */
    @Query("UPDATE songs SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    /**
     * Updates only the file_path column for a song (used by storage migration).
     *
     * @param songId The song row id.
     * @param filePath The new file path.
     */
    @Query("UPDATE songs SET file_path = :filePath WHERE id = :songId")
    suspend fun updateFilePath(songId: String, filePath: String)

    /**
     * Updates only the lyrics column for a song.
     *
     * @param songId The song row id.
     * @param lyrics The fetched lyrics text, or null to clear.
     */
    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :songId")
    suspend fun updateLyrics(songId: String, lyrics: String?)

    @Query("UPDATE songs SET lyrics_source = :source WHERE id = :songId")
    suspend fun updateLyricsSource(songId: String, source: String?)

    @Query("UPDATE songs SET lyrics_edited = :edited WHERE id = :songId")
    suspend fun updateLyricsEdited(songId: String, edited: Boolean)

    @Query("UPDATE songs SET yt_url = :ytUrl, yt_name = :ytName, yt_channel = :ytChannel WHERE id = :songId")
    suspend fun updateYoutubeMeta(songId: String, ytUrl: String?, ytName: String?, ytChannel: String?)

    @Query("UPDATE songs SET bitrate = :bitrate, file_size = :fileSize, downloaded_at = :downloadedAt WHERE id = :songId")
    suspend fun updateDownloadMeta(songId: String, bitrate: Int?, fileSize: Long?, downloadedAt: Long?)

    @Query("UPDATE songs SET lyrics = :lyrics, lyrics_edited = :edited WHERE id = :songId")
    suspend fun updateLyricsAndEdited(songId: String, lyrics: String?, edited: Boolean)

    /**
     * Searches songs by title or artist name across all playlists.
     *
     * @param query The search term to match against title and artists columns.
     * @return Matching songs ordered by title.
     */
    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artists LIKE '%' || :query || '%' ORDER BY title ASC")
    suspend fun searchByTitleOrArtist(query: String): List<SongEntity>
}
