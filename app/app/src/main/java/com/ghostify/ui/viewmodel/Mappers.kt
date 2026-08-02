package com.ghostify.ui.viewmodel

import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus as CanonicalSongStatus
import com.ghostify.player.core.Song as PlayerSong
import com.ghostify.player.core.SongStatus as PlayerSongStatus
import com.ghostify.ui.model.Bitrate
import com.ghostify.ui.model.PlaylistPreview
import com.ghostify.ui.model.SongStatus as UiSongStatus
import com.ghostify.ui.model.TrackUi

// ---------------------------------------------------------------------------
// Track / playlist status mapping (canonical data -> UI model).
// ---------------------------------------------------------------------------

internal fun CanonicalSongStatus.toUi(): UiSongStatus = when (this) {
    CanonicalSongStatus.PENDING -> UiSongStatus.PENDING
    CanonicalSongStatus.QUEUED -> UiSongStatus.QUEUED
    CanonicalSongStatus.DOWNLOADING -> UiSongStatus.DOWNLOADING
    CanonicalSongStatus.DOWNLOADED -> UiSongStatus.DOWNLOADED
    CanonicalSongStatus.FAILED -> UiSongStatus.FAILED
    // CANCELED rows are recoverable back to PENDING; the UI has no cancelled
    // badge, so they read as "pending again".
    CanonicalSongStatus.CANCELED, CanonicalSongStatus.REMOVED -> UiSongStatus.PENDING
}

internal fun com.ghostify.data.model.PlaylistStatus.toUi():
    com.ghostify.ui.model.PlaylistStatus = when (this) {
    com.ghostify.data.model.PlaylistStatus.NEW -> com.ghostify.ui.model.PlaylistStatus.NEW
    com.ghostify.data.model.PlaylistStatus.READY -> com.ghostify.ui.model.PlaylistStatus.READY
    com.ghostify.data.model.PlaylistStatus.DOWNLOADING ->
        com.ghostify.ui.model.PlaylistStatus.DOWNLOADING
    com.ghostify.data.model.PlaylistStatus.ERROR -> com.ghostify.ui.model.PlaylistStatus.ERROR
}

internal fun SongEntity.toTrackUi(): TrackUi = TrackUi(
    id = id,
    spotifyId = spotifyId,
    title = title,
    artists = artists,
    album = album.orEmpty(),
    durationMs = durationMs.toLong(),
    status = status.toUi(),
    position = position,
)

/** Maps a canonical song row onto the player core's [PlayerSong] model. */
internal fun SongEntity.toPlayerSong(): PlayerSong = PlayerSong(
    id = id,
    title = title,
    artists = artists,
    album = album.orEmpty(),
    durationMs = durationMs.toLong(),
    filePath = filePath,
    status = PlayerSongStatus.fromValue(status.name),
)

/** Builds the Add-dialog preview from a fetched playlist's metadata. */
internal fun com.ghostify.python.PlaylistMetadata.toPreview(): PlaylistPreview = PlaylistPreview(
    name = name,
    owner = owner,
    coverUrl = coverUrl,
    trackCount = trackCount,
)

// ---------------------------------------------------------------------------
// Settings bitrate mapping.
// ---------------------------------------------------------------------------

/** Parses a stored "kbps" string ("128" / "192" / "320") into a [Bitrate]. */
internal fun bitrateFromKbps(kbps: String): Bitrate = when (kbps) {
    Bitrate.LOW.kbps.toString() -> Bitrate.LOW
    Bitrate.HIGH.kbps.toString() -> Bitrate.HIGH
    else -> Bitrate.MEDIUM
}

internal fun Bitrate.toKbpsString(): String = kbps.toString()

// ---------------------------------------------------------------------------
// Player repeat-mode mapping (core -> UI).
// ---------------------------------------------------------------------------

internal fun com.ghostify.player.core.RepeatMode.toUi(): com.ghostify.ui.util.RepeatMode =
    when (this) {
        com.ghostify.player.core.RepeatMode.OFF -> com.ghostify.ui.util.RepeatMode.OFF
        com.ghostify.player.core.RepeatMode.ONE -> com.ghostify.ui.util.RepeatMode.ONE
        com.ghostify.player.core.RepeatMode.ALL -> com.ghostify.ui.util.RepeatMode.ALL
    }
