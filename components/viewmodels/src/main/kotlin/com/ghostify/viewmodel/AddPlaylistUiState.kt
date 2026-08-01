package com.ghostify.viewmodel

/** UI state of the Add-playlist dialog. */
sealed interface AddPlaylistUiState {

    /** Nothing entered / dialog freshly opened. */
    data object Idle : AddPlaylistUiState

    /** URL is being validated (synchronous, transient). */
    data object Validating : AddPlaylistUiState

    /** Metadata fetch from Spotify is in flight. */
    data object Fetching : AddPlaylistUiState

    /** Metadata fetched — show preview, enable Save. */
    data class Preview(val metadata: FetchedPlaylist, val spotifyId: String) : AddPlaylistUiState

    /** Save is being written to the DB. */
    data object Saving : AddPlaylistUiState

    /** Something failed; the dialog stays open so the user can fix it. */
    data class Error(val message: String, val retryHint: String? = null) : AddPlaylistUiState

    /** Saved — the dialog may close and the Library picks the playlist up. */
    data object Success : AddPlaylistUiState
}

/** True while the dialog should remain visible (anything but a completed save). */
val AddPlaylistUiState.dialogOpen: Boolean
    get() = this != AddPlaylistUiState.Success
