package xyz.botolog.ghostify.sync

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.data.model.PlaylistStatus
import xyz.botolog.ghostify.python.PlaylistMetadata

class SyncUseCaseCoverTest {

    private val playlistDao = mockk<PlaylistDao>()
    private val songDao = mockk<SongDao>()
    private val fetcher = mockk<SpotifyPlaylistFetcher>()
    private val updated = slot<PlaylistEntity>()

    private fun useCase(): SyncUseCase = SyncUseCase(
        playlists = playlistDao,
        songs = songDao,
        transactions = DirectTransactions,
        fetcher = fetcher,
        files = NoFiles,
        enqueuer = DownloadEnqueuer { },
        locks = SyncLocks(),
        now = { NOW },
        newId = { "new" },
    )

    private fun stub(stored: PlaylistEntity, remote: PlaylistMetadata) {
        coEvery { playlistDao.getById(PLAYLIST_ID) } returns stored
        coEvery { playlistDao.update(capture(updated)) } returns Unit
        coEvery { songDao.getSongsForPlaylist(PLAYLIST_ID) } returns emptyList()
        coEvery { fetcher.fetchPlaylist(SPOTIFY_ID, PlaylistOrigin.SPOTIFY) } returns remote
    }

    @Test
    fun remoteCoverUrlReplacesTheStoredOne() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg"),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
    }

    @Test
    fun missingRemoteCoverKeepsTheStoredOne() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg"),
            remote = metadata(coverUrl = null),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://old/cover.jpg", updated.captured.coverUrl)
    }

    @Test
    fun blankRemoteCoverKeepsTheStoredOne() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg"),
            remote = metadata(coverUrl = "   "),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://old/cover.jpg", updated.captured.coverUrl)
    }

    @Test
    fun missingRemoteCoverLeavesAnUnsetCoverUnset() = runTest {
        stub(stored = playlist(coverUrl = null), remote = metadata(coverUrl = null))

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals(null, updated.captured.coverUrl)
    }

    private fun playlist(coverUrl: String?) = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = SPOTIFY_ID,
        name = "Playlist",
        coverUrl = coverUrl,
        status = PlaylistStatus.NEW,
    )

    private fun metadata(coverUrl: String?) = PlaylistMetadata(
        name = "Playlist",
        owner = "Owner",
        coverUrl = coverUrl,
        description = null,
        trackCount = 0,
        tracks = emptyList(),
    )

    private object DirectTransactions : TransactionRunner {
        override suspend fun <R> withinTransaction(block: suspend () -> R): R = block()
    }

    private object NoFiles : LocalFileStore {
        override fun exists(filePath: String?): Boolean = false
        override fun delete(filePath: String) = Unit
    }

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
        const val SPOTIFY_ID = "spotify-1"
        const val NOW = 1_000L
    }
}
