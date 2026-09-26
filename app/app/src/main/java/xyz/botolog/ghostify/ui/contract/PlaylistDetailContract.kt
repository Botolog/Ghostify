package xyz.botolog.ghostify.ui.contract

import androidx.compose.runtime.Immutable
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Playlist detail screen.
 *
 * Exposes playlist metadata, the ordered track list merged with live download
 * progress, and top-level actions (play / download / re-sync / retry).
 */
interface PlaylistDetailContract {

    /** Observable UI state for the Playlist detail screen. */
    val state: StateFlow<PlaylistDetailUiState>

    /** One-shot event emitted when the playlist is deleted and the UI should navigate back. */
    val deleted: SharedFlow<Unit>

    /**
     * The committed playlist sort (field + direction) that is currently persisted.
     *
     * Starts as [PlaylistSortSpec]'s default (playlist order, ascending), which
     * matches the order already stored in the database. Selecting an option in
     * the sort sheet only drafts a new value — it is published here (and written
     * to the database) by [commitSort].
     */
    val sortMode: StateFlow<PlaylistSortSpec>

    /**
     * Commits a drafted sort: persists the resulting order into the database by
     * rewriting the playlist songs' `position` values.
     *
     * The playback queue is deliberately left untouched — the active queue keeps
     * the order it was built with until the playlist is started again. The sort
     * is also reapplied to the complete track set whenever a sync finishes, so
     * newly added tracks land in the selected order too.
     *
     * @param specification the sort selected in the sort sheet.
     */
    fun commitSort(specification: PlaylistSortSpec)

    /** Download every track that is missing (PENDING / FAILED). */
    fun downloadAll()

    /** Re-sync against Spotify (diff: add new, remove deleted, keep downloaded). */
    fun sync()

    /** Queue all DOWNLOADED tracks for playback. */
    fun playAll()

    /**
     * Play the playlist starting from a specific song.
     *
     * @param songId the id of the song to start playback from.
     */
    fun playFromSong(songId: String)

    /**
     * Download a single track.
     *
     * @param trackId the id of the track to enqueue for download.
     */
    fun downloadSong(trackId: String)

    /**
     * Reorder a song within the playlist.
     *
     * @param songId the id of the song to move.
     * @param newPosition the target position index.
     */
    fun reorderSong(songId: String, newPosition: Int)

    /**
     * Delete a single song from the playlist.
     *
     * @param trackId the id of the song to remove.
     */
    fun deleteSong(trackId: String)

    /**
     * Re-download a single track (from FAILED tap or long-press).
     *
     * @param trackId the id of the track to retry.
     */
    fun retryTrack(trackId: String)

    /**
     * Add a track to the playback queue (after all user-queued songs).
     *
     * @param trackId the id of the track to add.
     */
    fun addToQueue(trackId: String)

    /** Deletes the entire playlist and navigates back. */
    fun deletePlaylist()

    /**
     * Renames the playlist.
     *
     * @param newName the new name to apply.
     */
    fun renamePlaylist(newName: String)

    /**
     * Immutable UI state for the Playlist detail screen.
     *
     * @property playlistId the database id of the playlist.
     * @property name display name of the playlist.
     * @property coverUrl URL of the playlist cover image.
     * @property tracks ordered list of track rows to render.
     * @property downloadedCount number of tracks with a local file.
     * @property trackCount total number of tracks in the playlist.
     * @property loading `true` while the initial data is being loaded.
     * @property isDownloadingAll `true` when a download-all run is in progress.
     * @property downloadAllProgress overall download progress `0..100`, or `null` when idle.
     * @property isSyncing `true` while a re-sync operation is in progress.
     * @property error surface-level load error; the empty state shows a retry button.
     */
    @Immutable
    data class PlaylistDetailUiState(
        val playlistId: String = "",
        val name: String = "",
        val coverUrl: String? = null,
        val coverArtLocalPath: String? = null,
        val tracks: List<TrackUi> = emptyList(),
        val downloadedCount: Int = 0,
        val trackCount: Int = 0,
        val loading: Boolean = true,
        val isDownloadingAll: Boolean = false,
        val downloadAllProgress: Int? = null,
        val isSyncing: Boolean = false,
        val error: String? = null,
        val origin: String = "",
        val createdAt: Long = 0L,
        val lastSyncedAt: Long? = null,
        val owner: String = "",
        val spotifyId: String = "",
        val totalDurationMs: Long = 0L,
        val totalFileSizeBytes: Long = 0L,
    )
}
