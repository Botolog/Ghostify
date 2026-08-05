package com.ghostify.ui.viewmodel

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
import timber.log.Timber

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

    /** Cache the artwork for the current track to avoid re-extraction on every tick. */
    private var cachedMediaId: String? = null
    private var cachedCover: Any? = null

    init {
        Timber.i("PlayerViewModel: init")
        launch {
            combine(player.state, queueOpen) { core, open ->
                mapToUi(core, open)
            }
                .catch { e -> Timber.e(e, "PlayerViewModel: stream collection FAILED") }
                .collect { _state.value = it }
        }
    }

    override fun togglePlay() {
        Timber.i("PlayerViewModel.togglePlay: START")
        player.togglePlayPause()
    }

    override fun next() {
        Timber.i("PlayerViewModel.next: START")
        player.next()
    }

    override fun previous() {
        Timber.i("PlayerViewModel.previous: START")
        player.previous()
    }

    override fun seekTo(positionMs: Long) {
        Timber.i("PlayerViewModel.seekTo: START")
        player.seekTo(positionMs)
    }

    override fun toggleShuffle() {
        Timber.i("PlayerViewModel.toggleShuffle: START")
        player.toggleShuffle()
    }

    override fun cycleRepeat() {
        Timber.i("PlayerViewModel.cycleRepeat: START")
        player.toggleRepeatMode()
    }

    override fun setVolume(fraction: Float) {
        Timber.i("PlayerViewModel.setVolume: START")
        player.setVolume(fraction)
    }

    override fun jumpToQueueIndex(index: Int) {
        Timber.i("PlayerViewModel.jumpToQueueIndex: START")
        player.skipToMediaItem(index)
    }

    override fun toggleQueue() {
        Timber.i("PlayerViewModel.toggleQueue: START")
        queueOpen.update { !it }
    }

    override fun closeQueue() {
        Timber.i("PlayerViewModel.closeQueue: START")
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
        coverUrl = coverFor(current),
    )

    /**
     * Returns cover art for the current track. Passes the raw [ByteArray] to Coil
     * directly (more efficient than base64-encoding), falling back to the HTTP URL
     * from the database when embedded artwork is unavailable.
     */
    private fun coverFor(current: CurrentItem): Any? {
        val mediaId = current.mediaId ?: return current.coverUrl
        val bytes = current.artworkBytes
        if (bytes == null) return current.coverUrl
        if (cachedMediaId == mediaId) return cachedCover
        cachedMediaId = mediaId
        cachedCover = bytes
        return cachedCover
    }
}
