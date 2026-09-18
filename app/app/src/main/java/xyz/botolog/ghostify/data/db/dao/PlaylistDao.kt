package xyz.botolog.ghostify.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.model.PlaylistStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the `playlists` table.
 *
 * Provides both [Flow]-based observation for reactive UI and one-shot `suspend`
 * queries for background work (sync, reorder).
 */
@Dao
interface PlaylistDao {

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Observes all playlists ordered by [PlaylistEntity.sortOrder] then creation time.
     *
     * @return A [Flow] emitting the full playlist list on every DB change.
     */
    @Query("SELECT * FROM playlists ORDER BY sort_order ASC, created_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    /**
     * Observes a single playlist by its primary key.
     *
     * @param id The playlist row id.
     * @return A [Flow] emitting the playlist, or `null` if not found.
     */
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: String): Flow<PlaylistEntity?>

    /**
     * One-shot fetch of a single playlist by its primary key.
     *
     * @param id The playlist row id.
     * @return The playlist, or `null` if not found.
     */
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

    /**
     * Looks up a Spotify-origin playlist by its Spotify id.
     *
     * @param spotifyId The Spotify playlist id.
     * @return The matching playlist, or `null` if not found.
     */
    @Query("SELECT * FROM playlists WHERE spotify_id = :spotifyId AND origin = 'SPOTIFY'")
    suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity?

    /**
     * Looks up a YouTube-origin playlist by its playlist URL.
     *
     * @param ytPlaylistId The YouTube playlist URL / identifier.
     * @return The matching playlist, or `null` if not found.
     */
    @Query("SELECT * FROM playlists WHERE spotify_id = :ytPlaylistId AND origin = 'YOUTUBE'")
    suspend fun getByYtPlaylistId(ytPlaylistId: String): PlaylistEntity?

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Inserts a single playlist. Fails immediately on primary-key conflict.
     *
     * @param playlist The playlist to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity)

    /**
     * Inserts multiple playlists in a batch. Fails immediately on any conflict.
     *
     * @param playlists The playlists to insert.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(playlists: List<PlaylistEntity>)

    /**
     * Updates a single playlist in place (matched by primary key).
     *
     * @param playlist The playlist with updated fields.
     */
    @Update
    suspend fun update(playlist: PlaylistEntity)

    /**
     * Deletes a single playlist (matched by primary key).
     *
     * Associated songs are removed via `ON DELETE CASCADE`.
     *
     * @param playlist The playlist to delete.
     */
    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    /**
     * Deletes a playlist by its primary key.
     *
     * @param id The playlist row id.
     */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Aggregate ─────────────────────────────────────────────────────

    /**
     * Returns the total number of playlists.
     *
     * @return The playlist count.
     */
    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    /**
     * Returns the download status of a playlist.
     *
     * @param id The playlist row id.
     * @return The [PlaylistStatus], or `null` if the playlist does not exist.
     */
    @Query("SELECT status FROM playlists WHERE id = :id")
    suspend fun getPlaylistStatus(id: String): PlaylistStatus?

    /**
     * Updates the download status of a playlist.
     *
     * @param id The playlist row id.
     * @param status The new status to set.
     */
    @Query("UPDATE playlists SET status = :status WHERE id = :id")
    suspend fun setPlaylistStatus(id: String, status: PlaylistStatus)

    /**
     * Returns all playlist row ids (no payload).
     *
     * @return List of every playlist id.
     */
    @Query("SELECT id FROM playlists")
    suspend fun allPlaylistIds(): List<String>

    /**
     * Updates the user-defined sort order of a playlist.
     *
     * @param id The playlist row id.
     * @param sortOrder The new sort position (lower = higher in list).
     */
    @Query("UPDATE playlists SET sort_order = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    /**
     * Searches playlists by name.
     *
     * @param query The search term to match against the name column.
     * @return Matching playlists ordered by name.
     */
    @Query("SELECT * FROM playlists WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchByName(query: String): List<PlaylistEntity>
}
