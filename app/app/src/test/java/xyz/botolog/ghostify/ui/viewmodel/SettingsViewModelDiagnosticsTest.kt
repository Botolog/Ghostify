package xyz.botolog.ghostify.ui.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.python.PythonDiagnostics
import xyz.botolog.ghostify.python.PythonDiagnosticsBridge
import xyz.botolog.ghostify.python.PythonLibraryInfo
import xyz.botolog.ghostify.ui.contract.SettingsContract.SettingsUiState
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelDiagnosticsTest {

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

        settings = mockk()
        every { settings.observeBitrate() } returns flowOf("320")
        every { settings.observeConcurrency() } returns flowOf(1)
        every { settings.observeAutoDownload() } returns flowOf(false)
        every { settings.observeStorageDir() } returns flowOf("ghostify")
        every { settings.observeLandscapeControlsSide() } returns flowOf("left")
        every { settings.observeShowQueueCovers() } returns flowOf(true)
        every { settings.observeLoopPlaylists() } returns flowOf(true)
        every { settings.observeFullPlayerLayout() } returns flowOf("normal")
        every { settings.observeIncludePreReleaseUpdates() } returns flowOf(false)
        coEvery { settings.getStorageDir() } returns "ghostify"

        playlistRepo = mockk()
        every { playlistRepo.observePlaylists() } returns flowOf(emptyList())

        songRepo = mockk(relaxed = true)
        musicStore = mockk(relaxed = true)
        every { musicStore.orphanFiles(any()) } returns emptyList()

        diagnostics = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun reportedLibrariesReachUiState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        val pending = readReport(
            viewModel,
            PythonDiagnostics(
                pythonVersion = "3.11.14",
                implementation = "CPython",
                libraries = listOf(
                    PythonLibraryInfo("spotdl", "4.5.2", "/data/spotdl/__init__.py"),
                    PythonLibraryInfo("ghostify_dl", "my libs", "/app/ghostify_dl.py"),
                ),
            )
        )

        assertTrue(pending.showPythonLibraries)
        assertFalse(pending.isLoadingPythonLibraries)
        assertNull(pending.pythonLibrariesError)
        assertEquals("3.11.14", pending.pythonVersion)
        assertEquals("CPython", pending.pythonImplementation)
        assertEquals(listOf("spotdl", "ghostify_dl"), pending.pythonLibraries.map { it.name })
        assertEquals("my libs", pending.pythonLibraries[1].version)

        viewModel.clear()
    }

    @Test
    fun failureIsSurfacedReadably() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        val pending = readReport(
            viewModel,
            PythonDiagnostics(error = "The Python diagnostics are unavailable."),
        )

        assertTrue(pending.showPythonLibraries)
        assertFalse(pending.isLoadingPythonLibraries)
        assertEquals("The Python diagnostics are unavailable.", pending.pythonLibrariesError)
        assertTrue(pending.pythonLibraries.isEmpty())

        viewModel.clear()
    }

    @Test
    fun dismissClosesTheSheetAndKeepsTheReport() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        readReport(viewModel, PythonDiagnostics(libraries = listOf(PythonLibraryInfo("requests", "2.32.3"))))
        viewModel.dismissPythonLibraries()

        val state = viewModel.state.value
        assertFalse(state.showPythonLibraries)
        assertEquals(1, state.pythonLibraries.size)

        viewModel.clear()
    }

    @Test
    fun nothingIsRequestedUntilTheButtonIsTapped() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = createViewModel()

        val state = viewModel.state.value
        assertFalse(state.showPythonLibraries)
        assertFalse(state.isLoadingPythonLibraries)
        assertTrue(state.pythonLibraries.isEmpty())
        assertNull(state.pythonLibrariesError)

        viewModel.clear()
    }

    private fun readReport(
        viewModel: SettingsViewModel,
        report: PythonDiagnostics,
    ): SettingsUiState {
        every { diagnostics.reportBlocking() } returns report
        viewModel.showPythonLibraries()
        assertTrue(viewModel.state.value.isLoadingPythonLibraries)
        return awaitSettledReport(viewModel)
    }

    private fun awaitSettledReport(viewModel: SettingsViewModel): SettingsUiState {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPORT_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val current = viewModel.state.value
            if (current.showPythonLibraries && !current.isLoadingPythonLibraries) return current
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return viewModel.state.value
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        context = context,
        settings = settings,
        playlistRepo = playlistRepo,
        songRepo = songRepo,
        musicStore = musicStore,
        diagnostics = diagnostics,
    )

    private companion object {
        private const val REPORT_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 5L
    }
}
