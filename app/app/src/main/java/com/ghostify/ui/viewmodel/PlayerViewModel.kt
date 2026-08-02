package com.ghostify.ui.viewmodel

import android.util.Base64
import com.ghostify.player.PlayerController
import com.ghostify.player.core.CurrentItem
import com.ghostify.player.core.PlayerUiState as CorePlayerUiState
import com.ghostify.ui.contract.PlayerContract
import com.ghostify.ui.contract.PlayerContract.PlayerUiState
import com.ghostify.ui.model.NowPlaying
import com.ghostify.ui.model.QueueItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Backs the Player screen: a pure presentation layer over [PlayerController].
 *
 * It maps the raw player state stream onto the render-ready [PlayerUiState]
 * (now-playing / position / shuffle / repeat / queue) and forwards user
 * commands. It never owns playback state itself.
 */
class PlayerViewModel(
    private val player: PlayerController,
) : ContractViewModel(), PlayerContract {

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val queueOpen = MutableStateFlow(false)

    /** Encode a given track's artwork at most once. */
    private var encodedMediaId: String? = null
    private var encodedUri: String? = null

    init {
        launch {
            combine(player.state, queueOpen) { core, open ->
                mapToUi(core, open)
            }
                .catch { }
                .collect { _state.value = it }
        }
    }

    override fun togglePlay() = player.togglePlayPause()
    override fun next() = player.next()
    override fun previous() = player.previous()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun toggleShuffle() = player.toggleShuffle()
    override fun cycleRepeat() = player.toggleRepeatMode()
    override fun setVolume(fraction: Float) = player.setVolume(fraction)
    override fun jumpToQueueIndex(index: Int) = player.skipToMediaItem(index)
    override fun toggleQueue() {
        queueOpen.update { !it }
    }

    override fun closeQueue() {
        queueOpen.value = false
    }

    private fun mapToUi(core: CorePlayerUiState, open: Boolean): PlayerUiState {
        val empty = core.nothingToPlay || core.queue.isEmpty()
        val current = core.currentItem
        return PlayerUiState(
            empty = empty,
            nowPlaying = if (empty || current == null) null else toNowPlaying(current),
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = core.durationMs,
            shuffle = core.shuffleEnabled,
            repeatMode = core.repeatMode.toUi(),
            volume = core.volume,
            queue = core.queue.mapIndexed { index, item ->
                QueueItem(
                    title = item.title.orEmpty(),
                    artist = item.artist.orEmpty(),
                    durationMs = item.durationMs ?: 0L,
                    isCurrent = index == core.currentQueueIndex,
                )
            },
            queueOpen = open,
        )
    }

    private fun toNowPlaying(current: CurrentItem): NowPlaying = NowPlaying(
        title = current.title.orEmpty(),
        artist = current.artist.orEmpty(),
        album = current.album.orEmpty(),
        coverUrl = coverUriFor(current),
    )

    /**
     * The player core extracts cover art as raw bytes; Coil's [AsyncImage] can
     * render a `data:` URI, so encode once per track to avoid re-encoding on
     * every 250ms position tick.
     */
    private fun coverUriFor(current: CurrentItem): String? {
        val mediaId = current.mediaId ?: return null
        val bytes = current.artworkBytes ?: return null
        if (encodedMediaId == mediaId) return encodedUri
        encodedMediaId = mediaId
        encodedUri = "data:image/jpeg;base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        return encodedUri
    }
}
