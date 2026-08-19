package xyz.botolog.ghostify.download.data

import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.PlaylistStatus as CanonicalPlaylistStatus
import xyz.botolog.ghostify.data.model.SongStatus as CanonicalSongStatus
import xyz.botolog.ghostify.download.DownloadRepository
import xyz.botolog.ghostify.download.DownloadStatus
import xyz.botolog.ghostify.download.PlaylistRecord
import xyz.botolog.ghostify.download.PlaylistStatus
import xyz.botolog.ghostify.download.SongRecord
import xyz.botolog.ghostify.download.SongStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Room-backed implementation of [DownloadRepository] over the canonical `data/`
 * database. Status transitions are validated through the state machine so an
 * illegal write surfaces as an [xyz.botolog.ghostify.download.IllegalStateTransition]
 * instead of silently corrupting the database (T-044).
 */
class RoomDownloadRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
) : DownloadRepository {

    override suspend fun songsFor(playlistId: String): List<SongRecord> {
        Timber.i("RoomDownloadRepository.songsFor: START")
        val result = songDao.getSongsForPlaylist(playlistId).map { it.toRecord() }
        Timber.i("RoomDownloadRepository.songsFor: returning ${result.size} songs")
        return result
    }

    override fun observeSongs(playlistId: String): Flow<List<SongRecord>> {
        Timber.i("RoomDownloadRepository.observeSongs: START")
        val flow = songDao.observeSongsForPlaylist(playlistId).map { list -> list.map { it.toRecord() } }
        Timber.i("RoomDownloadRepository.observeSongs: returning flow")
        return flow
    }

    override suspend fun getSong(songId: String): SongRecord? {
        Timber.i("RoomDownloadRepository.getSong: START")
        val result = songDao.getById(songId)?.toRecord()
        Timber.i("RoomDownloadRepository.getSong: returning $result")
        return result
    }

    override suspend fun setStatus(
        songId: String,
        status: DownloadStatus,
        filePath: String?,
        error: String?,
    ) {
        Timber.i("RoomDownloadRepository.setStatus: START")
        val current = songDao.getById(songId)
        if (current != null) {
            SongStateMachine.requireTransition(current.status.toDownloadStatus(), status)
        }
        songDao.setStatus(songId, status.toSongStatus(), filePath, error)
        Timber.d("RoomDownloadRepository: song $songId state changed to $status")
    }

    override suspend fun setStatuses(
        songIds: Collection<String>,
        status: DownloadStatus,
    ) {
        Timber.i("RoomDownloadRepository.setStatuses: START")
        songDao.updateStatus(songIds.toList(), status.toSongStatus())
        Timber.d("RoomDownloadRepository: ${songIds.size} songs state changed to $status")
    }

    override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus? {
        Timber.i("RoomDownloadRepository.getPlaylistStatus: START")
        val result = playlistDao.getPlaylistStatus(playlistId)?.let { it.toPlaylistStatus() }
        Timber.i("RoomDownloadRepository.getPlaylistStatus: returning $result")
        return result
    }

    override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
        Timber.i("RoomDownloadRepository.setPlaylistStatus: START")
        playlistDao.setPlaylistStatus(playlistId, status.toPlaylistStatus())
        Timber.d("RoomDownloadRepository: playlist $playlistId state changed to $status")
    }

    override suspend fun allPlaylistIds(): List<String> {
        Timber.i("RoomDownloadRepository.allPlaylistIds: START")
        val result = playlistDao.allPlaylistIds()
        Timber.i("RoomDownloadRepository.allPlaylistIds: returning ${result.size} ids")
        return result
    }

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
        ytId = ytId,
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
