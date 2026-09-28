package xyz.botolog.ghostify.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.download.DownloadProgressCalculator
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.sync.SyncUseCase
import xyz.botolog.ghostify.ui.playlist.PlaylistSortOption
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec

/**
 * The sort a playlist was saved with is playlist data, not a UI draft: closing the sort
 * sheet records the field *and* the direction on the row, and re-opening the playlist comes
 * back in that order.
 *
 * Adopting the stored sort must not sort again — the positions were written when the sort was
 * committed — and a commit must not be undone by a playlist row that was read before the
 * write landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailStoredSortTest {

    private lateinit var repo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var downloads: DownloadManager
    private lateinit var player: PlayerController
    private lateinit var viewModel: PlaylistDetailViewModel

    private val playlist = MutableStateFlow(storedPlaylist(PLAYLIST_ORDER))
    private val appliedOrders = mutableListOf<List<String>>()
    private val savedSorts = mutableListOf<Triple<String, String, Boolean>>()

    @Before
    fun setUp() {
        appliedOrders.clear()
        savedSorts.clear()
        playlist.value = storedPlaylist(PLAYLIST_ORDER)
        repo = mockk(relaxed = true)
        every { repo.observePlaylist(PLAYLIST_ID) } returns playlist
        every { repo.observeSongs(PLAYLIST_ID) } returns flowOf(emptyList())
        coEvery { repo.saveTrackSort(any(), any(), any()) } answers {
            savedSorts += Triple(firstArg(), secondArg(), thirdArg())
        }
        coEvery { repo.applySongOrder(any(), any()) } answers {
            appliedOrders += secondArg<List<String>>()
            true
        }
        songRepo = mockk(relaxed = true)
        coEvery { songRepo.getSongs(PLAYLIST_ID) } returns SONGS
        downloads = mockk(relaxed = true)
        every { downloads.observeProgress(PLAYLIST_ID) } returns flowOf(
            DownloadProgressCalculator.fromSongs(PLAYLIST_ID, emptyList()),
        )
        player = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.clear()
        Dispatchers.resetMain()
    }

    // ── Commit persists field and direction ────────────────────────────────

    @Test
    fun closingTheSortSheetPersistsTheFieldAndTheDirection() = runTest {
        startViewModel()
        settled()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.ARTIST, descending = true))
        advanceUntilIdle()

        assertEquals(listOf(Triple(PLAYLIST_ID, "artist", true)), savedSorts)
    }

    @Test
    fun anAscendingSortPersistsAsAscending() = runTest {
        startViewModel()
        settled()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.ALBUM))
        advanceUntilIdle()

        assertEquals(listOf(Triple(PLAYLIST_ID, "album", false)), savedSorts)
    }

    @Test
    fun goingBackToPlaylistOrderIsAlsoPersisted() = runTest {
        startViewModel()
        settled()
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE, descending = true))
        advanceUntilIdle()
        savedSorts.clear()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER))
        advanceUntilIdle()

        assertEquals(listOf(Triple(PLAYLIST_ID, PLAYLIST_ORDER, false)), savedSorts)
    }

    @Test
    fun aCommittedSortIsPersistedOnceForTheSortThatWasChosen() = runTest {
        startViewModel()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.DURATION, descending = true))
        advanceUntilIdle()
        settled()

        assertEquals(listOf(Triple(PLAYLIST_ID, "duration", true)), savedSorts)
    }

    @Test
    fun aCommittedSortStillRewritesThePositions() = runTest {
        startViewModel()
        settled()
        appliedOrders.clear()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "b", "c")), appliedOrders)
    }

    // ── Opening adopts what was stored ─────────────────────────────────────

    @Test
    fun openingAPlaylistComesBackInItsStoredSort() = runTest {
        playlist.value = storedPlaylist("artist", descending = true)
        startViewModel()
        settled()

        assertEquals(
            PlaylistSortSpec(option = PlaylistSortOption.ARTIST, descending = true),
            viewModel.sortMode.value,
        )
    }

    @Test
    fun aDescendingStoredSortIsAdoptedWithItsDirection() = runTest {
        playlist.value = storedPlaylist("title", descending = true)
        startViewModel()
        settled()

        assertEquals(
            PlaylistSortSpec(option = PlaylistSortOption.TITLE, descending = true),
            viewModel.sortMode.value,
        )
    }

    @Test
    fun anAscendingStoredSortKeepsItsDirection() = runTest {
        playlist.value = storedPlaylist("title", descending = false)
        startViewModel()
        settled()

        assertEquals(
            PlaylistSortSpec(option = PlaylistSortOption.TITLE, descending = false),
            viewModel.sortMode.value,
        )
    }

    @Test
    fun adoptingTheStoredSortDoesNotSortAgain() = runTest {
        playlist.value = storedPlaylist("title", descending = true)
        startViewModel()
        settled()

        coVerify(exactly = 0) { repo.applySongOrder(any(), any()) }
        coVerify(exactly = 0) { repo.saveTrackSort(any(), any(), any()) }
    }

    @Test
    fun aNeverSortedPlaylistStaysInItsOwnOrder() = runTest {
        playlist.value = storedPlaylist(PLAYLIST_ORDER, descending = false)
        startViewModel()
        settled()

        assertEquals(PlaylistSortSpec(), viewModel.sortMode.value)
        coVerify(exactly = 0) { repo.applySongOrder(any(), any()) }
    }

    @Test
    fun aStoredFieldThisBuildDoesNotKnowFallsBackToTheStoredOrder() = runTest {
        playlist.value = storedPlaylist("mood", descending = true)
        startViewModel()
        settled()

        assertEquals(
            PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER, descending = true),
            viewModel.sortMode.value,
        )
    }

    // ── A commit is not undone by the row it is writing ────────────────────

    @Test
    fun theRowReadBeforeTheWriteLandsCannotUndoACommittedSort() = runTest {
        coEvery { repo.saveTrackSort(any(), any(), any()) } answers {
            savedSorts += Triple(firstArg(), secondArg(), thirdArg())
            playlist.value = storedPlaylist(PLAYLIST_ORDER)
        }
        startViewModel()
        settled()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.ARTIST, descending = true))
        advanceUntilIdle()

        assertEquals(
            PlaylistSortSpec(option = PlaylistSortOption.ARTIST, descending = true),
            viewModel.sortMode.value,
        )
    }

    @Test
    fun aFailedSortWriteStillLeavesTheChosenSortActive() = runTest {
        coEvery { repo.saveTrackSort(any(), any(), any()) } throws IllegalStateException("db is locked")
        startViewModel()
        settled()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()

        assertEquals(PlaylistSortSpec(option = PlaylistSortOption.TITLE), viewModel.sortMode.value)
    }

    @Test
    fun persistingASortNeverTouchesThePlaybackQueue() = runTest {
        startViewModel()
        settled()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.ARTIST_ALBUM_TITLE, descending = true))
        advanceUntilIdle()

        verify(exactly = 0) { player.addToQueueNext(any(), any()) }
        verify(exactly = 0) { player.playPlaylistLazy(any(), any(), any(), any()) }
        verify(exactly = 0) { player.reorderQueue(any(), any()) }
        verify(exactly = 0) { player.setShuffleEnabled(any()) }
    }

    /**
     * Waits for the first rendered state, then drains the scheduler.
     *
     * The playlist is mapped on a real `Dispatchers.IO` hop, so `advanceUntilIdle` alone can
     * return with that hop still in flight — and a test that then restores the main dispatcher
     * would turn the late continuation into an uncaught failure for whichever test runs next.
     * The state is published last in the mapping, so waiting for it means nothing is left
     * queued.
     */
    private suspend fun TestScope.settled() {
        viewModel.state.first { !it.loading }
        advanceUntilIdle()
    }

    private fun TestScope.startViewModel() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = PlaylistDetailViewModel(
            playlistId = PLAYLIST_ID,
            repo = repo,
            songRepo = songRepo,
            downloads = downloads,
            syncer = mockk<SyncUseCase>(relaxed = true),
            player = player,
            deletion = mockk(relaxed = true),
        )
    }

    private fun storedPlaylist(sortField: String, descending: Boolean = false) = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = "spotify-playlist",
        name = "Playlist",
        sortField = sortField,
        sortDescending = descending,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
        const val PLAYLIST_ORDER = PlaylistEntity.SORT_FIELD_PLAYLIST_ORDER

        private fun song(id: String, title: String, position: Int) = SongEntity(
            id = id,
            playlistId = PLAYLIST_ID,
            spotifyId = "spotify-$id",
            title = title,
            artists = "Artist",
            position = position,
        )

        /** Stored in playlist order (Charlie, Bravo, Alpha) — not yet in A–Z order. */
        val SONGS = listOf(
            song("c", "Charlie", 0),
            song("b", "Bravo", 1),
            song("a", "Alpha", 2),
        )
    }
}
