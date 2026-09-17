package xyz.botolog.ghostify.player.core

/**
 * Lifecycle status of a song as tracked by the player core.
 *
 * Mirrors the Room `songs` row status column (see PROJECT.md §4) but stays independent
 * of the database component so this package can be unit-tested without an Android runtime.
 *
 * @property value The string representation stored in the database.
 */
enum class SongStatus(val value: String) {
    /** Song has been discovered but not yet queued for download. */
    PENDING("PENDING"),

    /** Song is queued and waiting to be downloaded. */
    QUEUED("QUEUED"),

    /** Song download is in progress. */
    DOWNLOADING("DOWNLOADING"),

    /** Song has been fully downloaded and is ready for playback. */
    DOWNLOADED("DOWNLOADED"),

    /** Song download or processing failed. */
    FAILED("FAILED"),

    /** Song was removed from the playlist. */
    REMOVED("REMOVED");

    companion object {
        /**
         * Safely converts a stored string value to a [SongStatus].
         *
         * @param value The string value from the database.
         * @return The matching [SongStatus], or [PENDING] if the value is unknown.
         */
        fun fromValue(value: String): SongStatus =
            entries.firstOrNull { it.value == value } ?: PENDING
    }
}

/**
 * Domain model for a track as the player core needs it.
 *
 * It deliberately mirrors the Room `songs` row (see PROJECT.md §4) but stays independent
 * of the database component so this package can be unit-tested without an Android runtime.
 * The database layer is responsible for mapping its Room entity into this type.
 *
 * @property id Unique identifier for the song.
 * @property title Display title of the track.
 * @property artists Comma-separated list of artist names.
 * @property album Album name.
 * @property durationMs Track duration in milliseconds, or `null` if unknown.
 * @property filePath Local filesystem path to the downloaded file, or `null` if not downloaded.
 * @property status Current lifecycle status of the song.
 * @property coverUrl URL for the album artwork, or `null` if unavailable.
 * @property lyrics LRC-synced lyrics text, or `null` if unavailable.
 */
data class Song(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long?,
    val filePath: String?,
    val status: SongStatus,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val lyricsSource: String? = null,
    val lyricsEdited: Boolean = false,
    val ytId: String? = null,
    val ytUrl: String? = null,
    val ytName: String? = null,
    val ytChannel: String? = null,
    val bitrate: Int? = null,
    val fileSize: Long? = null,
    val downloadedAt: Long? = null,
) {
    /**
     * Whether this song is ready for playback.
     *
     * A song is considered downloaded only when its status is [SongStatus.DOWNLOADED]
     * *and* a non-blank local file path is present. The actual existence of the file
     * is verified later by [PlayerQueueBuilder].
     */
    val isDownloaded: Boolean
        get() = status == SongStatus.DOWNLOADED && !filePath.isNullOrBlank()
}
