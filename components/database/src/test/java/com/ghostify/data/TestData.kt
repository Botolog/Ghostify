package com.ghostify.data

import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus

/** Shared fixtures for the database tests. */
object TestData {

    fun playlist(
        id: String,
        spotifyId: String = "sp-$id",
        name: String = "Playlist $id"
    ) = PlaylistEntity(id = id, spotifyId = spotifyId, name = name)

    fun song(
        playlistId: String,
        position: Int,
        spotifyId: String = "track-$position",
        status: SongStatus = SongStatus.PENDING,
        id: String = "$playlistId-$position"
    ) = SongEntity(
        id = id,
        playlistId = playlistId,
        spotifyId = spotifyId,
        title = "Song $position",
        artists = "Artist $position",
        position = position,
        status = status
    )

    fun songs(playlistId: String, count: Int, status: SongStatus = SongStatus.PENDING): List<SongEntity> =
        (0 until count).map { song(playlistId, it, status = status) }
}
