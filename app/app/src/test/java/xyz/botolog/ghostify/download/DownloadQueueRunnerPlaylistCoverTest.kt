package xyz.botolog.ghostify.download

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.player.ArtworkPersistence

class DownloadQueueRunnerPlaylistCoverTest {

    private class FakeRepo(
        songs: List<SongRecord>,
        var playlist: PlaylistRecord?,
    ) : DownloadRepository {
        private val songMap = songs.associateBy { it.id }.toMutableMap()
        val playlistCoverUpdates = mutableListOf<Pair<String, String?>>()

        override suspend fun songsFor(playlistId: String): List<SongRecord> = songMap.values.toList()
        override fun observeSongs(playlistId: String): Flow<List<SongRecord>> = flowOf(songMap.values.toList())
        override suspend fun getSong(songId: String): SongRecord? = songMap[songId]
        override suspend fun setStatus(songId: String, status: DownloadStatus, filePath: String?, error: String?, lyrics: String?) {
            songMap[songId]?.let { songMap[songId] = it.copy(status = status, filePath = filePath ?: it.filePath) }
        }
        override suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus) {
            songIds.forEach { id -> songMap[id]?.let { songMap[id] = it.copy(status = status) } }
        }
        override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus? = playlist?.status
        override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
            playlist = playlist?.copy(status = status)
        }
        override suspend fun getPlaylist(playlistId: String): PlaylistRecord? = playlist
        override suspend fun allPlaylistIds(): List<String> = listOf("pl1")
        override suspend fun updateLyrics(songId: String, lyrics: String?) = Unit
        override suspend fun updateYoutubeMeta(songId: String, ytUrl: String?, ytName: String?, ytChannel: String?) = Unit
        override suspend fun updateDownloadMeta(songId: String, bitrate: Int?, fileSize: Long?, downloadedAt: Long?) = Unit
        override suspend fun updateLyricsSource(songId: String, source: String?) = Unit
        override suspend fun updateLyricsEdited(songId: String, edited: Boolean) = Unit
        override suspend fun updateCoverArtLocalPath(songId: String, path: String?) = Unit
        override suspend fun updatePlaylistCoverArtLocalPath(playlistId: String, path: String?) {
            playlistCoverUpdates.add(playlistId to path)
            playlist = playlist?.copy(coverArtLocalPath = path)
        }
    }

    private class OkDownloader : TrackDownloader {
        override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult =
            TrackDownloadResult(songId = song.id, filePath = "/dl/${song.id}.mp3")
        override suspend fun cancel(songId: String) = Unit
    }

    private fun song(id: String, pos: Int) = SongRecord(
        id = id,
        playlistId = "pl1",
        spotifyId = id,
        title = "T $id",
        artists = "A",
        position = pos,
        status = DownloadStatus.PENDING,
    )

    private fun runner(
        repo: FakeRepo,
        persistence: ArtworkPersistence,
        fetcher: (String) -> ByteArray? = { ByteArray(0) },
    ) = DownloadQueueRunner(
        repo = repo,
        downloader = OkDownloader(),
        concurrencySupplier = { 1 },
        artworkPersistence = persistence,
        playlistCoverFetcher = fetcher,
    )

    @Test
    fun `own playlist cover is downloaded and first-song copy never used`() = runBlocking {
        val repo = FakeRepo(
            songs = listOf(song("s1", 0)),
            playlist = PlaylistRecord(id = "pl1", status = PlaylistStatus.DOWNLOADING, coverUrl = "https://img/pl.jpg", coverArtLocalPath = null),
        )
        val persistence = mockk<ArtworkPersistence>()
        every { persistence.persist(any(), any()) } returns null
        every { persistence.playlistLocalUsable(any()) } returns false
        every { persistence.persistPlaylistCoverFromUrl("pl1", "https://img/pl.jpg", any()) } returns "/covers/playlist_pl1.jpg"
        every { persistence.persistPlaylistCover(any(), any()) } returns null

        val outcome = runner(repo, persistence, fetcher = { "bytes".toByteArray() }).run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        assertEquals(listOf("pl1" to "/covers/playlist_pl1.jpg"), repo.playlistCoverUpdates)
        verify(exactly = 0) { persistence.persistPlaylistCover(any(), any()) }
    }

    @Test
    fun `existing usable local playlist cover is kept and never overwritten`() = runBlocking {
        val repo = FakeRepo(
            songs = listOf(song("s1", 0)),
            playlist = PlaylistRecord(id = "pl1", status = PlaylistStatus.DOWNLOADING, coverUrl = "https://img/pl.jpg", coverArtLocalPath = "/covers/playlist_pl1.jpg"),
        )
        val persistence = mockk<ArtworkPersistence>()
        every { persistence.persist(any(), any()) } returns null
        every { persistence.playlistLocalUsable("/covers/playlist_pl1.jpg") } returns true
        // Should never be called when local is usable.
        every { persistence.persistPlaylistCoverFromUrl(any(), any(), any()) } returns null
        every { persistence.persistPlaylistCover(any(), any()) } returns null

        val outcome = runner(repo, persistence).run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        assertTrue(repo.playlistCoverUpdates.isEmpty())
        verify(exactly = 0) { persistence.persistPlaylistCover(any(), any()) }
        verify(exactly = 0) { persistence.persistPlaylistCoverFromUrl(any(), any(), any()) }
    }

    @Test
    fun `first-song copy is used only when playlist has no own cover`() = runBlocking {
        val repo = FakeRepo(
            songs = listOf(song("s1", 0)),
            playlist = PlaylistRecord(id = "pl1", status = PlaylistStatus.DOWNLOADING, coverUrl = null, coverArtLocalPath = null),
        )
        val persistence = mockk<ArtworkPersistence>()
        every { persistence.persist(any(), any()) } returns null
        every { persistence.playlistLocalUsable(any()) } returns false
        every { persistence.persistPlaylistCover("pl1", "s1") } returns "/covers/playlist_pl1.jpg"

        val outcome = runner(repo, persistence).run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        assertEquals(listOf("pl1" to "/covers/playlist_pl1.jpg"), repo.playlistCoverUpdates)
    }

    @Test
    fun `failed own-cover download leaves remote URL instead of wrong art`() = runBlocking {
        val repo = FakeRepo(
            songs = listOf(song("s1", 0)),
            playlist = PlaylistRecord(id = "pl1", status = PlaylistStatus.DOWNLOADING, coverUrl = "https://img/pl.jpg", coverArtLocalPath = null),
        )
        val persistence = mockk<ArtworkPersistence>()
        every { persistence.persist(any(), any()) } returns null
        every { persistence.playlistLocalUsable(any()) } returns false
        every { persistence.persistPlaylistCoverFromUrl(any(), any(), any()) } returns null
        every { persistence.persistPlaylistCover(any(), any()) } returns "/covers/playlist_pl1.jpg"

        val outcome = runner(repo, persistence, fetcher = { null }).run("pl1")

        assertEquals(RunOutcome.COMPLETED, outcome)
        // Own download failed -> no DB write, and crucially no first-song copy.
        assertTrue(repo.playlistCoverUpdates.isEmpty())
        verify(exactly = 0) { persistence.persistPlaylistCover(any(), any()) }
    }
}
