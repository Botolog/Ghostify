package xyz.botolog.ghostify.ui.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import xyz.botolog.ghostify.ui.model.FullPlayerLayout
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelFullPlayerLayoutTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var musicStore: MusicStore

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
        every { settings.observeShowQueueCovers() } returns flowOf(false)
        every { settings.observeLoopPlaylists() } returns flowOf(true)
        coEvery { settings.getStorageDir() } returns "ghostify"
        coEvery { settings.setFullPlayerLayout(any()) } returns Unit

        playlistRepo = mockk()
        every { playlistRepo.observePlaylists() } returns flowOf(emptyList())

        songRepo = mockk(relaxed = true)

        musicStore = mockk(relaxed = true)
        every { musicStore.orphanFiles(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun storedLayoutReachesUiState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val layout = MutableStateFlow("super_compact")
        every { settings.observeFullPlayerLayout() } returns layout

        val viewModel = createViewModel()

        assertEquals(FullPlayerLayout.SUPER_COMPACT, viewModel.state.value.fullPlayerLayout)

        layout.value = "compact"
        assertEquals(FullPlayerLayout.COMPACT, viewModel.state.value.fullPlayerLayout)

        layout.value = "normal"
        assertEquals(FullPlayerLayout.NORMAL, viewModel.state.value.fullPlayerLayout)

        viewModel.clear()
    }

    @Test
    fun legacyStoredTrueReachesUiStateAsSuperCompact() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        every { settings.observeFullPlayerLayout() } returns flowOf("true")

        val viewModel = createViewModel()

        assertEquals(FullPlayerLayout.SUPER_COMPACT, viewModel.state.value.fullPlayerLayout)

        viewModel.clear()
    }

    @Test
    fun missingStoredValueLeavesStateAtDefault() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        every { settings.observeFullPlayerLayout() } returns flowOf(SettingsRepository.DEFAULT_FULL_PLAYER_LAYOUT)

        val viewModel = createViewModel()

        assertEquals(FullPlayerLayout.NORMAL, viewModel.state.value.fullPlayerLayout)

        viewModel.clear()
    }

    @Test
    fun setFullPlayerLayoutPersistsStorageValue() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        every { settings.observeFullPlayerLayout() } returns flowOf("normal")

        val viewModel = createViewModel()
        viewModel.setFullPlayerLayout(FullPlayerLayout.COMPACT)

        coVerify(exactly = 1) { settings.setFullPlayerLayout("compact") }

        viewModel.clear()
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        context = context,
        settings = settings,
        playlistRepo = playlistRepo,
        songRepo = songRepo,
        musicStore = musicStore,
    )
}
