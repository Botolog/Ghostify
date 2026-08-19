package com.ghostify.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ghostify.data.model.PlaylistOrigin
import com.ghostify.data.model.PlaylistStatus

/**
 * A saved playlist — either a Spotify playlist or a YouTube playlist.
 *
 * `spotify_id` is unique per origin: saving the same Spotify playlist twice
 * (e.g. re-running the "Add playlist" flow) must collapse onto a single row
 * instead of duplicating it. For YouTube-origin playlists, `spotify_id`
 * stores the watch URL / playlist URL and the `origin` column distinguishes
 * it.
 *
 * @param origin whether this playlist was fetched from Spotify or YouTube.
 *   Drives UI tinting and download routing.
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["spotify_id", "origin"], unique = true)
    ]
)
data class PlaylistEntity(
    @PrimaryKey
    val id: String,

    @androidx.room.ColumnInfo(name = "spotify_id")
    val spotifyId: String,

    val name: String,
    val owner: String? = null,

    @androidx.room.ColumnInfo(name = "cover_url")
    val coverUrl: String? = null,

    @androidx.room.ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    val status: PlaylistStatus = PlaylistStatus.NEW,

    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @androidx.room.ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    /** User-defined sort position (lower = higher in list). Falls back to created_at. */
    @androidx.room.ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /** Where this playlist was fetched from (Spotify / YouTube). */
    @ColumnInfo(name = "origin", defaultValue = "SPOTIFY")
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
)
