package xyz.botolog.ghostify.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDownloadManagerCancelTest {

    private fun tempDir(): File = Files.createTempDirectory("ghostify-update-cancel-test").toFile()

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

    private fun infoFor(url: String, version: String = "1.0.1"): UpdateInfo = UpdateInfo(
        versionName = version,
        versionCode = 101L,
        releaseNotes = "",
        apkDownloadUrl = url,
        publishedAt = "",
    )

    private class SliceDownloader(
        val full: ByteArray,
        val failAfterBytes: Long? = null,
        val invocations: AtomicInteger = AtomicInteger(0),
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
            invocations.incrementAndGet()
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
                throw java.io.IOException("simulated interruption at $written of $total")
            }
            return total
        }
    }

    @Test
    fun cancelDuringDownloadingClearsPartialAndPublishesIdle() = runTest {
        val full = zipBytes()
        val dir = tempDir()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val blocking = SliceDownloader(full, started = started, release = release)
        val manager = UpdateDownloadManager(scope = this, ioDispatcher = dispatcher, downloader = blocking)
        val info = infoFor("https://example.com/ghostify.apk")

        assertTrue(manager.start(dir, info))
        runCurrent()
        started.await()
        assertTrue(manager.currentState() is DownloadState.Downloading)

        assertTrue(manager.cancel(dir))
        advanceUntilIdle()

        assertTrue(manager.currentState() is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())
        assertFalse(File(dir, UpdateDownloadHelper.FINAL_NAME).exists())

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.currentState() is DownloadState.Idle)
    }

    @Test
    fun cancelFromPausedDiscardsPartial() = runTest {
        val full = zipBytes()
        val total = full.size.toLong()
        val half = total / 2
        val dir = tempDir()
        dir.mkdirs()
        File(dir, UpdateDownloadHelper.partName()).writeBytes(full.sliceArray(0 until half.toInt()))
        File(dir, UpdateDownloadHelper.META_NAME).writeText(
            UpdateDownloadHelper.encodeMeta("https://example.com/ghostify.apk", "1.0.1", total),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = dispatcher,
            downloader = SliceDownloader(full),
        )
        val restored = manager.restore(dir)
        assertTrue(restored is DownloadState.Paused)

        assertTrue(manager.cancel(dir))
        assertTrue(manager.currentState() is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())

        val afterRestore = manager.restore(dir)
        assertTrue(afterRestore is DownloadState.Idle)
    }

    @Test
    fun cancelFromResumableErrorClearsPartial() = runTest {
        val full = zipBytes()
        val total = full.size.toLong()
        val half = total / 2
        val dir = tempDir()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failing = SliceDownloader(full, failAfterBytes = half)
        val manager = UpdateDownloadManager(scope = this, ioDispatcher = dispatcher, downloader = failing)
        val info = infoFor("https://example.com/ghostify.apk")

        assertTrue(manager.start(dir, info))
        advanceUntilIdle()

        val failed = manager.currentState()
        assertTrue(failed is DownloadState.Error)
        assertTrue((failed as DownloadState.Error).resumable)

        assertTrue(manager.cancel(dir))
        assertTrue(manager.currentState() is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())
    }

    @Test
    fun duplicateCancelIsSafe() = runTest {
        val full = zipBytes()
        val dir = tempDir()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val blocking = SliceDownloader(full, started = started, release = release)
        val manager = UpdateDownloadManager(scope = this, ioDispatcher = dispatcher, downloader = blocking)
        val info = infoFor("https://example.com/ghostify.apk")

        assertTrue(manager.start(dir, info))
        runCurrent()
        started.await()

        assertTrue(manager.cancel(dir))
        assertFalse(manager.cancel(dir))
        assertFalse(manager.cancel(dir))
        assertTrue(manager.currentState() is DownloadState.Idle)

        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.currentState() is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())
    }

    @Test
    fun stalePartialCleanupWhenIdle() = runTest {
        val full = zipBytes()
        val total = full.size.toLong()
        val half = total / 2
        val dir = tempDir()
        dir.mkdirs()
        File(dir, UpdateDownloadHelper.partName()).writeBytes(full.sliceArray(0 until half.toInt()))
        File(dir, UpdateDownloadHelper.META_NAME).writeText(
            UpdateDownloadHelper.encodeMeta("https://example.com/ghostify.apk", "1.0.1", total),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = dispatcher,
            downloader = SliceDownloader(full),
        )
        assertTrue(manager.currentState() is DownloadState.Idle)

        assertFalse(manager.cancel(dir))
        assertTrue(manager.currentState() is DownloadState.Idle)
        assertFalse(File(dir, UpdateDownloadHelper.partName()).exists())
        assertFalse(File(dir, UpdateDownloadHelper.META_NAME).exists())

        val restored = manager.restore(dir)
        assertTrue(restored is DownloadState.Idle)
    }

    @Test
    fun cancelDoesNotClearInstallationArtifactsWhenDownloaded() = runTest {
        val full = zipBytes()
        val dir = tempDir()
        dir.mkdirs()
        val finalFile = File(dir, UpdateDownloadHelper.FINAL_NAME)
        finalFile.writeBytes(full)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateDownloadManager(
            scope = this,
            ioDispatcher = dispatcher,
            downloader = SliceDownloader(full),
        )
        val restored = manager.restore(dir)
        assertTrue(restored is DownloadState.Downloaded)

        assertFalse(manager.cancel(dir))
        assertTrue(manager.currentState() is DownloadState.Downloaded)
        assertTrue(finalFile.exists())
        assertEquals(full.size.toLong(), finalFile.length())
    }
}
