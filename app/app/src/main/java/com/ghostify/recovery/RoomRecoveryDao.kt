package com.ghostify.recovery

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import timber.log.Timber

/**
 * Room implementation of [RecoveryDao] against the schema in PROJECT.md §4.
 *
 * Column names match the DTO constructor params so Room maps rows directly. The whole
 * [applyPlan] runs inside a single [Transaction], keeping the repair atomic.
 *
 * This file intentionally has no dependency on the app's entity classes — it reads the
 * `songs` / `playlists` tables by column name, which makes it self-contained.
 */
@Dao
interface RoomRecoveryDao : RecoveryDao {

    @Query("SELECT id, status FROM songs")
    override fun songsSnapshot(): List<SongState>

    @Query(
        """
        SELECT p.id, p.status,
               (SELECT COUNT(*) FROM songs s
                 WHERE s.playlist_id = p.id AND s.status = 'DOWNLOADED') AS downloadedCount
        FROM playlists p
        """,
    )
    override fun playlistsSnapshot(): List<PlaylistState>

    @Query("UPDATE songs SET status = 'PENDING' WHERE id IN (:ids)")
    override fun resetSongsToPending(ids: List<String>)

    @Query("UPDATE playlists SET status = :toStatus WHERE id = :id")
    override fun resetPlaylistStatus(id: String, toStatus: PlaylistStatus)

    @Transaction
    override fun applyPlan(plan: RecoveryPlan) {
        Timber.i("RoomRecoveryDao.applyPlan: START")
        super.applyPlan(plan)
        Timber.d("RoomRecoveryDao: state changed to PLAN_APPLIED")
    }
}
