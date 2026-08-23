package xyz.botolog.ghostify.player.core

/**
 * The track shown in the now-playing region of the UI.
 *
 * Contains only the metadata needed for display; artwork is passed as raw bytes
 * to avoid Android Bitmap dependencies in the core layer.
 *
 * @property mediaId Unique identifier for the media item, or `null` if unavailable.
 * @property title Display title of the track, or `null` if unavailable.
 * @property artist Artist name, or `null` if unavailable.
 * @property album Album name, or `null` if unavailable.
 * @property artworkBytes Raw artwork image data, or `null` if unavailable.
 * @property coverUrl URL for the album artwork, or `null` if unavailable.
 */
data class CurrentItem(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkBytes: ByteArray?,
    val coverUrl: String? = null,
)

/**
 * Last non-recoverable player error surfaced to the UI.
 *
 * `null` while the player is healthy. Once set, it persists until the next
 * successful playback operation clears it.
 *
 * @property errorCode Numeric error code matching `PlaybackException.ERROR_CODE_*`.
 * @property message Human-readable error description, or `null` if unavailable.
 */
data class PlayerError(
    val errorCode: Int,
    val message: String?,
)

/**
 * The full state the UI observes, exposed by the player controller as a [kotlinx.coroutines.flow.StateFlow].
 *
 * Everything here is derived from a [PlayerSnapshot] plus the built queue — pure data,
 * no Android types. The UI layer maps this directly to Compose state.
 *
 * @property playbackStatus Current playback lifecycle status.
 * @property isPlaying Whether the player is actively producing audio.
 * @property isBuffering Whether the player is buffering data.
 * @property positionMs Current playback position in milliseconds.
 * @property durationMs Total duration of the current track in milliseconds.
 * @property currentItem Metadata for the currently playing track, or `null` if nothing is loaded.
 * @property currentQueueIndex Playlist-order index of the currently playing item; -1 when nothing is playing.
 * @property queue Ordered list of items in the playback queue.
 * @property hasQueue Whether the player has a non-empty queue loaded.
 * @property nothingToPlay Whether the playlist has zero playable songs.
 * @property shuffleEnabled Whether shuffle mode is active.
 * @property repeatMode Current repeat mode setting.
 * @property volume Current playback volume (0.0 to 1.0).
 * @property lastError Last non-recoverable error, or `null` if the player is healthy.
 */
data class PlayerUiState(
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentItem: CurrentItem? = null,
    val currentQueueIndex: Int = -1,
    val queue: List<QueueItem> = emptyList(),
    val hasQueue: Boolean = false,
    val nothingToPlay: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val volume: Float = 1f,
    val lastError: PlayerError? = null,
)
