package com.ghostify.viewmodel

/** UI state of the Library screen. */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(val playlists: List<PlaylistSummary>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}
