package xyz.botolog.ghostify.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.core.PlayerUiState as CorePlayerUiState
import xyz.botolog.ghostify.ui.contract.PlayerContract.SongMetadataUpdateResult

/**
 * A playlist is sorted by writing `songs.position`, so an edit made in the player's info
 * dialog can leave a track in the wrong place — "Alpha" renamed to "Zulu" in an A–Z playlist
 * is still sitting at the top. Saving therefore re-applies the sort the playlist was saved
 * with, so the track lands where that sort says it should.
 *
 * The re-sort is a database concern only: the playback queue keeps the order it was built
 * with, and a playlist in its own order is never re-written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelPlaylistSortTest {

    private lateinit var player: PlayerController
    private lateinit var songDao: SongDao
    private lateinit var downloads: DownloadManager
    private lateinit var settings: SettingsRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel

    private val songs = MutableStateFlow(storedSongs())
    private val appliedOrders = mutableListOf<List<String>>()
    private val results = mutableListOf<SongMetadataUpdateResult>()

    /** The sort the playlist row carries, and the "title, ascending" default. */
    private var storedSort: Pair<String, Boolean> = "title" to false
    private var sortFailure: Exception? = null

    @Before
    fun setUp() {
        songs.value = storedSongs()
        appliedOrders.clear()
        results.clear()
        storedSort = "title" to false
        sortFailure = null
        player = mockk(relaxed = true)
        every { player.state } returns MutableStateFlow(CorePlayerUiState())
        songDao = mockk(relaxed = true)
        every { songDao.observeLyricsById(any()) } returns flowOf(null)
        every { songDao.observeById(any()) } returns flowOf(null)
        coEvery { songDao.updateMetadata(any(), any(), any(), any()) } answers {
            val id = firstArg<String>()
            val title = secondArg<String>()
            val artists = thirdArg<String>()
            songs.value = songs.value.map {
                if (it.id == id) it.copy(title = title, artists = artists) else it
            }
            1
        }
        coEvery { songDao.getById(SONG_ID) } answers { songs.value.first { it.id == SONG_ID } }
        playlists = mockk(relaxed = true)
        coEvery { playlists.getPlaylist(PLAYLIST_ID) } answers { storedPlaylist() }
        coEvery { playlists.getSongs(PLAYLIST_ID) } answers { songs.value }
        coEvery { playlists.applySongOrder(any(), any()) } answers {
            sortFailure?.let { throw it }
            appliedOrders += secondArg<List<String>>()
            true
        }
        downloads = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { settings.observeShowQueueCovers() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.clear()
        Dispatchers.resetMain()
    }

    // ── A saved sort is re-applied to the edited track ─────────────────────

    @Test
    fun renamingATrackMovesItIntoThePlaylistSort() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Back Catalogue", results::add)
        advanceUntilIdle()

        assertEquals(listOf(listOf("b", "c", "a")), appliedOrders)
        assertEquals(SongMetadataUpdateResult.Updated, results.single())
    }

    @Test
    fun theReSortUsesTheDirectionThePlaylistWasSavedWith() = runTest {
        storedSort = "title" to true
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "c", "b")), appliedOrders)
    }

    @Test
    fun anArtistSortIsReAppliedToTheEditedArtist() = runTest {
        storedSort = "artist" to false
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Alpha", "Zorro", "Album", results::add)
        advanceUntilIdle()

        assertEquals(listOf(listOf("c", "b", "a")), appliedOrders)
    }

    @Test
    fun theMetadataIsWrittenBeforeTheTrackIsMoved() = runTest {
        val steps = mutableListOf<String>()
        coEvery { songDao.updateMetadata(any(), any(), any(), any()) } answers {
            steps += "metadata"
            1
        }
        coEvery { playlists.applySongOrder(any(), any()) } answers {
            steps += "order"
            true
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        assertEquals(listOf("metadata", "order"), steps)
    }

    @Test
    fun reSortingNeverReordersThePlaybackQueue() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        verify(exactly = 0) { player.reorderQueue(any(), any()) }
        verify(exactly = 0) { player.setShuffleEnabled(any()) }
        verify(exactly = 0) { player.addToQueueNext(any(), any()) }
        verify(exactly = 0) { player.playPlaylistLazy(any(), any(), any(), any()) }
        verify(exactly = 0) { player.skipToMediaItem(any()) }
    }

    // ── A playlist in its own order is left alone ──────────────────────────

    @Test
    fun aPlaylistInItsOwnOrderIsNeverReSorted() = runTest {
        storedSort = PlaylistEntity.SORT_FIELD_PLAYLIST_ORDER to false
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { playlists.applySongOrder(any(), any()) }
        assertEquals(SongMetadataUpdateResult.Updated, results.single())
    }

    @Test
    fun aStoredFieldThisBuildDoesNotKnowIsLeftAlone() = runTest {
        storedSort = "mood" to false
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { playlists.applySongOrder(any(), any()) }
    }

    @Test
    fun aSingleTrackPlaylistIsNotReSorted() = runTest {
        songs.value = listOf(storedSongs().first())
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { playlists.applySongOrder(any(), any()) }
    }

    // ── A failed edit never re-sorts ───────────────────────────────────────

    @Test
    fun aFailedWriteNeitherRefreshesThePlayerNorSorts() = runTest {
        coEvery { songDao.updateMetadata(any(), any(), any(), any()) } throws IllegalStateException("locked")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { playlists.applySongOrder(any(), any()) }
        verify(exactly = 0) { player.updateQueueItemMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun aFailedReSortStillLeavesTheSavedMetadataInPlace() = runTest {
        sortFailure = IllegalStateException("positions are locked")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Zulu", "Ada", "Album", results::add)
        advanceUntilIdle()

        assertEquals(listOf(SongMetadataUpdateResult.Updated), results)
        coVerify(exactly = 1) { songDao.updateMetadata(SONG_ID, "Zulu", "Ada", "Album") }
    }

    private fun createViewModel() =
        PlayerViewModel(player, songDao, downloads, settings, playlists)

    private fun storedPlaylist() = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = "spotify-playlist",
        name = "Playlist",
        sortField = storedSort.first,
        sortDescending = storedSort.second,
    )

    private fun storedSongs(): List<SongEntity> = listOf(
        song("a", title = "Alpha", artist = "Ada", position = 0),
        song("b", title = "Bravo", artist = "Mike", position = 1),
        song("c", title = "Charlie", artist = "Ada", position = 2),
    )

    private fun song(id: String, title: String, artist: String, position: Int) = SongEntity(
        id = id,
        playlistId = PLAYLIST_ID,
        spotifyId = "spotify-$id",
        title = title,
        artists = artist,
        position = position,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
        const val SONG_ID = "a"
    }
}
