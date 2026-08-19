package xyz.botolog.ghostify.player.core

/**
 * Immutable snapshot of the raw values read off the `Player`, decoupled from Media3 so the
 * mapping into [PlayerUiState] is pure and unit-testable. The Android glue fills this from
 * `ExoPlayer` getters and hands it to [PlayerStateMapper].
 */
data class PlayerSnapshot(
    val playbackState: Int = PlaybackStatus.IDLE.playerValue,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isLoading: Boolean = false,
    val currentItemIndex: Int = 0,
    val itemCount: Int = 0,
    val currentPositionMs: Long = 0L,
    /** Player-reported duration; [PlaybackConstants.TIME_UNSET] while unknown. */
    val currentDurationMs: Long = PlaybackConstants.TIME_UNSET,
    /** Duration carried on the queue item metadata (may be null/unknown). */
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
