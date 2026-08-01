package com.ghostify.viewmodel

/** UI state of the Playlist detail screen. */
sealed interface PlaylistUiState {
    data object Loading : PlaylistUiState

    /** The playlist no longer exists (deleted from another screen). */
    data object NotFound : PlaylistUiState

    /** The playlist has no tracks yet (e.g. an empty Spotify playlist). */
    data object Empty : PlaylistUiState

    data class Content(
        val playlist: PlaylistSummary,
        val tracks: List<TrackUi>,
        /** Live download progress; null when nothing is running. */
        val progress: DownloadProgress? = null,
        val syncing: Boolean = false,
        val deleting: Boolean = false,
    ) : PlaylistUiState

    data class Error(val message: String) : PlaylistUiState
}

/** A track row on the detail screen, with live download status merged in. */
data class TrackUi(
    val id: String,
    val title: String,
    val artists: String,
    val durationMs: Int,
    val status: SongStatus,
    /** 0f..1f; DOWNLOADED tracks read 1f even without live progress. */
    val fraction: Float,
    val position: Int,
)
