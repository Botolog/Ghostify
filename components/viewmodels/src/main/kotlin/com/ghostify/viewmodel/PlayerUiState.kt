package com.ghostify.viewmodel

/** UI state of the Player screen. */
sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    /** Queue is empty (no DOWNLOADED tracks) — "nothing to play". */
    data object NothingToPlay : PlayerUiState

    data class Error(val message: String) : PlayerUiState

    data class Content(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long?,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val queueSize: Int,
    ) : PlayerUiState
}
