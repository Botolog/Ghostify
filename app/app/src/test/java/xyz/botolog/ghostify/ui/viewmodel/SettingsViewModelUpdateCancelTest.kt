package xyz.botolog.ghostify.ui.viewmodel

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.update.DownloadState
import xyz.botolog.ghostify.update.UpdateDownloadHelper
import xyz.botolog.ghostify.update.UpdateDownloadManager
import xyz.botolog.ghostify.update.UpdateDownloader
import xyz.botolog.ghostify.update.UpdateInfo

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelUpdateCancelTest {

    private lateinit var cacheParent: File
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var musicStore: MusicStore

    private fun updatesDir(): File = File(cacheParent, "updates")

    private fun zipBytes(payloadSize: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(payloadSize) { (it % 251).toByte() })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(0x64, 0x65, 0x78, 0x0A))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun fakeInfo(url: String = "https://example.com/ghostify.apk"): UpdateInfo = UpdateInfo(
        versionName = "1.0.1",
        versionCode = 101L,
        releaseNotes = "notes",
        apkDownloadUrl = url,
        publishedAt = "",
    )

    private class SliceDownloader(
        val full: ByteArray,
        val failAfterBytes: Long? = null,
        var started: CompletableDeferred<Unit>? = null,
        var release: CompletableDeferred<Unit>? = null,
    ) : UpdateDownloader {
        override suspend fun download(
            url: String,
            destPart: File,
            resumeFrom: Long,
            totalHint: Long?,
            onProgress: (Long, Long?) -> Unit,
        ): Long? {
            started?.complete(Unit)
            release?.await()
            val total = full.size.toLong()
            val target = failAfterBytes ?: total
            val from = resumeFrom.coerceIn(0L, total)
            val to = target.coerceIn(from, total)
            destPart.parentFile?.mkdirs()
            val append = from > 0 && destPart.exists()
            val slice = full.sliceArray(from.toInt() until to.toInt())
            if (append) {
                destPart.appendBytes(slice)
            } else {
                destPart.writeBytes(slice)
            }
            val written = if (destPart.exists()) destPart.length() else to
            onProgress(written, total)
            if (failAfterBytes != null) {
                throw java.io.IOException("simulated interruption")
            }
            return total
        }
    }

    @Before
    fun setUp() {
        cacheParent = Files.createTempDirectory("ghostify-vm-cancel-test").toFile()
        val tmp = cacheParent

        context = mockk(relaxed = true)
        every { context.getExternalFilesDir(null) } returns tmp
        every { context.filesDir } returns tmp
        every { context.cacheDir } returns tmp

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        try {
            unmockkObject(xyz.botolog.ghostify.update.UpdateChecker)
        } catch (_: Exception) {
        }
    }

    private fun createViewModel(manager: UpdateDownloadManager): SettingsViewModel = SettingsViewModel(
        context = context,
        settings = settings,
        playlistRepo = playlistRepo,
        songRepo = songRepo,
        musicStore = musicStore,
        updateDownloads = manager,
    )

    @Test
    fun cancelDuringDownloadingPublishesIdleKeepsUpdateInfoAndDismissesDialog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val full = zipBytes()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            downloader = SliceDownloader(full, started = started, release = release),
        )
        val viewModel = createViewModel(manager)
        mockkObject(xyz.botolog.ghostify.update.UpdateChecker)
        coEvery { xyz.botolog.ghostify.update.UpdateChecker.checkForUpdate(any(), any()) } returns fakeInfo()
        viewModel.checkForUpdate()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.updateInfo)
        assertTrue(viewModel.state.value.showUpdateDialog)

        viewModel.confirmUpdate()
        runCurrent()
        started.await()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Downloading)

        viewModel.cancelUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        assertFalse(viewModel.state.value.showUpdateDialog)
        assertNotNull(viewModel.state.value.updateInfo)
        assertEquals("1.0.1", viewModel.state.value.updateInfo?.versionName)
        assertFalse(File(updatesDir(), UpdateDownloadHelper.partName()).exists())
        assertFalse(File(updatesDir(), UpdateDownloadHelper.META_NAME).exists())

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        assertNotNull(viewModel.state.value.updateInfo)

        viewModel.clear()
    }

    @Test
    fun cancelFromPausedClearsPartial() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val full = zipBytes()
        val total = full.size.toLong()
        val half = total / 2
        val dir = updatesDir()
        dir.mkdirs()
        File(dir, UpdateDownloadHelper.partName()).writeBytes(full.sliceArray(0 until half.toInt()))
        File(dir, UpdateDownloadHelper.META_NAME).writeText(
            UpdateDownloadHelper.encodeMeta("https://example.com/ghostify.apk", "1.0.1", total),
        )
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            downloader = SliceDownloader(full),
        )
        val viewModel = createViewModel(manager)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Paused)

        viewModel.cancelUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())

        viewModel.clear()
    }

    @Test
    fun cancelFromResumableErrorClearsPartial() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val full = zipBytes()
        val half = full.size.toLong() / 2
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            downloader = SliceDownloader(full, failAfterBytes = half),
        )
        val viewModel = createViewModel(manager)
        mockkObject(xyz.botolog.ghostify.update.UpdateChecker)
        coEvery { xyz.botolog.ghostify.update.UpdateChecker.checkForUpdate(any(), any()) } returns fakeInfo()
        viewModel.checkForUpdate()
        advanceUntilIdle()
        viewModel.confirmUpdate()
        advanceUntilIdle()
        val failed = viewModel.state.value.downloadState
        assertTrue(failed is DownloadState.Error)
        assertTrue((failed as DownloadState.Error).resumable)

        viewModel.cancelUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        assertFalse(File(updatesDir(), UpdateDownloadHelper.partName()).exists())
        assertFalse(File(updatesDir(), UpdateDownloadHelper.META_NAME).exists())
        assertNotNull(viewModel.state.value.updateInfo)

        viewModel.clear()
    }

    @Test
    fun duplicateCancelIsSafe() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val full = zipBytes()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            downloader = SliceDownloader(full, started = started, release = release),
        )
        val viewModel = createViewModel(manager)
        mockkObject(xyz.botolog.ghostify.update.UpdateChecker)
        coEvery { xyz.botolog.ghostify.update.UpdateChecker.checkForUpdate(any(), any()) } returns fakeInfo()
        viewModel.checkForUpdate()
        advanceUntilIdle()
        viewModel.confirmUpdate()
        runCurrent()
        started.await()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Downloading)

        viewModel.cancelUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        viewModel.cancelUpdate()
        runCurrent()
        viewModel.cancelUpdate()
        runCurrent()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)
        assertFalse(File(updatesDir(), UpdateDownloadHelper.partName()).exists())

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)

        viewModel.clear()
    }

    @Test
    fun cancelDismissesDialogWhileKeepingReleaseMetadata() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val full = zipBytes()
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            downloader = SliceDownloader(full),
        )
        val viewModel = createViewModel(manager)
        mockkObject(xyz.botolog.ghostify.update.UpdateChecker)
        coEvery { xyz.botolog.ghostify.update.UpdateChecker.checkForUpdate(any(), any()) } returns fakeInfo()
        viewModel.checkForUpdate()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.showUpdateDialog)
        assertNotNull(viewModel.state.value.updateInfo)

        viewModel.cancelUpdate()
        runCurrent()
        assertFalse(viewModel.state.value.showUpdateDialog)
        assertNotNull(viewModel.state.value.updateInfo)
        assertEquals("1.0.1", viewModel.state.value.updateInfo?.versionName)
        assertTrue(viewModel.state.value.downloadState is DownloadState.Idle)

        viewModel.clear()
    }
}
