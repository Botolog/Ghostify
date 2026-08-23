package xyz.botolog.ghostify.player.core

import timber.log.Timber

/**
 * Maps a raw [PlayerSnapshot] (read off the Media3 player) into a [PlayerUiState] for the UI.
 *
 * Pure and unit-testable: shuffle/repeat mapping, duration normalisation and buffering
 * derivation all live here.
 */
object PlayerStateMapper {

    /**
     * Converts a [PlayerSnapshot] and associated queue data into a complete [PlayerUiState].
     *
     * @param snapshot Raw player state read from the Media3 player.
     * @param queue Current playback queue of [QueueItem]s.
     * @param nothingToPlay Whether the playlist has zero playable songs.
     * @param lastError Last non-recoverable player error, or `null` if healthy.
     * @return Fully resolved [PlayerUiState] for the UI layer.
     */
    fun toUiState(
        snapshot: PlayerSnapshot,
        queue: List<QueueItem>,
        nothingToPlay: Boolean,
        lastError: PlayerError?,
    ): PlayerUiState {
        Timber.i("PlayerStateMapper.toUiState: START")
        val playbackStatus = PlaybackStatus.fromPlayerState(snapshot.playbackState)
        val isBuffering = resolveIsBuffering(playbackStatus, snapshot)
        val currentQueueIndex = resolveCurrentQueueIndex(snapshot, queue)
        val currentItem = buildCurrentItem(snapshot, queue)

        val result = PlayerUiState(
            playbackStatus = playbackStatus,
            isPlaying = snapshot.isPlaying,
            isBuffering = isBuffering,
            positionMs = snapshot.currentPositionMs.coerceAtLeast(0L),
            durationMs = DurationNormalizer.normalize(
                playerDurationMs = snapshot.currentDurationMs,
                metadataDurationMs = snapshot.itemMetadataDurationMs,
            ),
            currentItem = currentItem,
            currentQueueIndex = currentQueueIndex,
            queue = queue,
            hasQueue = snapshot.itemCount > 0,
            nothingToPlay = nothingToPlay,
            shuffleEnabled = snapshot.shuffleEnabled,
            repeatMode = RepeatMode.fromMedia3(snapshot.repeatMode),
            volume = snapshot.volume,
            lastError = lastError,
        )
        Timber.i(
            "PlayerStateMapper.toUiState: returning playbackStatus=${result.playbackStatus}, " +
                "isPlaying=${result.isPlaying}",
        )
        return result
    }

    /**
     * Determines whether the player is in a buffering state.
     *
     * "Buffering" while ready-but-loading (e.g. waiting for the next chunk) feels stalled;
     * treat loading during playback as buffering too.
     *
     * @param playbackStatus Resolved playback status.
     * @param snapshot Raw player snapshot.
     * @return `true` if the player appears to be buffering.
     */
    private fun resolveIsBuffering(playbackStatus: PlaybackStatus, snapshot: PlayerSnapshot): Boolean =
        playbackStatus == PlaybackStatus.BUFFERING ||
            (snapshot.isPlaying && snapshot.isLoading)

    /**
     * Finds the index of the currently playing item within the queue.
     *
     * @param snapshot Raw player snapshot containing the current media ID.
     * @param queue Current playback queue.
     * @return Queue index of the current item, or -1 if not found.
     */
    private fun resolveCurrentQueueIndex(snapshot: PlayerSnapshot, queue: List<QueueItem>): Int =
        snapshot.currentMediaId
            ?.let { mediaId -> queue.indexOfFirst { it.songId == mediaId } }
            ?: -1

    /**
     * Builds the [CurrentItem] for display from the snapshot and queue fallback.
     *
     * Metadata reported by the player comes from the actual current MediaItem, which is
     * correct even when shuffle reorders playback. The queue lookup is only a fallback for
     * the (unusual) case where metadata is empty.
     *
     * @param snapshot Raw player snapshot.
     * @param queue Current playback queue for fallback metadata.
     * @return [CurrentItem] with resolved metadata, or `null` if no items are loaded.
     */
    private fun buildCurrentItem(snapshot: PlayerSnapshot, queue: List<QueueItem>): CurrentItem? {
        if (snapshot.itemCount <= 0) return null

        val fallback = queue.getOrNull(snapshot.currentItemIndex)
        return CurrentItem(
            mediaId = snapshot.currentMediaId ?: fallback?.mediaId,
            title = snapshot.currentTitle ?: fallback?.title,
            artist = snapshot.currentArtist ?: fallback?.artist,
            album = snapshot.currentAlbum ?: fallback?.album,
            artworkBytes = snapshot.artworkBytes,
            coverUrl = fallback?.coverUrl,
        )
    }
}

/**
 * Normalises a raw duration to a non-negative display value.
 *
 * Prefers the player's authoritative duration once known; falls back to the duration
 * embedded in the queue item metadata; 0 when nothing is known yet.
 */
object DurationNormalizer {

    private const val FALLBACK_DURATION_MS = 0L

    /**
     * Resolves the best available duration for display.
     *
     * @param playerDurationMs Duration reported by the player (may be [PlaybackConstants.TIME_UNSET]).
     * @param metadataDurationMs Duration from the queue item metadata, or `null` if unknown.
     * @return A non-negative duration in milliseconds suitable for UI display.
     */
    fun normalize(playerDurationMs: Long, metadataDurationMs: Long?): Long {
        Timber.i(
            "DurationNormalizer.normalize: START playerDurationMs=$playerDurationMs, " +
                "metadataDurationMs=$metadataDurationMs",
        )
        if (playerDurationMs > 0L) {
            Timber.i("DurationNormalizer.normalize: returning $playerDurationMs")
            return playerDurationMs
        }
        val result = if (metadataDurationMs != null && metadataDurationMs > 0L) {
            metadataDurationMs
        } else {
            FALLBACK_DURATION_MS
        }
        Timber.i("DurationNormalizer.normalize: returning $result")
        return result
    }
}
