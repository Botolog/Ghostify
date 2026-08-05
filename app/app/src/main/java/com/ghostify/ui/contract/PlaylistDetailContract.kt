package com.ghostify.ui.contract

import com.ghostify.ui.model.TrackUi
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Playlist detail screen.
 */
interface PlaylistDetailContract {

    val state: StateFlow<PlaylistDetailUiState>

    /** Download every track that is missing (PENDING / FAILED). */
    fun downloadAll()

    /** Re-sync against Spotify (diff: add new, remove deleted, keep downloaded). */
    fun sync()

    /** Queue all DOWNLOADED tracks for playback. */
    fun playAll()

    /** Play the playlist starting from a specific song. */
    fun playFromSong(songId: String)

    /** Download a single track. */
    fun downloadSong(trackId: String)

    /** Reorder a song within the playlist. */
    fun reorderSong(songId: String, newPosition: Int)

    /** Delete a single song from the playlist. */
    fun deleteSong(trackId: String)

    /** Re-download a single track (from FAILED tap or long-press). */
    fun retryTrack(trackId: String)

    data class PlaylistDetailUiState(
        val playlistId: String = "",
        val name: String = "",
        val coverUrl: String? = null,
        val tracks: List<TrackUi> = emptyList(),
        val downloadedCount: Int = 0,
        val trackCount: Int = 0,
        val loading: Boolean = true,
        val isDownloadingAll: Boolean = false,
        /** Overall download progress 0..100, while a download-all is running. */
        val downloadAllProgress: Int? = null,
        val isSyncing: Boolean = false,
        /** Surface-level load error (empty state shows a retry). */
        val error: String? = null,
    )
}
