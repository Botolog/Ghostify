package xyz.botolog.ghostify.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.botolog.ghostify.data.model.SongStatus

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
 *
 * @property id Unique row identifier (UUID).
 * @property playlistId Foreign key to the parent [PlaylistEntity].
 * @property spotifyId Spotify track identifier, unique within a playlist.
 * @property title Track title from the Spotify catalogue.
 * @property artists Comma-separated artist names.
 * @property album Album name, if available.
 * @property durationMs Track duration in milliseconds.
 * @property coverUrl URL of the album/track artwork.
 * @property ytId YouTube video id, resolved during download.
 * @property filePath Local file path after successful download, `null` otherwise.
 * @property lyrics Song lyrics fetched from a lyrics provider, `null` if unavailable.
 * @property lyricsSource Provider/source that supplied the lyrics.
 * @property lyricsEdited `true` if the user manually edited the lyrics.
 * @property ytUrl YouTube video URL, if resolved.
 * @property ytName YouTube video title, if resolved.
 * @property ytChannel YouTube channel name, if resolved.
 * @property bitrate Audio bitrate in kbps, if known.
 * @property fileSize File size in bytes, if downloaded.
 * @property downloadedAt Epoch millis when the file was downloaded, `null` otherwise.
 * @property error Last failure reason; non-null only while [status] is [SongStatus.FAILED].
 * @property status Current download lifecycle state.
 * @property position Playlist ordering (0-based).
 * @property addedAt Epoch millis when this row was first inserted.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["playlist_id", "spotify_id"], unique = true),
        Index(value = ["playlist_id", "position"]),
        Index(value = ["playlist_id", "status"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
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

    val lyrics: String? = null,

    @ColumnInfo(name = "lyrics_source")
    val lyricsSource: String? = null,

    @ColumnInfo(name = "lyrics_edited")
    val lyricsEdited: Boolean = false,

    @ColumnInfo(name = "yt_url")
    val ytUrl: String? = null,

    @ColumnInfo(name = "yt_name")
    val ytName: String? = null,

    @ColumnInfo(name = "yt_channel")
    val ytChannel: String? = null,

    val bitrate: Int? = null,

    @ColumnInfo(name = "file_size")
    val fileSize: Long? = null,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long? = null,

    val error: String? = null,

    val status: SongStatus = SongStatus.PENDING,

    val position: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
