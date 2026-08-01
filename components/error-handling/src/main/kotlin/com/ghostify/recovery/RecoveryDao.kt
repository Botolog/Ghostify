package com.ghostify.recovery

/**
 * Minimal persistence contract the recovery needs.
 *
 * The app implements this against Room (see `RoomRecoveryDao` in the androidMain source
 * set). Keeping it an interface lets the pure logic be JVM-unit-tested with an in-memory
 * fake and lets the database component own the real queries.
 *
 * Implementations MUST apply a whole plan atomically (single transaction) so a partial
 * failure can never leave the DB half-repaired.
 */
interface RecoveryDao {
    fun songsSnapshot(): List<SongState>
    fun playlistsSnapshot(): List<PlaylistState>

    /** Reset the given song ids back to PENDING. */
    fun resetSongsToPending(ids: List<String>)

    /** Set a playlist to a consistent, non-DOWNLOADING status. */
    fun resetPlaylistStatus(id: String, toStatus: PlaylistStatus)

    /** Apply a whole [RecoveryPlan] atomically. */
    fun applyPlan(plan: RecoveryPlan) {
        resetSongsToPending(plan.songResets.map { it.id })
        plan.playlistResets.forEach { resetPlaylistStatus(it.id, it.toStatus) }
    }
}
