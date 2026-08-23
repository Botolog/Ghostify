package xyz.botolog.ghostify.player.core

/**
 * Immutable snapshot of the raw values read off the `Player`, decoupled from Media3 so the
 * mapping into [PlayerUiState] is pure and unit-testable. The Android glue fills this from
 * `ExoPlayer` getters and hands it to [PlayerStateMapper].
 *
 * @property playbackState Raw player state value matching `Player.STATE_*` constants.
 * @property isPlaying Whether the player is actively producing audio.
 * @property playWhenReady Whether the player will automatically resume when ready.
 * @property isLoading Whether the player is currently loading media data.
 * @property currentItemIndex Index of the current item in the player's internal playlist.
 * @property itemCount Total number of items loaded in the player.
 * @property currentPositionMs Current playback position in milliseconds.
 * @property currentDurationMs Player-reported duration; [PlaybackConstants.TIME_UNSET] while unknown.
 * @property itemMetadataDurationMs Duration carried on the queue item metadata (may be null/unknown).
 * @property repeatMode Raw repeat mode value matching `Player.REPEAT_MODE_*` constants.
 * @property shuffleEnabled Whether shuffle mode is active.
 * @property volume Current playback volume (0.0 to 1.0).
 * @property currentMediaId Media ID of the currently loaded item, or `null` if none.
 * @property currentTitle Title from the current item's metadata, or `null` if unavailable.
 * @property currentArtist Artist from the current item's metadata, or `null` if unavailable.
 * @property currentAlbum Album from the current item's metadata, or `null` if unavailable.
 * @property artworkBytes Raw artwork image data from the current item, or `null` if unavailable.
 */
data class PlayerSnapshot(
    val playbackState: Int = PlaybackStatus.IDLE.playerValue,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isLoading: Boolean = false,
    val currentItemIndex: Int = 0,
    val itemCount: Int = 0,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = PlaybackConstants.TIME_UNSET,
    val itemMetadataDurationMs: Long? = null,
    val repeatMode: Int = RepeatMode.OFF.media3Value,
    val shuffleEnabled: Boolean = false,
    val volume: Float = 1f,
    val currentMediaId: String? = null,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentAlbum: String? = null,
    val artworkBytes: ByteArray? = null,
)
