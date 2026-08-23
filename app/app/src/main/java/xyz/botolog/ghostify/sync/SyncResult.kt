package xyz.botolog.ghostify.sync

/**
 * Outcome of one [SyncUseCase.syncPlaylist] run.
 *
 * Contains counts of each type of change made during the sync operation.
 * The [hasChanges] property provides a quick check whether any mutations occurred.
 *
 * @property added Number of rows inserted (new tracks added on Spotify).
 * @property removed Number of rows removed because they vanished from the Spotify playlist.
 * @property requeued Number of existing rows reset to PENDING because their local file was missing.
 * @property metadataUpdated Number of existing rows whose metadata/position changed (file kept, not re-downloaded).
 * @property filesDeleted Number of local files deleted because their track was removed from the playlist.
 */
data class SyncResult(
    val added: Int,
    val removed: Int,
    val requeued: Int,
    val metadataUpdated: Int,
    val filesDeleted: Int,
) {
    /** Whether this sync run produced any database or file changes. */
    val hasChanges: Boolean
        get() = added > 0 || removed > 0 || requeued > 0 || metadataUpdated > 0
}

/**
 * Typed failures thrown by [SyncUseCase.syncPlaylist].
 *
 * Each subclass represents a distinct failure mode with enough context
 * for the caller to display a meaningful error message.
 */
sealed class SyncException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The playlist id does not exist in the local DB.
     *
     * @property playlistId The missing playlist's local id.
     */
    class PlaylistNotFound(val playlistId: String) :
        SyncException("Playlist not found: $playlistId")

    /**
     * Spotify / YouTube fetch failed (offline / 404 / timeout).
     * Nothing was written to the database.
     *
     * @property spotifyId The remote playlist id that failed to fetch.
     * @property cause The underlying network or parse exception.
     */
    class Network(val spotifyId: String, cause: Throwable) :
        SyncException("Network failure fetching playlist $spotifyId", cause)
}
