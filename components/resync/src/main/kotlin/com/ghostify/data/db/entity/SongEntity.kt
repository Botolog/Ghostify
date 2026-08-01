package com.ghostify.data.db.entity

import com.ghostify.data.model.SongStatus

/**
 * A track belonging to a playlist.
 *
 * Mirrors the Room `songs` table (PROJECT.md §4). The schema invariants that
 * matter to the sync diff:
 *  - `UNIQUE(playlist_id, spotify_id)` — a Spotify track appears in a playlist
 *    at most once, so re-syncs never duplicate rows (T-064).
 *  - `FK playlist_id -> playlists.id ON DELETE CASCADE`.
 *  - `file_path != null && status == DOWNLOADED` implies the file exists on
 *    disk; re-sync re-verifies that (T-059, T-155).
 *
 * This is the annotation-free, JVM-compilable face of the Room entity defined
 * in the `database` component (same name, fields, column names). The Room
 * annotations only add compile-time/KSP metadata and never change the Kotlin
 * signatures, so code written against this type compiles unchanged against the
 * real Room entity.
 */
data class SongEntity(
    val id: String,
    val playlistId: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val durationMs: Int = 0,
    val coverUrl: String? = null,
    val ytId: String? = null,
    val filePath: String? = null,
    val status: SongStatus = SongStatus.PENDING,
    /** Playlist ordering (0-based). */
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
