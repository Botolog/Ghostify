package com.ghostify.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ghostify.data.model.PlaylistStatus

/**
 * A saved Spotify playlist.
 *
 * `spotify_id` is unique: saving the same Spotify playlist twice (e.g. re-running
 * the "Add playlist" flow) must collapse onto a single row instead of duplicating it.
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["spotify_id"], unique = true)
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
    val sortOrder: Int = 0
)
