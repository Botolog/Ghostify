package xyz.botolog.ghostify.ui.viewmodel

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.core.PlayerUiState as CorePlayerUiState

/**
 * The sleep timer is the player's to run; the screen only ever hands it a duration and reads
 * the countdown back, so the contract has to pass the duration through untouched and the
 * countdown has to reach the UI state the button draws from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelSleepTimerTest {

    private lateinit var player: PlayerController
    private lateinit var viewModel: PlayerViewModel
    private val coreState = MutableStateFlow(CorePlayerUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        player = mockk(relaxed = true)
        every { player.state } returns coreState
        every { player.cancelSleepTimer() } returns true
        val songDao = mockk<SongDao>(relaxed = true)
        every { songDao.observeLyricsById(any()) } returns flowOf(null)
        every { songDao.observeById(any()) } returns flowOf(null)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.observeShowQueueCovers() } returns flowOf(true)
        viewModel = PlayerViewModel(
            player,
            songDao,
            mockk<DownloadManager>(relaxed = true),
            settings,
            mockk<PlaylistRepository>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        viewModel.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun startingATimerPassesTheDurationStraightToThePlayer() = runTest {
        viewModel.startSleepTimer(1_800_000L)
        advanceUntilIdle()

        verify(exactly = 1) { player.startSleepTimer(1_800_000L) }
    }

    @Test
    fun cancellingReportsWhetherATimerWasRunning() = runTest {
        assertTrue(viewModel.cancelSleepTimer())

        every { player.cancelSleepTimer() } returns false
        assertFalse(viewModel.cancelSleepTimer())
    }

    @Test
    fun theCountdownReachesTheUiState() = runTest {
        advanceUntilIdle()

        assertFalse(viewModel.state.value.sleepTimerActive)
        assertEquals(0L, viewModel.state.value.sleepTimerRemainingMs)

        coreState.value = CorePlayerUiState(
            sleepTimerActive = true,
            sleepTimerRemainingMs = 1_200_000L,
            sleepTimerTotalMs = 1_800_000L,
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.sleepTimerActive)
        assertEquals(1_200_000L, state.sleepTimerRemainingMs)
        assertEquals(1_800_000L, state.sleepTimerTotalMs)
    }

    @Test
    fun anExpiredTimerIsPublishedAsInactiveAgain() = runTest {
        coreState.value = CorePlayerUiState(
            sleepTimerActive = true,
            sleepTimerRemainingMs = 1_000L,
            sleepTimerTotalMs = 1_800_000L,
        )
        advanceUntilIdle()

        coreState.value = CorePlayerUiState()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.sleepTimerActive)
        assertEquals(0L, viewModel.state.value.sleepTimerRemainingMs)
        assertEquals(0L, viewModel.state.value.sleepTimerTotalMs)
    }
}
