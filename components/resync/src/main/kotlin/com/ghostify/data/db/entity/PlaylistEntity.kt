package com.ghostify.data.db.entity

import com.ghostify.data.model.PlaylistOrigin
import com.ghostify.data.model.PlaylistStatus

/**
 * A saved playlist — either a Spotify playlist or a YouTube playlist.
 *
 * Mirrors the Room `playlists` table (PROJECT.md §4). `spotify_id` is unique
 * per origin: saving the same Spotify playlist twice must collapse onto one row.
 *
 * Annotation-free JVM mirror of the `database` component's entity; see
 * [SongEntity] for the rationale.
 */
data class PlaylistEntity(
    val id: String,
    val spotifyId: String,
    val name: String,
    val owner: String? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val status: PlaylistStatus = PlaylistStatus.NEW,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long? = null,
    val sortOrder: Int = 0,
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
)
