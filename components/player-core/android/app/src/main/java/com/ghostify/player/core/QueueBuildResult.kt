package com.ghostify.player.core

/**
 * Result of building a playback queue from a playlist's songs.
 *
 * - [Ready]: at least one playable song; the caller should start playback at [Ready.startIndex].
 * - [NothingToPlay]: the playlist has zero playable songs; the UI shows the "nothing to play"
 *   state and the player must not crash.
 */
sealed interface QueueBuildResult {
    data class Ready(
        val items: List<QueueItem>,
        val startIndex: Int,
    ) : QueueBuildResult {
        init {
            require(items.isNotEmpty()) { "Ready result must contain at least one item" }
            require(startIndex in items.indices) {
                "startIndex $startIndex out of bounds for ${items.size} items"
            }
        }
    }

    data object NothingToPlay : QueueBuildResult
}
