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

/** Where a playlist was fetched from — drives UI tinting. */
enum class PlaylistOrigin { SPOTIFY, YOUTUBE }

/** A playlist row on the Library screen. */
data class PlaylistUi(
    val id: String,
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val trackCount: Int,
    val downloadedCount: Int,
    val status: PlaylistStatus,
    /** Origin of the playlist (Spotify / YouTube) — drives row tinting. */
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
    /** Explicit overall download progress (0..100) emitted by the download flow, if in flight. */
    val progressPercent: Int? = null,
    /** Epoch ms; null = never synced. */
    val lastSyncedAt: Long? = null,
)
