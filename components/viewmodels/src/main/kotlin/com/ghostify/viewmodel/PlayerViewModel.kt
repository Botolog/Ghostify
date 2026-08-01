package com.ghostify.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Backs the Player screen.
 *
 * It is a pure presentation layer over [PlayerController]: it maps the raw
 * [PlayerPlaybackState] stream onto [PlayerUiState] (playing / position /
 * shuffle / repeat — T-111) and forwards user commands. It never owns playback
 * state itself, so it cannot diverge from the real player.
 *
 * ## Lifecycle / no leaks (T-111)
 * The upstream subscription is started in [viewModelScope]; when the VM is
 * cleared ([ViewModel.clear]) the whole chain — `observeState().map{}`
 * — is cancelled, so the collector is released and nothing retains the
 * ViewModel. There is exactly one collection for the lifetime of the VM, and
 * it ends with it.
 *
 * ## No crashes
 * The controller reports failures inside [PlayerPlaybackState.error] (mapped to
 * [PlayerUiState.Error]) instead of throwing, so the screen can never be
 * killed by an unexpected player fault.
 */
class PlayerViewModel(
    private val player: PlayerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            player.observeState()
                .map(::mapToUi)
                .catch { e -> emit(PlayerUiState.Error(e.message ?: "Playback error.")) }
                .collect { _uiState.value = it }
        }
    }

    private fun mapToUi(state: PlayerPlaybackState): PlayerUiState {
        state.error?.let { return PlayerUiState.Error(it) }
        if (state.nothingToPlay || state.queueSize == 0) return PlayerUiState.NothingToPlay
        return PlayerUiState.Content(
            title = state.currentTrackTitle.orEmpty(),
            artist = state.currentTrackArtist.orEmpty(),
            isPlaying = state.isPlaying,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            shuffle = state.shuffle,
            repeat = state.repeat,
            queueSize = state.queueSize,
        )
    }

    /**
     * Loads a playlist into the player. Only DOWNLOADED tracks join the queue
     * (PROJECT.md §6.3); a filtered-empty queue becomes [PlayerUiState.NothingToPlay].
     */
    fun loadPlaylist(playlistId: String, tracks: List<Track>) {
        viewModelScope.launch {
            val downloaded = tracks
                .filter { it.status == SongStatus.DOWNLOADED }
                .sortedBy { it.position }
            when (player.playPlaylist(playlistId, downloaded)) {
                is PlayerLoadResult.Failed -> _uiState.value =
                    PlayerUiState.Error("Could not start playback.")
                else -> Unit // Ok / NothingToPlay arrive via observeState()
            }
        }
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun setShuffle(enabled: Boolean) = player.setShuffle(enabled)
    fun setRepeat(mode: RepeatMode) = player.setRepeat(mode)
    fun setVolume(volume: Float) = player.setVolume(volume)
}
