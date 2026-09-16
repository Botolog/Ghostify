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

    /** Observable UI state for the Player screen. */
    val state: StateFlow<PlayerUiState>

    /** Toggle between play and pause. */
    fun togglePlay()

    /** Skip to the next track in the queue. */
    fun next()

    /** Skip to the previous track in the queue. */
    fun previous()

    /** Skip to the previous track unconditionally (never restarts the current track). */
    fun previousTrack()

    /**
     * Seek to an absolute position within the current track.
     *
     * @param positionMs target position in milliseconds.
     */
    fun seekTo(positionMs: Long)

    /** Toggle shuffle mode on or off. */
    fun toggleShuffle()

    /**
     * Cycles repeat mode in the order OFF → ALL → ONE → OFF.
     */
    fun cycleRepeat()

    /**
     * Set the playback volume.
     *
     * @param fraction volume level in the range `0f` (mute) to `1f` (full).
     */
    fun setVolume(fraction: Float)

    /**
     * Jump to a specific index in the playback queue.
     *
     * @param index zero-based position in the queue.
     */
    fun jumpToQueueIndex(index: Int)

    /** Toggle the queue drawer open/closed. */
    fun toggleQueue()

    /** Close the queue drawer. */
    fun closeQueue()

    /** Retry fetching lyrics for the currently playing track. */
    fun retryLyrics()

    /** Save edited lyrics for the currently playing track. */
    fun saveLyrics(lyrics: String)

    /** Re-fetch lyrics from the given provider. Calls [onResult] with the result (or null). */
    fun refetchLyrics(provider: String, onResult: (String?) -> Unit)

    /**
     * Immutable UI state for the Player screen.
     *
     * @property empty `true` when there is nothing to play.
     * @property nowPlaying metadata of the currently playing track, or `null`.
     * @property isPlaying `true` when playback is active.
     * @property positionMs current playback position in milliseconds.
     * @property durationMs total duration of the current track in milliseconds.
     * @property shuffle `true` when shuffle mode is enabled.
     * @property repeatMode current repeat mode.
     * @property volume playback volume in the range `0f..1f`.
     * @property queue the ordered list of tracks in the queue.
     * @property queueOpen `true` when the queue drawer is visible.
     */
    data class PlayerUiState(
        val empty: Boolean = true,
        val nowPlaying: NowPlaying? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val shuffle: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val volume: Float = 0.8f,
        val queue: List<QueueItem> = emptyList(),
        val queueOpen: Boolean = false,
        val lyrics: String? = null,
    )
}
