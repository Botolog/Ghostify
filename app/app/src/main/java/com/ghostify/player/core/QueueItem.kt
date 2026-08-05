package com.ghostify.player.core

/**
 * A single entry in the playback queue, in playlist order.
 *
 * `indexInQueue` is the position within the *built* queue (which only contains
 * playable, downloaded songs), not within the full DB track list.
 */
data class QueueItem(
    val songId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val filePath: String,
    val indexInQueue: Int,
    val coverUrl: String? = null,
) {
    val mediaId: String
        get() = songId
}
