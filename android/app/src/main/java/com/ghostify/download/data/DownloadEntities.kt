package com.ghostify.download.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ghostify.download.DownloadStatus
import com.ghostify.download.PlaylistStatus
import com.ghostify.download.SongRecord

/**
 * Minimal Room schema for the download component, matching the column layout
 * described in PROJECT.md §4. When the app is assembled, the canonical entities
 * live in the `data/` package and the download component consumes them through
 * [com.ghostify.download.DownloadRepository]; this self-contained schema keeps
 * the component independently buildable and verifiable.
 */

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "spotify_id") val spotifyId: String,
    val title: String,
    val artists: String,
    val position: Int,
    val status: String,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    val error: String? = null,
) {
    fun toRecord(): SongRecord = SongRecord(
        id = id,
        playlistId = playlistId,
        spotifyId = spotifyId,
        title = title,
        artists = artists,
        position = position,
        status = DownloadStatus.valueOf(status),
        filePath = filePath,
        error = error,
    )
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val status: String,
) {
    fun toPlaylistRecord(): com.ghostify.download.PlaylistRecord =
        com.ghostify.download.PlaylistRecord(id = id, status = PlaylistStatus.valueOf(status))
}
