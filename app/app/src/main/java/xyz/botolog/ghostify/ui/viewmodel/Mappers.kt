package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus as CanonicalSongStatus
import xyz.botolog.ghostify.player.core.Song as PlayerSong
import xyz.botolog.ghostify.player.core.SongStatus as PlayerSongStatus
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.PlaylistPreview
import xyz.botolog.ghostify.ui.model.SongStatus as UiSongStatus
import xyz.botolog.ghostify.ui.model.TrackUi

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

internal fun xyz.botolog.ghostify.data.model.PlaylistStatus.toUi():
    xyz.botolog.ghostify.ui.model.PlaylistStatus = when (this) {
    xyz.botolog.ghostify.data.model.PlaylistStatus.NEW -> xyz.botolog.ghostify.ui.model.PlaylistStatus.NEW
    xyz.botolog.ghostify.data.model.PlaylistStatus.READY -> xyz.botolog.ghostify.ui.model.PlaylistStatus.READY
    xyz.botolog.ghostify.data.model.PlaylistStatus.DOWNLOADING ->
        xyz.botolog.ghostify.ui.model.PlaylistStatus.DOWNLOADING
    xyz.botolog.ghostify.data.model.PlaylistStatus.ERROR -> xyz.botolog.ghostify.ui.model.PlaylistStatus.ERROR
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
    coverUrl = coverUrl,
)

/** Builds the Add-dialog preview from a fetched playlist's metadata. */
internal fun xyz.botolog.ghostify.python.PlaylistMetadata.toPreview(): PlaylistPreview = PlaylistPreview(
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

internal fun xyz.botolog.ghostify.player.core.RepeatMode.toUi(): xyz.botolog.ghostify.ui.util.RepeatMode =
    when (this) {
        xyz.botolog.ghostify.player.core.RepeatMode.OFF -> xyz.botolog.ghostify.ui.util.RepeatMode.OFF
        xyz.botolog.ghostify.player.core.RepeatMode.ONE -> xyz.botolog.ghostify.ui.util.RepeatMode.ONE
        xyz.botolog.ghostify.player.core.RepeatMode.ALL -> xyz.botolog.ghostify.ui.util.RepeatMode.ALL
    }
