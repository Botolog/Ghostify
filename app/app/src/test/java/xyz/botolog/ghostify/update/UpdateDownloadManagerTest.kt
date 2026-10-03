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
class UpdateDownloadManagerTest {

    private fun tempDir(): File = Files.createTempDirectory("ghostify-update-test").toFile()

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
    fun interruptionPreservesPartialThenResumesToCompletion() = runTest {
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
        assertTrue(failed.progress > 0f)
        val part = File(dir, UpdateDownloadHelper.partName())
        assertTrue(part.exists())
        assertEquals(half, part.length())
        assertFalse(File(dir, UpdateDownloadHelper.FINAL_NAME).exists())

        val completing = SliceDownloader(full, failAfterBytes = null)
        val manager2 = UpdateDownloadManager(scope = this, ioDispatcher = dispatcher, downloader = completing)
        manager2.restore(dir)
        val paused = manager2.currentState()
        assertTrue(paused is DownloadState.Paused)
        assertEquals(UpdateDownloadHelper.progress(half, total), (paused as DownloadState.Paused).progress, 0.01f)

        assertTrue(manager2.resume(dir, info))
        advanceUntilIdle()

        val done = manager2.currentState()
        assertTrue(done is DownloadState.Downloaded)
        val final = File(dir, UpdateDownloadHelper.FINAL_NAME)
        assertTrue(final.exists())
        assertEquals(total, final.length())
    }

    @Test
    fun restoredProgressSurvivesManagerRecreation() = runTest {
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
        assertEquals(
            UpdateDownloadHelper.progress(half, total),
            (restored as DownloadState.Paused).progress,
            0.01f,
        )
        val target = manager.activeTarget()
        assertEquals("https://example.com/ghostify.apk", target.first)
    }

    @Test
    fun duplicateStartWhileDownloadingIsRejected() = runTest {
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

        assertFalse(manager.start(dir, info))
        assertFalse(manager.resume(dir, info))
        assertEquals(1, blocking.invocations.get())

        release.complete(Unit)
        advanceUntilIdle()

        val done = manager.currentState()
        assertTrue(done is DownloadState.Downloaded)
        assertEquals(1, blocking.invocations.get())
    }

    @Test
    fun differentTargetCancelsPreviousWithoutDeletingNewProgress() = runTest {
        val full = zipBytes()
        val dir = tempDir()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val counting = SliceDownloader(full)
        val manager = UpdateDownloadManager(scope = this, ioDispatcher = dispatcher, downloader = counting)
        val first = infoFor("https://example.com/old.apk", "1.0.0")
        val second = infoFor("https://example.com/new.apk", "1.0.1")

        assertTrue(manager.start(dir, first))
        advanceUntilIdle()
        assertTrue(manager.currentState() is DownloadState.Downloaded)

        assertTrue(manager.start(dir, second))
        advanceUntilIdle()
        assertTrue(manager.currentState() is DownloadState.Downloaded)
        assertEquals(2, counting.invocations.get())
    }
}
