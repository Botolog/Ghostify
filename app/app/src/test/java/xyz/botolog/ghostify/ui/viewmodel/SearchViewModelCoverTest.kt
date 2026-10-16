package xyz.botolog.ghostify.ui.viewmodel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.ui.util.resolveCoverImageModel
import java.io.File

/**
 * Search covers must be local-first so results render offline.
 *
 * Regression test for the bug where [SearchViewModel] dropped
 * `coverArtLocalPath` and search rows loaded only the remote URL
 * (blank when offline). Covers resolve through
 * [xyz.botolog.ghostify.ui.util.coverImageModel] with the local file
 * preferred, matching Library and PlaylistDetail rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelCoverTest {

    private lateinit var playlistDao: PlaylistDao
    private lateinit var songDao: SongDao
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        playlistDao = mockk()
        songDao = mockk()
        viewModel = SearchViewModel(playlistDao, songDao)
    }

    @After
    fun tearDown() {
        viewModel.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun playlistResultsCarryLocalCoverPath() = runTest {
        val entity = PlaylistEntity(
            id = "pl-1",
            spotifyId = "spotify-pl-1",
            name = "Chill",
            coverUrl = "https://img/pl.jpg",
            coverArtLocalPath = "/covers/pl.jpg",
            trackCount = 5,
        )
        coEvery { playlistDao.searchByName("chill") } returns listOf(entity)
        coEvery { songDao.searchByTitleOrArtist("chill") } returns emptyList()

        viewModel.onQueryChange("chill")
        advanceTimeBy(400L)
        advanceUntilIdle()

        val results = viewModel.state.value.playlistResults
        assertEquals(1, results.size)
        assertEquals("/covers/pl.jpg", results[0].coverArtLocalPath)
        assertEquals("https://img/pl.jpg", results[0].coverUrl)
    }

    @Test
    fun songResultsCarryLocalCoverPath() = runTest {
        val playlist = PlaylistEntity(
            id = "pl-1",
            spotifyId = "spotify-pl-1",
            name = "Chill",
        )
        val song = SongEntity(
            id = "s-1",
            playlistId = "pl-1",
            spotifyId = "spotify-s-1",
            title = "Chill Song",
            artists = "Artist",
            coverUrl = "https://img/s.jpg",
            coverArtLocalPath = "/covers/s.jpg",
        )
        coEvery { playlistDao.searchByName("chill") } returns emptyList()
        coEvery { songDao.searchByTitleOrArtist("chill") } returns listOf(song)
        coEvery { playlistDao.getById("pl-1") } returns playlist

        viewModel.onQueryChange("chill")
        advanceTimeBy(400L)
        advanceUntilIdle()

        val results = viewModel.state.value.songResults
        assertEquals(1, results.size)
        assertEquals("/covers/s.jpg", results[0].coverArtLocalPath)
        assertEquals("https://img/s.jpg", results[0].coverUrl)
    }

    @Test
    fun offlinePlaylistCoverResolvesFromLocalPathWhenRemoteIsNull() = runTest {
        val entity = PlaylistEntity(
            id = "pl-offline",
            spotifyId = "spotify-pl-offline",
            name = "Offline Mix",
            coverUrl = null,
            coverArtLocalPath = "/covers/offline.jpg",
            trackCount = 3,
        )
        coEvery { playlistDao.searchByName("offline") } returns listOf(entity)
        coEvery { songDao.searchByTitleOrArtist("offline") } returns emptyList()

        viewModel.onQueryChange("offline")
        advanceTimeBy(400L)
        advanceUntilIdle()

        val result = viewModel.state.value.playlistResults.single()
        assertEquals("/covers/offline.jpg", result.coverArtLocalPath)
        assertNull(result.coverUrl)

        // Local-first resolution: the offline file wins over a missing remote URL.
        val model = resolveCoverImageModel(result.coverArtLocalPath, result.coverUrl) { true }
        assertEquals(File("/covers/offline.jpg"), model)
    }

    @Test
    fun offlineSongCoverResolvesFromLocalPathWhenRemoteIsNull() = runTest {
        val playlist = PlaylistEntity(
            id = "pl-1",
            spotifyId = "spotify-pl-1",
            name = "Offline Mix",
        )
        val song = SongEntity(
            id = "s-offline",
            playlistId = "pl-1",
            spotifyId = "spotify-s-offline",
            title = "Offline Song",
            artists = "Artist",
            coverUrl = null,
            coverArtLocalPath = "/covers/offline-song.jpg",
        )
        coEvery { playlistDao.searchByName("offline") } returns emptyList()
        coEvery { songDao.searchByTitleOrArtist("offline") } returns listOf(song)
        coEvery { playlistDao.getById("pl-1") } returns playlist

        viewModel.onQueryChange("offline")
        advanceTimeBy(400L)
        advanceUntilIdle()

        val result = viewModel.state.value.songResults.single()
        assertEquals("/covers/offline-song.jpg", result.coverArtLocalPath)
        assertNull(result.coverUrl)

        val model = resolveCoverImageModel(result.coverArtLocalPath, result.coverUrl) { true }
        assertEquals(File("/covers/offline-song.jpg"), model)
    }

    @Test
    fun searchResultsDefaultToNullLocalPath() {
        val playlist = xyz.botolog.ghostify.ui.contract.SearchContract.PlaylistSearchResult(
            id = "p",
            name = "N",
            trackCount = 0,
        )
        val song = xyz.botolog.ghostify.ui.contract.SearchContract.SongSearchResult(
            songId = "s",
            title = "T",
            artists = "A",
            playlistId = "p",
            playlistName = "N",
            position = 0,
        )
        assertNull(playlist.coverArtLocalPath)
        assertNull(song.coverArtLocalPath)
    }
}
