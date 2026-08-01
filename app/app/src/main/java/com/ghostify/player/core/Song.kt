package com.ghostify.player.core

/**
 * Domain model for a track as the player core needs it.
 *
 * It deliberately mirrors the Room `songs` row (see PROJECT.md §4) but stays independent
 * of the database component so this package can be unit-tested without an Android runtime.
 * The database layer is responsible for mapping its Room entity into this type.
 */
enum class SongStatus(val value: String) {
    PENDING("PENDING"),
    QUEUED("QUEUED"),
    DOWNLOADING("DOWNLOADING"),
    DOWNLOADED("DOWNLOADED"),
    FAILED("FAILED"),
    REMOVED("REMOVED");

    companion object {
        /** Safe lookup from a stored string; unknown values map to [SongStatus.PENDING]. */
        fun fromValue(value: String): SongStatus =
            entries.firstOrNull { it.value == value } ?: PENDING
    }
}

data class Song(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long?,
    val filePath: String?,
    val status: SongStatus,
) {
    /**
     * A song is playable only when its row says DOWNLOADED *and* a local file path is present.
     * The actual existence of the file is verified later by [PlayerQueueBuilder].
     */
    val isDownloaded: Boolean
        get() = status == SongStatus.DOWNLOADED && !filePath.isNullOrBlank()
}
