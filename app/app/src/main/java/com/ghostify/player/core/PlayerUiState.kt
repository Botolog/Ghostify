package com.ghostify.player.core

/** The track shown in the now-playing region of the UI. */
data class CurrentItem(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkBytes: ByteArray?,
    val coverUrl: String? = null,
)

/** Last non-recoverable player error surfaced to the UI (null while healthy). */
data class PlayerError(
    val errorCode: Int,
    val message: String?,
)

/**
 * The full state the UI observes, exposed by the player controller as a [kotlinx.coroutines.flow.StateFlow].
 * Everything here is derived from a [PlayerSnapshot] plus the built queue — pure data, no Android types.
 */
data class PlayerUiState(
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentItem: CurrentItem? = null,
    /** Playlist-order index of the currently playing item; -1 when nothing is playing. */
    val currentQueueIndex: Int = -1,
    val queue: List<QueueItem> = emptyList(),
    val hasQueue: Boolean = false,
    val nothingToPlay: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val volume: Float = 1f,
    val lastError: PlayerError? = null,
)
