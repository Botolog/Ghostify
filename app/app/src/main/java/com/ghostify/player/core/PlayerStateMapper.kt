package com.ghostify.player.core

import timber.log.Timber

/**
 * Maps a raw [PlayerSnapshot] (read off the Media3 player) into a [PlayerUiState] for the UI.
 * Pure and unit-testable: shuffle/repeat mapping, duration normalisation and buffering
 * derivation all live here.
 */
object PlayerStateMapper {

    fun toUiState(
        snapshot: PlayerSnapshot,
        queue: List<QueueItem>,
        nothingToPlay: Boolean,
        lastError: PlayerError?,
    ): PlayerUiState {
        Timber.i("PlayerStateMapper.toUiState: START")
        val playbackStatus = PlaybackStatus.fromPlayerState(snapshot.playbackState)

        // "Buffering" while ready-but-loading (e.g. waiting for the next chunk) feels stalled;
        // treat loading during playback as buffering too.
        val isBuffering = playbackStatus == PlaybackStatus.BUFFERING ||
            (snapshot.isPlaying && snapshot.isLoading)

        val currentQueueIndex = snapshot.currentMediaId
            ?.let { id -> queue.indexOfFirst { it.songId == id } }
            ?: -1

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
        Timber.i("PlayerStateMapper.toUiState: returning playbackStatus=${result.playbackStatus}, isPlaying=${result.isPlaying}")
        return result
    }

    private fun buildCurrentItem(snapshot: PlayerSnapshot, queue: List<QueueItem>): CurrentItem? {
        if (snapshot.itemCount <= 0) return null

        // Metadata reported by the player comes from the actual current MediaItem, which is
        // correct even when shuffle reorders playback. The queue lookup is only a fallback for
        // the (unusual) case where metadata is empty.
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

/** Normalises a raw duration to a non-negative display value. */
object DurationNormalizer {

    /**
     * Prefers the player's authoritative duration once known; falls back to the duration
     * embedded in the queue item metadata; 0 when nothing is known yet.
     */
    fun normalize(playerDurationMs: Long, metadataDurationMs: Long?): Long {
        Timber.i("DurationNormalizer.normalize: START playerDurationMs=$playerDurationMs, metadataDurationMs=$metadataDurationMs")
        if (playerDurationMs > 0L) {
            Timber.i("DurationNormalizer.normalize: returning $playerDurationMs")
            return playerDurationMs
        }
        val meta = metadataDurationMs
        val result = if (meta != null && meta > 0L) meta else 0L
        Timber.i("DurationNormalizer.normalize: returning $result")
        return result
    }
}
