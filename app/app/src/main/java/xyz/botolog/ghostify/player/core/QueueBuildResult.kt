package xyz.botolog.ghostify.player.core

/**
 * Result of building a playback queue from a playlist's songs.
 *
 * This sealed interface represents the two possible outcomes:
 * - [Ready]: at least one playable song was found; the caller should start playback at [Ready.startIndex].
 * - [NothingToPlay]: the playlist has zero playable songs; the UI shows the "nothing to play"
 *   state and the player must not crash.
 */
sealed interface QueueBuildResult {

    /**
     * A successfully built queue with at least one playable item.
     *
     * @property items Ordered list of playable [QueueItem]s in queue order.
     * @property startIndex Index within [items] to begin playback from.
     */
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

    /** The playlist has no playable songs. */
    data object NothingToPlay : QueueBuildResult
}
