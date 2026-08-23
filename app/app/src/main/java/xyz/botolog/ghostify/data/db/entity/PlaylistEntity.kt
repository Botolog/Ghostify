package xyz.botolog.ghostify.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.data.model.PlaylistStatus

/**
 * A saved playlist — either a Spotify playlist or a YouTube playlist.
 *
 * `spotify_id` is unique per origin: saving the same Spotify playlist twice
 * (e.g. re-running the "Add playlist" flow) must collapse onto a single row
 * instead of duplicating it. For YouTube-origin playlists, `spotify_id`
 * stores the watch URL / playlist URL and the [origin] column distinguishes it.
 *
 * @property id Unique row identifier (UUID).
 * @property spotifyId Spotify playlist id, or a YouTube playlist URL for YouTube origin.
 * @property name Display name of the playlist.
 * @property owner Playlist owner name (e.g. Spotify username).
 * @property coverUrl URL of the playlist cover artwork.
 * @property trackCount Number of tracks in the playlist (derived, never trusted from callers).
 * @property status Current lifecycle state of this playlist.
 * @property createdAt Epoch millis when this row was first inserted.
 * @property lastSyncedAt Epoch millis of the most recent successful sync.
 * @property sortOrder User-defined sort position (lower = higher in list). Falls back to created_at.
 * @property origin Where this playlist was fetched from (Spotify / YouTube).
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["spotify_id", "origin"], unique = true),
    ],
)
data class PlaylistEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "spotify_id")
    val spotifyId: String,

    val name: String,
    val owner: String? = null,

    @ColumnInfo(name = "cover_url")
    val coverUrl: String? = null,

    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    val status: PlaylistStatus = PlaylistStatus.NEW,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "origin", defaultValue = "SPOTIFY")
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
)
