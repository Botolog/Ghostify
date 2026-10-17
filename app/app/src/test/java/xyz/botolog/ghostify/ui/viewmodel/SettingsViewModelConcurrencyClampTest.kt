package xyz.botolog.ghostify.ui.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.python.PythonDiagnosticsBridge
import java.io.File

/**
 * Verifies the download-concurrency clamp: the value is bounded to
 * `1..MAX_CONCURRENCY` both when edited and when loaded, so a legacy/corrupt
 * stored value can never multiply simultaneous native downloader sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelConcurrencyClampTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var musicStore: MusicStore
    private lateinit var diagnostics: PythonDiagnosticsBridge

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        context = mockk(relaxed = true)
        every { context.getExternalFilesDir(null) } returns tmp
        every { context.filesDir } returns tmp
        every { context.cacheDir } returns tmp

        settings = mockk(relaxed = true)
        every { settings.observeBitrate() } returns flowOf("320")
        every { settings.observeConcurrency() } returns flowOf(1)
        every { settings.observeAutoDownload() } returns flowOf(false)
        every { settings.observeStorageDir() } returns flowOf("ghostify")
        every { settings.observeLandscapeControlsSide() } returns flowOf("left")
        every { settings.observeShowQueueCovers() } returns flowOf(true)
        every { settings.observeLoopPlaylists() } returns flowOf(true)
        every { settings.observeFullPlayerLayout() } returns flowOf("normal")
        every { settings.observeIncludePreReleaseUpdates() } returns flowOf(false)
        every { settings.observeAppScale() } returns flowOf(1.0f)
        coEvery { settings.getStorageDir() } returns "ghostify"
        coEvery { settings.setConcurrency(any()) } returns Unit

        playlistRepo = mockk(relaxed = true)
        every { playlistRepo.observePlaylists() } returns flowOf(emptyList())

        songRepo = mockk(relaxed = true)
        musicStore = mockk(relaxed = true)
        every { musicStore.orphanFiles(any()) } returns emptyList()

        diagnostics = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stored concurrency above the maximum is clamped on load`() = runTest {
        every { settings.observeConcurrency() } returns flowOf(99)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(8, viewModel.state.value.concurrentDownloads)
        viewModel.clear()
    }

    @Test
    fun `stored concurrency below the minimum is clamped on load`() = runTest {
        every { settings.observeConcurrency() } returns flowOf(0)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.concurrentDownloads)
        viewModel.clear()
    }

    @Test
    fun `setConcurrency above the maximum is clamped to eight`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        viewModel.setConcurrency(500)
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.setConcurrency(8) }
        viewModel.clear()
    }

    @Test
    fun `setConcurrency below the minimum is clamped to one`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        viewModel.setConcurrency(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.setConcurrency(1) }
        viewModel.clear()
    }

    @Test
    fun `setConcurrency within range is stored unchanged`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        viewModel.setConcurrency(3)
        advanceUntilIdle()

        coVerify(exactly = 1) { settings.setConcurrency(3) }
        viewModel.clear()
    }

    private fun TestScope.createViewModel(): SettingsViewModel = SettingsViewModel(
        context = context,
        settings = settings,
        playlistRepo = playlistRepo,
        songRepo = songRepo,
        musicStore = musicStore,
        diagnostics = diagnostics,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )
}
