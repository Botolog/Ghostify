package com.ghostify.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ghostify.data.model.SongStatus

/**
 * A track belonging to a playlist.
 *
 * Invariants enforced by the schema:
 *  - `UNIQUE(playlist_id, spotify_id)` — a Spotify track can appear in a playlist
 *    at most once, so re-syncs never duplicate rows.
 *  - `FK playlist_id -> playlists.id ON DELETE CASCADE` — deleting a playlist
 *    removes all of its tracks.
 *
 * Index strategy:
 *  - `(playlist_id, spotify_id)` UNIQUE doubles as the index for the per-playlist
 *    spotify lookup used by the sync diff.
 *  - `(playlist_id, position)` serves the "ordered track list" query.
 *  - `(playlist_id, status)` serves the "download these statuses" queries.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["playlist_id", "spotify_id"], unique = true),
        Index(value = ["playlist_id", "position"]),
        Index(value = ["playlist_id", "status"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SongEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "spotify_id")
    val spotifyId: String,

    val title: String,
    val artists: String,
    val album: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Int = 0,

    @ColumnInfo(name = "cover_url")
    val coverUrl: String? = null,

    @ColumnInfo(name = "yt_id")
    val ytId: String? = null,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    val status: SongStatus = SongStatus.PENDING,

    /** Playlist ordering (0-based). */
    val position: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
