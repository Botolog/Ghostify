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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.core.PlayerUiState as CorePlayerUiState
import xyz.botolog.ghostify.ui.contract.PlayerContract.SongMetadataUpdateResult

/**
 * Saving the info dialog's edit is a two-step act: the song row is written first, and the
 * player is only refreshed once the database confirmed the write. Anything that stops the
 * row from being written — a blank title, an id no longer in the library, a failing write
 * — must leave the database alone and report back so the dialog can stay open, instead of
 * half-applying the edit to the player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelMetadataUpdateTest {

    private lateinit var player: PlayerController
    private lateinit var songDao: SongDao
    private lateinit var downloads: DownloadManager
    private lateinit var settings: SettingsRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel

    private val results = mutableListOf<SongMetadataUpdateResult>()
    private var writtenRows = 1
    private var writeFailure: Exception? = null

    @Before
    fun setUp() {
        results.clear()
        writtenRows = 1
        writeFailure = null
        player = mockk(relaxed = true)
        every { player.state } returns MutableStateFlow(CorePlayerUiState())
        songDao = mockk(relaxed = true)
        every { songDao.observeLyricsById(any()) } returns flowOf(null)
        every { songDao.observeById(any()) } returns flowOf(null)
        coEvery { songDao.updateMetadata(any(), any(), any(), any()) } answers {
            writeFailure?.let { throw it }
            writtenRows
        }
        downloads = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { settings.observeShowQueueCovers() } returns flowOf(true)
        playlists = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun savingWritesTheSongRowAndRefreshesThePlayer() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "  Renamed  ", " Ada, Mike ", " Back Catalogue ", results::add)
        advanceUntilIdle()

        coVerify(exactly = 1) { songDao.updateMetadata(SONG_ID, "Renamed", "Ada, Mike", "Back Catalogue") }
        verify(exactly = 1) { player.updateQueueItemMetadata(SONG_ID, "Renamed", "Ada, Mike", "Back Catalogue") }
        assertEquals(listOf(SongMetadataUpdateResult.Updated), results)
    }

    @Test
    fun aClearedAlbumIsPersistedAsNoAlbum() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Title", "Artist", "   ", results::add)
        advanceUntilIdle()

        coVerify(exactly = 1) { songDao.updateMetadata(SONG_ID, "Title", "Artist", null) }
        verify(exactly = 1) { player.updateQueueItemMetadata(SONG_ID, "Title", "Artist", null) }
        assertEquals(listOf(SongMetadataUpdateResult.Updated), results)
    }

    @Test
    fun anEmptyTitleIsNeverWritten() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "   ", "Artist", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { songDao.updateMetadata(any(), any(), any(), any()) }
        verify(exactly = 0) { player.updateQueueItemMetadata(any(), any(), any(), any()) }
        assertEquals(
            listOf(SongMetadataUpdateResult.Failed(PlayerViewModel.METADATA_TITLE_REQUIRED)),
            results,
        )
    }

    @Test
    fun aMissingSongIdIsNeverWritten() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata("  ", "Title", "Artist", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 0) { songDao.updateMetadata(any(), any(), any(), any()) }
        verify(exactly = 0) { player.updateQueueItemMetadata(any(), any(), any(), any()) }
        assertEquals(
            listOf(SongMetadataUpdateResult.Failed(PlayerViewModel.METADATA_SONG_MISSING)),
            results,
        )
    }

    @Test
    fun aSongThatVanishedFromTheDatabaseLeavesThePlayerAlone() = runTest {
        writtenRows = 0
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Title", "Artist", "Album", results::add)
        advanceUntilIdle()

        coVerify(exactly = 1) { songDao.updateMetadata(SONG_ID, "Title", "Artist", "Album") }
        verify(exactly = 0) { player.updateQueueItemMetadata(any(), any(), any(), any()) }
        assertEquals(
            listOf(SongMetadataUpdateResult.Failed(PlayerViewModel.METADATA_SONG_MISSING)),
            results,
        )
    }

    @Test
    fun aFailingWriteLeavesThePlayerAloneAndReportsAFriendlyReason() = runTest {
        writeFailure = IllegalStateException("SQLiteConstraintException: table songs is locked")
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "Title", "Artist", "Album", results::add)
        advanceUntilIdle()

        verify(exactly = 0) { player.updateQueueItemMetadata(any(), any(), any(), any()) }
        val failure = results.single() as SongMetadataUpdateResult.Failed
        assertEquals(PlayerViewModel.METADATA_SAVE_FAILED, failure.reason)
        assertFalse(failure.reason.contains("SQLite"))
    }

    @Test
    fun everySaveReportsExactlyOneOutcome() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        viewModel = PlayerViewModel(player, songDao, downloads, settings, playlists)
        advanceUntilIdle()

        viewModel.updateSongMetadata(SONG_ID, "First", "Artist", "Album", results::add)
        viewModel.updateSongMetadata(SONG_ID, "Second", "Artist", "Album", results::add)
        viewModel.updateSongMetadata(SONG_ID, "  ", "Artist", "Album", results::add)
        advanceUntilIdle()

        assertEquals(3, results.size)
        coVerify(exactly = 2) { songDao.updateMetadata(any(), any(), any(), any()) }
    }

    private companion object {
        const val SONG_ID = "song-1"
    }
}
