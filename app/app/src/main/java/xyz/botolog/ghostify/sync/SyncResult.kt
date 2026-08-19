package xyz.botolog.ghostify.sync

/** Outcome of one [SyncUseCase.syncPlaylist] run. */
data class SyncResult(
    /** Rows inserted (new tracks added on Spotify). */
    val added: Int,
    /** Rows removed because they vanished from the Spotify playlist. */
    val removed: Int,
    /** Existing rows reset to PENDING because their local file was missing. */
    val requeued: Int,
    /** Existing rows whose metadata/position changed (file kept, not re-downloaded). */
    val metadataUpdated: Int,
    /** Local files deleted because their track was removed from the playlist. */
    val filesDeleted: Int,
) {
    val hasChanges: Boolean
        get() = added > 0 || removed > 0 || requeued > 0 || metadataUpdated > 0
}

/** Typed failures thrown by [SyncUseCase.syncPlaylist]. */
sealed class SyncException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {

    /** The playlist id does not exist in the local DB. */
    class PlaylistNotFound(val playlistId: String) :
        SyncException("Playlist not found: $playlistId")

    /** Spotify fetch failed (offline / 404 / timeout). Nothing was written. */
    class Network(val spotifyId: String, cause: Throwable) :
        SyncException("Network failure fetching playlist $spotifyId", cause)
}
