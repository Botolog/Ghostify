package com.ghostify.ui.model

/**
 * UI-facing state models for the Ghostify screens.
 *
 * These deliberately mirror (but do not duplicate) the Room entities in the `database`
 * component — they are the immutable, render-ready projection the ViewModels expose.
 * Keeping them here lets the UI component compile and be tested against plain Kotlin
 * fakes without any Android/Database dependency.
 */

enum class PlaylistStatus { NEW, READY, DOWNLOADING, ERROR }

enum class SongStatus { PENDING, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/** A playlist row on the Library screen. */
data class PlaylistUi(
    val id: String,
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val trackCount: Int,
    val downloadedCount: Int,
    val status: PlaylistStatus,
    /** Explicit overall download progress (0..100) emitted by the download flow, if in flight. */
    val progressPercent: Int? = null,
    /** Epoch ms; null = never synced. */
    val lastSyncedAt: Long? = null,
)

/** A single track row on the Playlist detail screen. */
data class TrackUi(
    val id: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val status: SongStatus,
    val position: Int,
)

/** Metadata preview shown in the Add-playlist dialog before saving. */
data class PlaylistPreview(
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val trackCount: Int,
)

/** A single item in the Player's queue drawer. */
data class QueueItem(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isCurrent: Boolean = false,
)

/** The now-playing track on the Player screen. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: Any?,
)

/** Cache stats shown on the Settings screen. */
data class CacheStats(
    val fileCount: Int,
    val sizeBytes: Long,
) {
    val sizeText: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1) "%.1f MB".format(mb) else "${sizeBytes / 1024} KB"
        }
}

/** Download bitrate options (kbps). */
enum class Bitrate(val kbps: Int, val label: String) {
    LOW(128, "128 kbps"),
    MEDIUM(192, "192 kbps"),
    HIGH(320, "320 kbps"),
}
