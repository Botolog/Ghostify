package com.ghostify.download.data

import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.PlaylistStatus as CanonicalPlaylistStatus
import com.ghostify.data.model.SongStatus as CanonicalSongStatus
import com.ghostify.download.DownloadRepository
import com.ghostify.download.DownloadStatus
import com.ghostify.download.PlaylistRecord
import com.ghostify.download.PlaylistStatus
import com.ghostify.download.SongRecord
import com.ghostify.download.SongStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [DownloadRepository] over the canonical `data/`
 * database. Status transitions are validated through the state machine so an
 * illegal write surfaces as an [com.ghostify.download.IllegalStateTransition]
 * instead of silently corrupting the database (T-044).
 */
class RoomDownloadRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
) : DownloadRepository {

    override suspend fun songsFor(playlistId: String): List<SongRecord> =
        songDao.getSongsForPlaylist(playlistId).map { it.toRecord() }

    override fun observeSongs(playlistId: String): Flow<List<SongRecord>> =
        songDao.observeSongsForPlaylist(playlistId).map { list -> list.map { it.toRecord() } }

    override suspend fun getSong(songId: String): SongRecord? =
        songDao.getById(songId)?.toRecord()

    override suspend fun setStatus(
        songId: String,
        status: DownloadStatus,
        filePath: String?,
        error: String?,
    ) {
        val current = songDao.getById(songId)
        if (current != null) {
            SongStateMachine.requireTransition(current.status.toDownloadStatus(), status)
        }
        songDao.setStatus(songId, status.toSongStatus(), filePath, error)
    }

    override suspend fun setStatuses(
        songIds: Collection<String>,
        status: DownloadStatus,
    ) {
        songDao.updateStatus(songIds.toList(), status.toSongStatus())
    }

    override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus? =
        playlistDao.getPlaylistStatus(playlistId)?.let { it.toPlaylistStatus() }

    override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
        playlistDao.setPlaylistStatus(playlistId, status.toPlaylistStatus())
    }

    override suspend fun allPlaylistIds(): List<String> = playlistDao.allPlaylistIds()

    private fun SongEntity.toRecord(): SongRecord = SongRecord(
        id = id,
        playlistId = playlistId,
        spotifyId = spotifyId,
        title = title,
        artists = artists,
        position = position,
        status = status.toDownloadStatus(),
        filePath = filePath,
        error = error,
    )
}

/** Canonical `songs.status` -> download-manager `DownloadStatus`. */
private fun CanonicalSongStatus.toDownloadStatus(): DownloadStatus = when (this) {
    CanonicalSongStatus.PENDING -> DownloadStatus.PENDING
    CanonicalSongStatus.QUEUED -> DownloadStatus.QUEUED
    CanonicalSongStatus.DOWNLOADING -> DownloadStatus.DOWNLOADING
    CanonicalSongStatus.DOWNLOADED -> DownloadStatus.DOWNLOADED
    CanonicalSongStatus.FAILED -> DownloadStatus.FAILED
    CanonicalSongStatus.CANCELED -> DownloadStatus.CANCELED
    // REMOVED rows are deleted by the sync layer before the download manager ever
    // sees them; if one slips through it behaves like a cancelled, terminal track.
    CanonicalSongStatus.REMOVED -> DownloadStatus.CANCELED
}

private fun DownloadStatus.toSongStatus(): CanonicalSongStatus = when (this) {
    DownloadStatus.PENDING -> CanonicalSongStatus.PENDING
    DownloadStatus.QUEUED -> CanonicalSongStatus.QUEUED
    DownloadStatus.DOWNLOADING -> CanonicalSongStatus.DOWNLOADING
    DownloadStatus.DOWNLOADED -> CanonicalSongStatus.DOWNLOADED
    DownloadStatus.FAILED -> CanonicalSongStatus.FAILED
    DownloadStatus.CANCELED -> CanonicalSongStatus.CANCELED
}

private fun CanonicalPlaylistStatus.toPlaylistStatus(): PlaylistStatus =
    PlaylistStatus.valueOf(name)

private fun PlaylistStatus.toPlaylistStatus(): CanonicalPlaylistStatus =
    CanonicalPlaylistStatus.valueOf(name)
