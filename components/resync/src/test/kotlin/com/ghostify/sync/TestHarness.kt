package com.ghostify.sync

import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.PlaylistStatus
import com.ghostify.data.model.SongStatus
import java.io.File

/**
 * Wires all fakes + the real [SyncUseCase] into one cohesive unit, with
 * deterministic ids and timestamps.
 */
class TestHarness(tempDir: File) {
    val songDao = FakeSongDao()
    val playlistDao = FakePlaylistDao()
    val transactions = RollbackTransactionRunner(songDao, playlistDao)
    val files = JvmLocalFileStore(tempDir)
    val fetcher = FakeFetcher()
    val enqueuer = FakeEnqueuer()
    val locks = SyncLocks()

    private var idCounter = 0

    val useCase = SyncUseCase(
        playlists = playlistDao,
        songs = songDao,
        transactions = transactions,
        fetcher = fetcher,
        files = files,
        enqueuer = enqueuer,
        locks = locks,
        now = { 1_000L },
        newId = { "song-${idCounter++}" },
    )

    suspend fun seedPlaylist(
        id: String = "pl1",
        spotifyId: String = "spot-pl",
        name: String = "Playlist",
        coverUrl: String? = "http://cover.example/art",
    ): PlaylistEntity = PlaylistEntity(
        id = id,
        spotifyId = spotifyId,
        name = name,
        owner = "Owner",
        coverUrl = coverUrl,
        trackCount = 0,
        status = PlaylistStatus.NEW,
        createdAt = 0L,
        lastSyncedAt = null,
    ).also { playlistDao.insert(it) }

    suspend fun seedSong(
        id: String,
        playlistId: String = "pl1",
        spotifyId: String,
        title: String = "Title",
        artists: String = "Artist",
        position: Int = 0,
        status: SongStatus = SongStatus.DOWNLOADED,
        filePath: String? = null,
        durationMs: Int = 180_000,
    ): SongEntity = SongEntity(
        id = id,
        playlistId = playlistId,
        spotifyId = spotifyId,
        title = title,
        artists = artists,
        album = "Album",
        durationMs = durationMs,
        coverUrl = null,
        ytId = null,
        filePath = filePath,
        status = status,
        position = position,
        addedAt = 0L,
    ).also { songDao.insert(it) }
}
