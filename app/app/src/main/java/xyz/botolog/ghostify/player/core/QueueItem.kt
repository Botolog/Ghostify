package xyz.botolog.ghostify.player.core

/**
 * A single entry in the playback queue, in playlist order.
 *
 * `indexInQueue` is the position within the *built* queue (which only contains
 * playable, downloaded songs), not within the full DB track list.
 *
 * @property songId Unique identifier of the song in the database.
 * @property title Display title, or `null` if the title was blank.
 * @property artist Artist name, or `null` if the artist was blank.
 * @property album Album name, or `null` if the album was blank.
 * @property durationMs Track duration in milliseconds, or `null` if unknown.
 * @property filePath Absolute path to the local audio file.
 * @property indexInQueue Zero-based position within the built queue.
 * @property coverUrl URL for the album artwork, or `null` if unavailable.
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
    /**
     * Media ID used by the player to identify this item.
     *
     * Alias for [songId] to align with Media3's media item identifier convention.
     */
    val mediaId: String
        get() = songId
}
