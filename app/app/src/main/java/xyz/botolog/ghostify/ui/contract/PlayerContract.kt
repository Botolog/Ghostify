package xyz.botolog.ghostify.ui.contract

import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.util.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Player screen.
 *
 * The contract wraps the player core (Media3/ExoPlayer) and exposes a render-ready state.
 * `empty == true` is the "nothing playing" state (no DOWNLOADED tracks / fresh app start).
 */
interface PlayerContract {

    val state: StateFlow<PlayerUiState>

    fun togglePlay()

    fun next()

    fun previous()

    fun seekTo(positionMs: Long)

    fun toggleShuffle()

    /** Cycles repeat OFF -> ALL -> ONE -> OFF. */
    fun cycleRepeat()

    fun setVolume(fraction: Float)

    /** Jump to the queue item at [index]. */
    fun jumpToQueueIndex(index: Int)

    fun toggleQueue()

    fun closeQueue()

    data class PlayerUiState(
        val empty: Boolean = true,
        val nowPlaying: NowPlaying? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val shuffle: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        /** 0f..1f. */
        val volume: Float = 0.8f,
        val queue: List<QueueItem> = emptyList(),
        val queueOpen: Boolean = false,
    )
}
