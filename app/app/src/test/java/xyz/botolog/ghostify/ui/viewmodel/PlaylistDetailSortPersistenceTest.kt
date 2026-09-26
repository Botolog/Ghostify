package xyz.botolog.ghostify.ui.viewmodel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import xyz.botolog.ghostify.sync.SyncResult
import xyz.botolog.ghostify.sync.SyncUseCase
import xyz.botolog.ghostify.ui.playlist.PlaylistSortOption
import xyz.botolog.ghostify.ui.playlist.PlaylistSortSpec

/**
 * Sorting is persistence, not cosmetics: a committed sort must rewrite
 * `songs.position`, must never disturb the playback queue, and must be
 * reapplied when a sync adds tracks.
 *
 * The songs *observation* flow is left unemitted on purpose — sorting reads the
 * complete track set through [SongRepository.getSongs], and keeping the
 * state collector idle makes the assertions deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailSortPersistenceTest {

    private lateinit var repo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var downloads: DownloadManager
    private lateinit var syncer: SyncUseCase
    private lateinit var player: PlayerController
    private lateinit var viewModel: PlaylistDetailViewModel

    private val songs = MutableStateFlow(defaultSongs())
    private val appliedOrders = mutableListOf<List<String>>()
    private val manualReorders = mutableListOf<Pair<String, Int>>()
    private var applySongOrderResult = true

    @Before
    fun setUp() {
        appliedOrders.clear()
        manualReorders.clear()
        applySongOrderResult = true
        repo = mockk(relaxed = true)
        every { repo.observePlaylist(PLAYLIST_ID) } returns flowOf(playlist())
        every { repo.observeSongs(PLAYLIST_ID) } returns emptyFlow()
        coEvery { repo.applySongOrder(any(), any()) } answers {
            val order = secondArg<List<String>>()
            appliedOrders += order
            songs.value = songs.value
                .sortedBy { song -> order.indexOf(song.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .mapIndexed { index, song -> song.copy(position = index) }
            applySongOrderResult
        }
        coEvery { repo.reorderSong(any(), any()) } answers {
            manualReorders += firstArg<String>() to secondArg()
        }
        songRepo = mockk(relaxed = true)
        coEvery { songRepo.getSongs(PLAYLIST_ID) } answers { songs.value }
        downloads = mockk(relaxed = true)
        every { downloads.observeProgress(PLAYLIST_ID) } returns flowOf(
            DownloadProgressCalculator.fromSongs(PLAYLIST_ID, emptyList()),
        )
        syncer = mockk(relaxed = true)
        coEvery { syncer.syncPlaylist(PLAYLIST_ID) } returns syncResult()
        player = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        viewModel.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun commitSortPersistsPositionsInTheSelectedOrder() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "b", "c")), appliedOrders)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), storedPositions())
    }

    @Test
    fun commitSortNeverTouchesThePlaybackQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()

        verify(exactly = 0) { player.addToQueueNext(any(), any()) }
        verify(exactly = 0) { player.playPlaylistLazy(any(), any(), any(), any()) }
        verify(exactly = 0) { player.reorderQueue(any(), any()) }
        verify(exactly = 0) { player.setShuffleEnabled(any()) }
    }

    @Test
    fun sortIsNotPersistedUntilTheSheetIsClosed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        assertEquals(PlaylistSortSpec(), viewModel.sortMode.value)
        assertEquals(emptyList<List<String>>(), appliedOrders)
    }

    @Test
    fun syncCompletionReappliesTheActiveSortToTheCompleteTrackSet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()
        appliedOrders.clear()

        songs.value = defaultSongs() + song("d", title = "apple", position = 3)
        viewModel.sync()
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "d", "b", "c")), appliedOrders)
        assertEquals(listOf("a" to 0, "d" to 1, "b" to 2, "c" to 3), storedPositions())
    }

    @Test
    fun syncCompletionPersistsTheCommittedSortNotADraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()
        appliedOrders.clear()

        viewModel.sync()
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "b", "c")), appliedOrders)
    }

    @Test
    fun repeatedSortsCoalesceIntoASingleSortJobForTheLatestSort() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.ARTIST))
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()

        assertEquals(listOf(listOf("a", "b", "c")), appliedOrders)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), storedPositions())
    }

    @Test
    fun reapplyingTheSameSortDoesNotRewritePositions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.TITLE))
        advanceUntilIdle()
        applySongOrderResult = false
        appliedOrders.clear()

        viewModel.sync()
        advanceUntilIdle()

        assertEquals(1, appliedOrders.size)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), storedPositions())
    }

    @Test
    fun storedOrderSortWritesNothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.commitSort(PlaylistSortSpec(option = PlaylistSortOption.PLAYLIST_ORDER))
        viewModel.sync()
        advanceUntilIdle()

        assertEquals(emptyList<List<String>>(), appliedOrders)
    }

    @Test
    fun manualReorderIsStillPersisted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.reorderSong("c", 0)
        advanceUntilIdle()

        assertEquals(listOf("c" to 0), manualReorders)
    }

    /**
     * The `(id, position)` pairs currently stored in the fake database, in
     * `songs.position` order.
     */
    private fun storedPositions(): List<Pair<String, Int>> =
        songs.value.sortedBy { it.position }.map { it.id to it.position }

    private fun createViewModel() = PlaylistDetailViewModel(
        playlistId = PLAYLIST_ID,
        repo = repo,
        songRepo = songRepo,
        downloads = downloads,
        syncer = syncer,
        player = player,
    )

    private fun playlist() = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = "spotify-playlist",
        name = "Playlist",
        trackCount = 3,
    )

    private fun defaultSongs(): List<SongEntity> = listOf(
        song("c", title = "Charlie", artist = "Ada", position = 0),
        song("b", title = "Bravo", artist = "Mike", position = 1),
        song("a", title = "Alpha", artist = "Zulu", position = 2),
    )

    private fun song(id: String, title: String = id, artist: String = "Artist", position: Int) = SongEntity(
        id = id,
        playlistId = PLAYLIST_ID,
        spotifyId = "spotify-$id",
        title = title,
        artists = artist,
        position = position,
    )

    private fun syncResult() = SyncResult(
        added = 0,
        removed = 0,
        requeued = 0,
        metadataUpdated = 0,
        filesDeleted = 0,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
    }
}
