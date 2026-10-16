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

    private fun useCase(
        downloader: (suspend (String, String) -> String?)? = null,
    ): SyncUseCase = SyncUseCase(
        playlists = playlistDao,
        songs = songDao,
        transactions = DirectTransactions,
        fetcher = fetcher,
        files = NoFiles,
        enqueuer = DownloadEnqueuer { },
        locks = SyncLocks(),
        now = { NOW },
        newId = { "new" },
        playlistCoverDownloader = downloader,
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

    @Test
    fun syncNeverOverwritesLocalCoverWhenRemoteCoverMissing() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = null),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://old/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun syncKeepsLocalCoverWhenRemoteCoverUnchanged() = runTest {
        stub(
            stored = playlist(coverUrl = "https://same/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://same/cover.jpg"),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://same/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun syncClearsStaleLocalCoverWhenRemoteCoverChanged() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )

        useCase().syncPlaylist(PLAYLIST_ID)

        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        // Old file would shadow the new URL via local-first precedence.
        assertEquals(null, updated.captured.coverArtLocalPath)
    }

    @Test
    fun coverChangedRedownloadsAndStoresFreshLocalPath() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )
        val requested = mutableListOf<Pair<String, String>>()

        useCase(downloader = { playlistId, coverUrl ->
            requested += playlistId to coverUrl
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(listOf(PLAYLIST_ID to "https://new/cover.jpg"), requested)
        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun coverChangedTrimsUrlBeforeDownload() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg"),
            remote = metadata(coverUrl = "  https://new/cover.jpg  "),
        )
        val requested = mutableListOf<String>()

        useCase(downloader = { _, coverUrl ->
            requested += coverUrl
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(listOf("https://new/cover.jpg"), requested)
        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun firstCoverDownloadsWhenStoredCoverIsNull() = runTest {
        stub(
            stored = playlist(coverUrl = null),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )
        var calls = 0

        useCase(downloader = { _, _ ->
            calls++
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(1, calls)
        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun coverUnchangedSkipsDownload() = runTest {
        stub(
            stored = playlist(coverUrl = "https://same/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://same/cover.jpg"),
        )
        var calls = 0

        useCase(downloader = { _, _ ->
            calls++
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, calls)
        assertEquals("https://same/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun whitespaceOnlyDifferenceSkipsDownload() = runTest {
        stub(
            stored = playlist(coverUrl = "https://same/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "  https://same/cover.jpg  "),
        )
        var calls = 0

        useCase(downloader = { _, _ ->
            calls++
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, calls)
        assertEquals("https://same/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun blankRemoteCoverSkipsDownload() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "   "),
        )
        var calls = 0

        useCase(downloader = { _, _ ->
            calls++
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, calls)
        assertEquals("https://old/cover.jpg", updated.captured.coverUrl)
        assertEquals("/covers/playlist_pl.jpg", updated.captured.coverArtLocalPath)
    }

    @Test
    fun queryParamChangeCountsAsChanged() = runTest {
        stub(
            stored = playlist(coverUrl = "https://cdn/cover.jpg"),
            remote = metadata(coverUrl = "https://cdn/cover.jpg?version=2"),
        )
        var calls = 0

        useCase(downloader = { _, _ ->
            calls++
            "/covers/playlist_pl.jpg"
        }).syncPlaylist(PLAYLIST_ID)

        // No query-param stripping: a param-only change costs one JPEG download
        // rather than risking stale art.
        assertEquals(1, calls)
        assertEquals("https://cdn/cover.jpg?version=2", updated.captured.coverUrl)
    }

    @Test
    fun downloadFailureStillSucceedsAndClearsStaleLocal() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )

        val result = useCase(downloader = { _, _ -> null }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, result.added)
        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        // Stale file cleared so the new remote URL displays instead of wrong old art.
        assertEquals(null, updated.captured.coverArtLocalPath)
    }

    @Test
    fun downloadExceptionStillSucceedsWithoutCorruptingDb() = runTest {
        stub(
            stored = playlist(coverUrl = "https://old/cover.jpg", localPath = "/covers/playlist_pl.jpg"),
            remote = metadata(coverUrl = "https://new/cover.jpg"),
        )

        val result = useCase(downloader = { _, _ ->
            throw RuntimeException("offline")
        }).syncPlaylist(PLAYLIST_ID)

        assertEquals(0, result.added)
        assertEquals("https://new/cover.jpg", updated.captured.coverUrl)
        assertEquals(null, updated.captured.coverArtLocalPath)
    }

    @Test
    fun coverChangeComparisonIsTrimBased() {
        assertEquals(true, SyncUseCase.isPlaylistCoverChanged("https://old/a.jpg", "https://new/b.jpg"))
        assertEquals(true, SyncUseCase.isPlaylistCoverChanged(null, "https://new/b.jpg"))
        assertEquals(false, SyncUseCase.isPlaylistCoverChanged("https://same/a.jpg", "https://same/a.jpg"))
        assertEquals(false, SyncUseCase.isPlaylistCoverChanged("https://same/a.jpg", "  https://same/a.jpg  "))
        assertEquals(false, SyncUseCase.isPlaylistCoverChanged("https://old/a.jpg", null))
        assertEquals(false, SyncUseCase.isPlaylistCoverChanged("https://old/a.jpg", "   "))
        assertEquals(null, SyncUseCase.normalizeCoverUrl("   "))
        assertEquals("https://a/b.jpg", SyncUseCase.normalizeCoverUrl("  https://a/b.jpg  "))
    }

    private fun playlist(coverUrl: String?, localPath: String? = null) = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = SPOTIFY_ID,
        name = "Playlist",
        coverUrl = coverUrl,
        coverArtLocalPath = localPath,
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
