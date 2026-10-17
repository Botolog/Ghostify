package xyz.botolog.ghostify.recovery

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
    /** Returns a snapshot of all songs with their current status. */
    fun songsSnapshot(): List<SongState>

    /** Returns a snapshot of all playlists with their status and download count. */
    fun playlistsSnapshot(): List<PlaylistState>

    /** Reset the given song ids back to PENDING. */
    fun resetSongsToPending(ids: List<String>)

    /** Set a playlist to a consistent, non-DOWNLOADING status. */
    fun resetPlaylistStatus(id: String, toStatus: PlaylistStatus)

    /**
     * Apply a whole [RecoveryPlan] atomically.
     *
     * Song ids are applied in [RESET_CHUNK_SIZE]-sized chunks (like the
     * download/sync `IN (...)` writes) so a killed "download all" that left
     * ~1370 rows in-flight can never exceed SQLite's
     * `SQLITE_MAX_VARIABLE_NUMBER`. Implementations wrap this in a single
     * transaction, so the repair is still atomic.
     *
     * @param plan the plan containing song and playlist resets to apply.
     */
    fun applyPlan(plan: RecoveryPlan) {
        plan.songResets.map { it.id }
            .chunked(RESET_CHUNK_SIZE)
            .forEach { resetSongsToPending(it) }
        plan.playlistResets.forEach { resetPlaylistStatus(it.id, it.toStatus) }
    }

    companion object {
        /**
         * Maximum number of song ids written in a single `IN (...)` clause.
         *
         * SQLite's classic `SQLITE_MAX_VARIABLE_NUMBER` is 999; 900 leaves a safe
         * margin. Mirrors `SongDao.UPDATE_STATUS_CHUNK_SIZE` without depending on
         * the app's DAO layer.
         */
        const val RESET_CHUNK_SIZE = 900
    }
}
