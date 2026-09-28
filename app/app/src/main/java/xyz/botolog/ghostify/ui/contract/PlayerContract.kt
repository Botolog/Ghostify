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
    fun saveLyrics(lyrics: String, edited: Boolean = false)

    /**
     * Persists edited title/artists/album for a song and refreshes the player so the
     * now-playing line and the queue editor show the new values straight away.
     *
     * The media file itself is never renamed, moved or re-downloaded: only the metadata
     * columns of the song row are written. Nothing is written at all when the caller
     * discards the edit, which is why the dialog never calls this on dismiss.
     *
     * @param songId the song database ID to update.
     * @param title the new track title.
     * @param artists the new comma-separated artist names.
     * @param album the new album name; blank is stored as "no album".
     * @param onResult called with the outcome, on the main thread.
     */
    fun updateSongMetadata(
        songId: String,
        title: String,
        artists: String,
        album: String,
        onResult: (SongMetadataUpdateResult) -> Unit = {},
    )

    /** Outcome of a [updateSongMetadata] call, delivered to the caller rather than thrown. */
    sealed interface SongMetadataUpdateResult {

        /** The song row was written and the player now shows the new metadata. */
        data object Updated : SongMetadataUpdateResult

        /**
         * Nothing was written.
         *
         * @property reason a readable, user-facing explanation to show in the dialog.
         */
        data class Failed(val reason: String) : SongMetadataUpdateResult
    }

    /**
     * Reorder the queue by moving an item from one position to another.
     *
     * @param fromIndex the current position of the item to move.
     * @param toIndex the target position.
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int)

    /**
     * Add a song to the queue, positioned after all user-queued songs.
     *
     * If the song is already in the queue, it is moved to the new position.
     * If the song is currently playing, no action is taken.
     *
     * @param songId the song database ID to add.
     */
    fun addToQueue(songId: String)

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
        val lyricsSource: String? = null,
        val lyricsEdited: Boolean = false,
        val showQueueCovers: Boolean = true,
    )
}
